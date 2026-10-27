package com.dtrad.app

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import li.joye.yakuyomi.engine.Translator

/**
 * Traducteur branché sur le moteur d'images (yakuyomi-engine) : HY-MT en priorité, avec les bulles
 * précédentes en contexte (la mémoire continue d'une page à l'autre) ; ML Kit si HY-MT n'est pas prêt.
 * Ne lève jamais d'exception (sauf annulation) : une bulle ratée est rendue telle quelle.
 */
class HyMtEngineTranslator : Translator {

    private val history = ArrayDeque<String>() // texte anglais des dernières bulles
    private val mlkit by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
        )
    }

    override suspend fun translate(queries: List<String>): List<String> {
        val out = ArrayList<String>(queries.size)
        for (q in queries) {
            val src = q.replace(Regex("\\s+"), " ").trim()
            if (src.isEmpty()) {
                out += q
                continue
            }
            val fr = try {
                translateOne(src)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "traduction échouée", e)
                null
            }
            out += if (fr.isNullOrBlank()) q else fr
            remember(src)
        }
        return out
    }

    private suspend fun translateOne(src: String): String {
        val h = ModelManager.translator
        if (ModelManager.hymtReady && h != null) {
            try {
                val r = h.translate(src, contextFor(src))
                if (r.isNotBlank()) return r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "HY-MT a échoué", e)
                ModelManager.markFailed(e.message)
            }
        }
        mlkit.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return mlkit.translate(src).await()
    }

    /** Contexte = dernières bulles (autres que celle-ci), ~700 caractères max. */
    private fun contextFor(src: String): String {
        val parts = ArrayList<String>()
        var len = 0
        for (i in history.indices.reversed()) {
            val h = history[i]
            if (h == src) continue
            if (len + h.length > 700) break
            parts.add(0, h)
            len += h.length + 1
        }
        return parts.joinToString("\n")
    }

    private fun remember(src: String) {
        if (history.contains(src)) return
        history.addLast(src)
        while (history.size > 12) history.removeFirst()
    }

    fun close() {
        runCatching { mlkit.close() }
    }

    private companion object {
        const val TAG = "HyMtEngineTranslator"
    }
}
