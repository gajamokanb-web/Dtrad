package com.dtrad.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** État du modèle HY-MT, partagé entre l'écran principal et la bulle flottante. */
sealed class ModelState {
    /** Pas chargé en mémoire (téléchargé ou non). */
    object Idle : ModelState()

    /** [total] vaut -1 si la taille est inconnue. */
    data class Downloading(val done: Long, val total: Long) : ModelState()

    /** Installation : chargement du modèle en mémoire. */
    object Loading : ModelState()

    object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

/**
 * Télécharge puis charge HY-MT une seule fois par processus, et diffuse l'avancement.
 * Les écouteurs sont toujours appelés sur le thread principal.
 */
object ModelManager {

    @Volatile
    var state: ModelState = ModelState.Idle
        private set

    var translator: HyMtTranslator? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ModelState) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    fun isDownloaded(context: Context): Boolean = HyMtTranslator.isDownloaded(context)

    /** Lance téléchargement + chargement s'ils ne sont pas déjà en cours ou terminés. */
    fun start(context: Context) {
        val s = state
        if (s is ModelState.Downloading || s is ModelState.Loading || s is ModelState.Ready) return
        val app = context.applicationContext
        scope.launch {
            try {
                val t = translator ?: HyMtTranslator(app).also { translator = it }
                if (!t.isDownloaded) {
                    publish(ModelState.Downloading(0, -1))
                    t.download { done, total -> publish(ModelState.Downloading(done, total)) }
                }
                publish(ModelState.Loading)
                t.ensureLoaded()
                publish(ModelState.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("ModelManager", "HY-MT indisponible", e)
                publish(ModelState.Error(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Libère la mémoire (le fichier reste sur le téléphone). */
    fun unload() {
        if (state is ModelState.Ready) {
            translator?.close()
            publish(ModelState.Idle)
        }
    }

    /** Appelé quand une traduction échoue : on repasse sur ML Kit et on l'affiche. */
    fun markFailed(message: String?) {
        publish(ModelState.Error(message ?: "échec de la traduction"))
    }
}
