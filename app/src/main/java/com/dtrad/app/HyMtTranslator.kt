package com.dtrad.app

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Traduction EN -> FR avec Tencent HY-MT1.5-1.8B (quantification 1,25 bit), 100 % sur l'appareil.
 * Chaque bulle est traduite avec, en contexte, les bulles précédentes (modèle de prompt « contexte » de HY-MT).
 */
class HyMtTranslator(appContext: Context) {

    private val engine: InferenceEngine = AiChat.getInferenceEngine(appContext)
    private val modelFile = File(appContext.filesDir, MODEL_FILE)
    private var loaded = false

    val isDownloaded: Boolean
        get() = modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES

    /** Télécharge le modèle (~440 Mo) une seule fois. [onProgress] reçoit 0..100. */
    suspend fun download(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (isDownloaded) return@withContext
        val part = File(modelFile.path + ".part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            check(conn.responseCode == 200) { "Téléchargement impossible (HTTP ${conn.responseCode})" }
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            check(part.length() > MIN_MODEL_BYTES) { "Fichier du modèle incomplet" }
            check(part.renameTo(modelFile)) { "Impossible d'enregistrer le modèle" }
        } finally {
            conn.disconnect()
            if (part.exists() && !modelFile.exists()) part.delete()
        }
    }

    /** Charge le modèle en mémoire (lent la première fois). */
    suspend fun ensureLoaded() {
        if (loaded) return
        val st = engine.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }
        if (st is InferenceEngine.State.Error) throw st.exception
        if (st is InferenceEngine.State.Initialized) engine.loadModel(modelFile.absolutePath)
        loaded = true
    }

    /**
     * Traduit [text] vers le français. [previous] = texte anglais des bulles précédentes (peut être vide).
     */
    suspend fun translate(text: String, previous: String): String {
        ensureLoaded()
        engine.resetConversation()

        val content = if (previous.isBlank()) {
            "Translate the following segment into French, without additional explanation.\n\n$text"
        } else {
            "$previous\n参考上面的信息，把下面的文本翻译成French，注意不需要翻译上文，也不要额外解释：\n$text"
        }
        val prompt = BOS + USER + content + ASSISTANT

        val sb = StringBuilder()
        engine.sendUserPrompt(prompt, predictLength = 300, raw = true).collect { sb.append(it) }
        val out = sb.toString().trim()
        Log.i(TAG, "EN: $text\nFR: $out")
        return out
    }

    fun close() {
        try {
            engine.cleanUp() // décharge le modèle ; le moteur reste réutilisable
            loaded = false
        } catch (e: Exception) {
            Log.w(TAG, "close: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "HyMtTranslator"
        const val MODEL_FILE = "Hy-MT1.5-1.8B-1.25bit.gguf"
        const val MODEL_URL =
            "https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT1.5-1.8B-1.25bit.gguf"
        const val MIN_MODEL_BYTES = 100L * 1024 * 1024

        // Jetons du gabarit de chat de HY-MT (écrits dans le texte, parsés comme jetons spéciaux)
        const val BOS = "<｜hy_begin▁of▁sentence｜>"
        const val USER = "<｜hy_User｜>"
        const val ASSISTANT = "<｜hy_Assistant｜>"
    }
}
