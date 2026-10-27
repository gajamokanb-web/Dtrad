package com.dtrad.app

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.ModelDownloader
import li.joye.yakuyomi.engine.ModelProgress
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslationEngine
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** État de la préparation des modèles (images + HY-MT), partagé entre l'écran principal et la bulle. */
sealed class ModelState {
    /** Rien en cours : modèles pas (ou plus) chargés en mémoire. */
    object Idle : ModelState()

    /** [total] vaut -1 si la taille est inconnue. */
    data class Downloading(val step: String, val done: Long, val total: Long) : ModelState()

    /** Installation : chargement en mémoire. */
    data class Loading(val step: String) : ModelState()

    object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

/**
 * Prépare une seule fois par processus : modèles d'image (détection, OCR, effacement) puis HY-MT,
 * en téléchargeant ce qui manque, puis en les chargeant. Les écouteurs sont appelés sur le thread principal.
 */
object ModelManager {

    private const val STEP_IMAGES = "Étape 1/2 · Modèles d'image (détection, lecture, effacement)"
    private const val STEP_HYMT = "Étape 2/2 · Modèle de traduction HY-MT"
    private val ENGINE_ROLES = setOf("detector", "ocr", "inpainter")
    private const val ALPHABET = "models/alphabet-all-v5.txt"

    @Volatile
    var state: ModelState = ModelState.Idle
        private set

    /** Moteur d'image (détection → OCR → effacement → mise en page), prêt quand non nul. */
    @Volatile
    var engine: TranslationEngine? = null
        private set

    var translator: HyMtTranslator? = null
        private set

    /** HY-MT est chargé et utilisable. */
    @Volatile
    var hymtReady = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ModelState) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engineTranslator: HyMtEngineTranslator? = null

    /** À appeler depuis le thread principal. Notifie tout de suite l'état courant. */
    fun addListener(listener: (ModelState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (ModelState) -> Unit) {
        listeners.remove(listener)
    }

    private fun publish(s: ModelState) {
        state = s
        handler.post { listeners.forEach { it(s) } }
    }

    private fun engineDir(context: Context) = File(context.filesDir, "engine-models")
    private fun marker(context: Context) = File(engineDir(context), ".complete")

    private fun engineModelsComplete(context: Context): Boolean =
        marker(context).exists() && resolveModels(context) != null

    /** Tout est déjà sur le téléphone (donc utilisable hors ligne). */
    fun isDownloaded(context: Context): Boolean =
        HyMtTranslator.isDownloaded(context) && engineModelsComplete(context)

    private fun resolveModels(context: Context): ModelSet? {
        val files = engineDir(context).listFiles().orEmpty()
            .filter { it.name.endsWith(".param") }
            .map { it.name to it.absolutePath }
        return ModelSet.resolve(files)
    }

    /** Lance téléchargements + chargements s'ils ne sont pas déjà en cours ou terminés. */
    fun start(context: Context) {
        val s = state
        if (s is ModelState.Downloading || s is ModelState.Loading || s is ModelState.Ready) return
        val app = context.applicationContext
        scope.launch {
            try {
                // 1) Télécharger les modèles d'image (hors ligne si déjà complets)
                if (engine == null && !engineModelsComplete(app)) downloadEngineModels(app)

                // 2) Télécharger HY-MT
                val t = translator ?: HyMtTranslator(app).also { translator = it }
                if (!hymtReady && !t.isDownloaded) {
                    publish(ModelState.Downloading(STEP_HYMT, 0, -1))
                    t.download { done, total -> publish(ModelState.Downloading(STEP_HYMT, done, total)) }
                }

                // 3) Installer le moteur d'image
                if (engine == null) {
                    publish(ModelState.Loading("Installation du moteur d'image… (première fois : un peu long)"))
                    engine = withContext(Dispatchers.Default) { buildEngine(app) }
                }

                // 4) Installer HY-MT
                if (!hymtReady) {
                    publish(ModelState.Loading("Installation de HY-MT : chargement en mémoire… (peut prendre une minute)"))
                    t.ensureLoaded()
                    hymtReady = true
                }
                publish(ModelState.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("ModelManager", "préparation des modèles échouée", e)
                publish(ModelState.Error(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun downloadEngineModels(app: Context) {
        publish(ModelState.Downloading(STEP_IMAGES, 0, -1))
        val manifest = ModelDownloader.fetchManifest().filter { it.role in ENGINE_ROLES }
        val sizes = manifest.associate { it.name to it.size }
        val total = manifest.sumOf { it.size }
        var finished = 0L
        ModelDownloader.ensure(manifest, engineDir(app)) { p ->
            when (p) {
                is ModelProgress.Downloading -> publish(ModelState.Downloading(STEP_IMAGES, finished + p.bytes, total))
                is ModelProgress.Done -> {
                    finished += sizes[p.name] ?: 0L
                    publish(ModelState.Downloading(STEP_IMAGES, finished, total))
                }

                is ModelProgress.Verifying -> publish(ModelState.Downloading(STEP_IMAGES, finished, total))
                is ModelProgress.Failed -> Log.e("ModelManager", "échec ${p.name} : ${p.error}")
            }
        }
        check(resolveModels(app) != null) { "Modèles d'image incomplets après téléchargement" }
        marker(app).writeText(manifest.joinToString("\n") { it.name })
    }

    private fun buildEngine(app: Context): TranslationEngine {
        val models = resolveModels(app) ?: error("Modèles d'image introuvables")
        val alphabet = app.assets.open(ALPHABET).bufferedReader().use { it.readLines() }
        check(NcnnBackend.available) { "Bibliothèque NCNN non chargée (téléphone ARM 64 bits requis)" }

        // Source anglaise → français : texte horizontal, retour à la ligne par mots.
        val cfg = EngineConfig(render = RenderConfig(orientation = TextOrientation.HORIZONTAL))
        val tr = HyMtEngineTranslator().also { engineTranslator = it }
        val detector = Detector(models.detectorNcnn ?: error("détecteur manquant"), cfg.detector)
        val ocr = Ocr(models.ocr, alphabet, cfg.ocr)
        val inpainter = Inpainter(models.aotInpainterNcnn ?: error("modèle d'effacement manquant"), cfg.inpainter)
        val typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        return Pipeline(detector, ocr, tr, inpainter, cfg, typeface).also { it.warmUp() }
    }

    /** Libère la mémoire (les fichiers restent sur le téléphone). */
    fun unload() {
        if (state is ModelState.Downloading || state is ModelState.Loading) return
        runCatching { engine?.close() }
        engine = null
        engineTranslator?.close()
        engineTranslator = null
        if (hymtReady) translator?.close()
        hymtReady = false
        publish(ModelState.Idle)
    }

    /** Appelé quand HY-MT échoue pendant une traduction : on repasse sur ML Kit et on l'affiche. */
    fun markFailed(message: String?) {
        hymtReady = false
        publish(ModelState.Error("HY-MT : ${message ?: "échec de la traduction"}"))
    }
}
