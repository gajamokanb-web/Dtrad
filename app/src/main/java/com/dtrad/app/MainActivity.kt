package com.dtrad.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var btnModel: Button

    private val modelListener: (ModelState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        btnModel = findViewById(R.id.btnModel)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { start() }
        btnModel.setOnClickListener { ModelManager.start(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        status.setText(
            if (Settings.canDrawOverlays(this)) R.string.status_overlay_ok else R.string.status_overlay_ko
        )
        ModelManager.addListener(modelListener)
        // Téléchargement automatique, sauf sur données mobiles (le bouton permet de forcer).
        if (ModelManager.state is ModelState.Idle &&
            (ModelManager.isDownloaded(this) || isUnmetered())
        ) {
            ModelManager.start(applicationContext)
        }
    }

    override fun onPause() {
        ModelManager.removeListener(modelListener)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    private fun isUnmetered(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    /** Affiche l'avancement du modèle : téléchargement, installation (chargement), opérationnel. */
    private fun render(s: ModelState) {
        val keepOn = s is ModelState.Downloading || s is ModelState.Loading
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        when (s) {
            is ModelState.Idle -> {
                modelProgress.visibility = View.GONE
                if (ModelManager.isDownloaded(this) || isUnmetered()) {
                    modelStatus.setText(R.string.model_idle_unmetered)
                    btnModel.visibility = View.GONE
                } else {
                    modelStatus.setText(R.string.model_idle_metered)
                    btnModel.setText(R.string.btn_model)
                    btnModel.visibility = View.VISIBLE
                }
            }

            is ModelState.Downloading -> {
                btnModel.visibility = View.GONE
                modelProgress.visibility = View.VISIBLE
                if (s.total > 0) {
                    val pct = (s.done * 100 / s.total).toInt()
                    modelProgress.isIndeterminate = false
                    modelProgress.progress = pct
                    modelStatus.text =
                        "Téléchargement : ${mb(s.done)} / ${mb(s.total)} Mo ($pct %)\nGarde l'appli ouverte jusqu'à la fin."
                } else {
                    modelProgress.isIndeterminate = true
                    modelStatus.text = "Téléchargement : ${mb(s.done)} Mo…\nGarde l'appli ouverte jusqu'à la fin."
                }
            }

            is ModelState.Loading -> {
                btnModel.visibility = View.GONE
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = true
                modelStatus.setText(R.string.model_loading)
            }

            is ModelState.Ready -> {
                btnModel.visibility = View.GONE
                modelProgress.visibility = View.GONE
                modelStatus.setText(R.string.model_ready)
            }

            is ModelState.Error -> {
                modelProgress.visibility = View.GONE
                modelStatus.text = "Erreur : ${s.message}\nEn attendant, la traduction utilisera ML Kit."
                btnModel.setText(R.string.btn_model_retry)
                btnModel.visibility = View.VISIBLE
            }
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.status_overlay_ko, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        launchCapture()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) launchCapture()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startForegroundService(svc)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Capture d'écran refusée", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val REQ_CAPTURE = 1001
        const val REQ_NOTIF = 1002
    }
}
