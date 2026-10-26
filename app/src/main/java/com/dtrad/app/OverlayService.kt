package com.dtrad.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestImage: Image? = null
    private val imageLock = Any()

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: TranslationOverlayView? = null

    private var busy = false
    private var hymt: HyMtTranslator? = null
    private var hymtReady = false
    private val history = ArrayDeque<String>() // texte anglais des dernières bulles (contexte)
    private var screenW = 0
    private var screenH = 0

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        setupCapture(proj)
        showBubble()
        prepareModel()
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- capture

    private fun realScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun setupCapture(proj: MediaProjection) {
        val (w, h) = realScreenSize()
        screenW = w
        screenH = h

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(imageLock) {
                    latestImage?.close()
                    latestImage = img
                }
            }
        }, handler)
        imageReader = reader

        virtualDisplay = proj.createVirtualDisplay(
            "dtrad-capture",
            w,
            h,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun grabFrame(): Bitmap? {
        synchronized(imageLock) {
            val img = latestImage ?: return null
            val plane = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * img.width
            val full = Bitmap.createBitmap(
                img.width + rowPadding / plane.pixelStride,
                img.height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            return if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        }
    }

    // ------------------------------------------------------------ OCR + trad

    private data class Block(val box: Rect, val source: String, val bg: Int, val fg: Int)

    private fun prepareModel() {
        scope.launch {
            // Secours : ML Kit (petit modèle, rapide)
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit model: ${e.message}")
            }

            // Moteur principal : HY-MT 1,25 bit
            try {
                val h = HyMtTranslator(applicationContext)
                hymt = h
                if (!h.isDownloaded) {
                    toast("Téléchargement du modèle HY-MT (~440 Mo)…")
                    h.download { pct ->
                        handler.post { if (!busy) setBubbleLabel("$pct%") }
                    }
                }
                setBubbleLabel("…")
                toast("Chargement du modèle HY-MT…")
                h.ensureLoaded()
                hymtReady = true
                toast("HY-MT prêt : double-tape sur la bulle T")
            } catch (e: Throwable) {
                Log.e(TAG, "HY-MT indisponible", e)
                hymtReady = false
                toast("HY-MT indisponible (${e.message}) : traduction ML Kit")
            } finally {
                if (!busy) setBubbleLabel("T")
            }
        }
    }

    private suspend fun recognize(bmp: Bitmap): List<Block> {
        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val blocks = mutableListOf<Block>()
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            val source = block.lines.joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (source.count { it.isLetter() } < 2) continue
            val bg = sampleBackground(bmp, box)
            val fg = if (luminance(bg) > 0.5f) Color.BLACK else Color.WHITE
            blocks += Block(box, source, bg, fg)
        }
        return blocks.sortedBy { it.box.top }
    }

    /** Contexte = dernières bulles (autres que celle-ci), ~700 caractères max. */
    private fun contextFor(source: String): String {
        val parts = ArrayList<String>()
        var len = 0
        for (i in history.indices.reversed()) {
            val h = history[i]
            if (h == source) continue
            if (len + h.length > 700) break
            parts.add(0, h)
            len += h.length + 1
        }
        return parts.joinToString("\n")
    }

    private fun remember(source: String) {
        if (history.contains(source)) return
        history.addLast(source)
        while (history.size > 12) history.removeFirst()
    }

    private suspend fun translateOne(source: String, previous: String): String {
        val h = hymt
        if (hymtReady && h != null) {
            try {
                val out = h.translate(source, previous)
                if (out.isNotBlank()) return out
            } catch (e: Throwable) {
                Log.e(TAG, "HY-MT a échoué", e)
                hymtReady = false
                toast("HY-MT a échoué (${e.message}) : bascule sur ML Kit")
            }
        }
        return translator.translate(source).await()
    }

    private fun sampleBackground(bmp: Bitmap, r: Rect): Int {
        val m = 4
        val xs = intArrayOf(r.left - m, (r.left + r.right) / 2, r.right + m)
        val ys = intArrayOf(r.top - m, (r.top + r.bottom) / 2, r.bottom + m)
        var rs = 0
        var gs = 0
        var bs = 0
        var n = 0
        for (xi in xs.indices) {
            for (yi in ys.indices) {
                if (xi == 1 && yi == 1) continue // centre = texte
                val x = xs[xi].coerceIn(0, bmp.width - 1)
                val y = ys[yi].coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                rs += Color.red(c)
                gs += Color.green(c)
                bs += Color.blue(c)
                n++
            }
        }
        return Color.rgb(rs / n, gs / n, bs / n)
    }

    private fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    // ------------------------------------------------------------ UI flottante

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showBubble() {
        val size = dp(56)
        val tv = TextView(this).apply {
            text = "T"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6335CFF"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                translateVisiblePage()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                dismissOverlay()
                return true
            }
        })

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        tv.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX
                    val dy = ev.rawY - touchY
                    if (dragging || dx * dx + dy * dy > slop * slop) {
                        dragging = true
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
            }
            true
        }

        wm.addView(tv, lp)
        bubble = tv
        bubbleParams = lp
    }

    private fun setBubbleLabel(label: String) {
        bubble?.text = label
    }

    /** Remet la bulle au-dessus de la traduction (ordre d'ajout = ordre d'affichage). */
    private fun bringBubbleToFront() {
        val b = bubble ?: return
        val lp = bubbleParams ?: return
        try {
            wm.removeView(b)
            wm.addView(b, lp)
        } catch (_: Exception) {
        }
    }

    private fun translateVisiblePage() {
        if (busy) return
        busy = true
        setBubbleLabel("…")
        dismissOverlay()
        bubble?.visibility = View.INVISIBLE

        scope.launch {
            try {
                delay(400) // laisse l'écran se redessiner sans la bulle ni l'ancienne traduction
                val bmp = grabFrame()
                bubble?.visibility = View.VISIBLE
                if (bmp == null) {
                    toast("Capture impossible, réessaie")
                } else {
                    val blocks = withContext(Dispatchers.Default) { recognize(bmp) }
                    if (blocks.isEmpty()) {
                        toast("Aucun texte détecté")
                    } else {
                        val view = showOverlay()
                        bringBubbleToFront()
                        for (b in blocks) {
                            if (overlay !== view) break // l'utilisateur a fermé la traduction
                            val french = translateOne(b.source, contextFor(b.source))
                            remember(b.source)
                            if (overlay !== view) break
                            view.addItem(OverlayItem(RectF(b.box), french, b.bg, b.fg))
                        }
                    }
                }
            } catch (e: Exception) {
                toast("Erreur : ${e.message}")
            } finally {
                bubble?.visibility = View.VISIBLE
                setBubbleLabel("T")
                bringBubbleToFront()
                busy = false
            }
        }
    }

    private fun showOverlay(): TranslationOverlayView {
        val view = TranslationOverlayView(this, emptyList()) { dismissOverlay() }
        val lp = WindowManager.LayoutParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(view, lp)
        overlay = view
        return view
    }

    private fun dismissOverlay() {
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dtrad", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Dtrad est actif")
            .setContentText("Double-tape sur la bulle T pour traduire la page.")
            .setOngoing(true)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    // ------------------------------------------------------------ fin de vie

    override fun onDestroy() {
        dismissOverlay()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        scope.cancel()
        hymt?.close()
        hymt = null
        translator.close()
        recognizer.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.dtrad.app.STOP"
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "dtrad"
        private const val NOTIF_ID = 1
    }
}
