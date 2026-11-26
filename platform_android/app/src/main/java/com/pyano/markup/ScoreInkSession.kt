// Concern: coordinates local autosave and background Cloud pen sync | Non-concern: gestures | IO: (page edits, connection) -> observable markup
package com.pyano.markup

import android.content.Context
import com.pyano.scores.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class InkPage(val page: Int = -1, val strokes: List<InkStroke> = emptyList(), val loaded: Boolean = false,
    val saving: Boolean = false, val message: String = "Loading pen marks…", val error: Boolean = false, val exporting: Boolean = false)
class ScoreInkSession(
    context: Context, private val profile: ScoreProfile, private val score: Score, private val scope: CoroutineScope,
    private val synchronize: suspend (Int, ScoreInkStore) -> Unit = { page, store -> syncInk(profile, score.scoreId, score.sha256, page, store) },
    private val schedule: (Int) -> Unit = { page -> queueInk(context.applicationContext, profile.profileId, score.scoreId, score.sha256, page) },
    private val withPdf: suspend ((File) -> Unit) -> Unit,
) {
    private val context = context.applicationContext
    private val store = inkStore(this.context, profile, score.scoreId, score.sha256)
    private val mutable = MutableStateFlow(InkPage())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var exporting = false
    private var saving = false
    private var retryEdit: Pair<Int, InkStroke>? = null
    suspend fun watch(page: Int) {
        val token = ++generation
        mutable.value = InkPage(page = page, exporting = exporting, saving = saving)
        while (currentCoroutineContext().isActive) {
            if (retryEdit != null) {
                mutable.value = mutable.value.copy(loaded = false, error = true,
                    message = "An unsaved mark needs storage space. Tap Retry before leaving.")
                delay(15000)
                continue
            }
            try {
                val local = withContext(Dispatchers.IO) { store.read(page) }
                currentCoroutineContext().ensureActive()
                if (token != generation) return
                if (!saving) mutable.value = mutable.value.copy(strokes = local, loaded = true,
                    message = if (local.any { it.pending }) "Saved locally · Cloud pending" else "Checking Cloud…", error = false)
                synchronize(page, store)
                val saved = withContext(Dispatchers.IO) { store.read(page) }
                currentCoroutineContext().ensureActive()
                if (token == generation && !saving) mutable.value = mutable.value.copy(strokes = saved, loaded = true,
                    message = if (saved.any { it.pending }) "Saved locally · Cloud pending" else "Synced", error = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val local = withContext(Dispatchers.IO) { runCatching { store.read(page) }.getOrNull() }
                if (token == generation && !saving) mutable.value = mutable.value.copy(error = true,
                    strokes = local ?: mutable.value.strokes, loaded = local != null,
                    message = error.message?.take(180) ?: "Cloud unavailable. Local marks are kept.")
            }
            delay(15000)
        }
    }
    fun add(page: Int, points: List<List<Float>>) {
        if (!mutable.value.loaded || saving || mutable.value.exporting || page != mutable.value.page) return
        if (mutable.value.strokes.size >= 500) {
            mutable.value = mutable.value.copy(error = true, message = "This page has reached its 500-stroke limit. Existing marks can still be undone.")
            return
        }
        persist(page, InkStroke(UUID.randomUUID().toString(), points))
    }
    fun undo() {
        val value = mutable.value
        if (!value.loaded || saving || value.exporting) return
        value.strokes.lastOrNull { !it.deleted }?.let { persist(value.page, it.copy(deleted = true, pending = true)) }
    }
    private fun persist(page: Int, stroke: InkStroke) {
        if (saving) return
        saving = true
        mutable.value = mutable.value.copy(saving = true, message = "Saving pen marks…", error = false)
        scope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { store.edit(page, stroke); store.read(page) }
                retryEdit = null
                saving = false
                if (mutable.value.page == page) mutable.value = mutable.value.copy(strokes = rows, saving = false, loaded = true,
                    message = "Saved locally · Cloud pending", error = false)
                else mutable.value = mutable.value.copy(saving = false)
                enqueue(page)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                retryEdit = page to stroke
                saving = false
                if (mutable.value.page == page) mutable.value = mutable.value.copy(saving = false, loaded = false, error = true,
                    message = "Could not save this mark. Check storage and tap Retry before leaving.")
            }
        }
    }
    fun retry() {
        retryEdit?.let { persist(it.first, it.second); return }
        val page = mutable.value.page
        if (page >= 0) enqueue(page)
    }
    private fun enqueue(page: Int) {
        try { schedule(page) }
        catch (_: Exception) {
            if (mutable.value.page == page) mutable.value = mutable.value.copy(error = true,
                message = "Saved locally, but Cloud sync could not be queued. Tap Retry.")
        }
    }
    suspend fun export(): String {
        check(!saving && !exporting) { "Wait for pen marks to finish saving" }
        exporting = true
        mutable.value = mutable.value.copy(exporting = true)
        var published: MarkedExport? = null
        return try {
            withContext(Dispatchers.IO) {
                var result: MarkedExport? = null
                val coroutine = currentCoroutineContext()
                withPdf { file -> result = exportMarkedScore(context, file, score.title, store) { coroutine.ensureActive() }; published = result }
                checkNotNull(result).message
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { published?.remove?.invoke() }
            throw error
        } finally { exporting = false; mutable.value = mutable.value.copy(exporting = false) }
    }
}
