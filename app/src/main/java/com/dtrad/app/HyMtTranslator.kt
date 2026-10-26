package com.dtrad.app

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Traduction EN -> FR avec Tencent HY-MT1.5-1.8B (quantification 1,25 bit), 100 % sur l'appareil.
 * Chaque bulle est traduite avec, en contexte, les bulles précédentes (modèle de prompt « contexte » de HY-MT).
 */
class HyMtTranslator(appContext: Context) {

    private val engine: InferenceEngine = AiChat.getInferenceEngine(appContext)
    private val modelFile = modelFile(appContext)
    private var loaded = false

    val isDownloaded: Boolean
        get() = modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES

    /**
     * Télécharge le modèle (~440 Mo) une seule fois, avec reprise si interrompu.
     * [onProgress] reçoit (octets reçus, octets totaux ou -1 si inconnu).
     */
    suspend fun download(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (isDownloaded) return@withContext
        val part = File(modelFile.path + ".part")
        var resumeFrom = if (part.exists()) part.length() else 0L

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        try {
            val code = conn.responseCode
            if (code == 416) {
                part.delete()
                throw IOException("Reprise impossible, réessaie")
            }
            check(code == 200 || code == 206) { "Téléchargement impossible (HTTP $code)" }
            val append = code == 206
            if (!append) resumeFrom = 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) length + resumeFrom else -1L

            var done = resumeFrom
            var lastReport = done
            onProgress(done, total)
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 512 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            check(part.length() > MIN_MODEL_BYTES) { "Fichier du modèle incomplet" }
            check(part.renameTo(modelFile)) { "Impossible d'enregistrer le modèle" }
        } finally {
            conn.disconnect() // le .part est conservé pour reprendre au prochain essai
        }
    }

    /** Charge le modèle en mémoire (lent la première fois). */
    suspend fun ensureLoaded() {
        if (loaded) return
        var st = engine.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }
        if (st is InferenceEngine.State.Error) {
            engine.cleanUp() // remet le moteur à zéro pour un nouvel essai
            st = engine.state.first { it is InferenceEngine.State.Initialized }
        }
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

    companion object {
        private const val TAG = "HyMtTranslator"
        private const val MODEL_FILE = "Hy-MT1.5-1.8B-1.25bit.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT1.5-1.8B-1.25bit.gguf"
        private const val MIN_MODEL_BYTES = 100L * 1024 * 1024

        fun modelFile(context: Context) = File(context.filesDir, MODEL_FILE)

        fun isDownloaded(context: Context): Boolean {
            val f = modelFile(context)
            return f.exists() && f.length() > MIN_MODEL_BYTES
        }

        // Jetons du gabarit de chat de HY-MT (écrits dans le texte, parsés comme jetons spéciaux)
        private const val BOS = "<｜hy_begin▁of▁sentence｜>"
        private const val USER = "<｜hy_User｜>"
        private const val ASSISTANT = "<｜hy_Assistant｜>"
    }
}
