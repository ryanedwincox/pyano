// Concern: coordinates profile-isolated score operations | Non-concern: audio or rendering | IO: (library actions) -> observable state
package com.pyano.scores

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ScoreLibraryController(
    private val root: File,
    private val scope: CoroutineScope,
    private val client: CloudScoreClient = CloudScoreClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cacheFactory: (File, ScoreProfile) -> ScoreCache = { directory, selected -> ScoreCache(directory, selected) },
) {
    private val mutableState = MutableStateFlow(ScoreLibraryState())
    val state = mutableState.asStateFlow()
    private var profile: ScoreProfile? = null
    private var cache: ScoreCache? = null
    private var operation: Job? = null
    private var generation = 0

    fun select(selected: ScoreProfile?) {
        generation++
        operation?.cancel()
        profile = selected
        cache = selected?.let { cacheFactory(root, it) }
        mutableState.value = selected?.let { ScoreLibraryState(it.namespace, it.endpoint, it.ownerId, message = "Loading offline scores…") }
            ?: ScoreLibraryState()
        if (selected != null) reload()
    }

    fun viewerSource(score: Score): com.pyano.viewer.ViewerSource? {
        val selected = profile ?: return null
        val target = cache ?: return null
        return com.pyano.viewer.ViewerSource(selected.namespace, score) {
            target.openReadLease(score) { file ->
                com.pyano.viewer.AndroidPdfDocumentSession.open(android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY))
            }
        }
    }

    fun reload() = run("") { _, _ -> }
    fun refresh() = run("") { selected, target ->
        target.updateRemote(client.list(selected))
    }
    fun download(score: Score) = run("Score is available offline.") { selected, target ->
        target.download(score) { output -> client.download(selected, score, output) }
    }
    fun downloadAndOpen(score: Score, onReady: () -> Unit) = run("Score is available offline.", onReady) { selected, target ->
        target.download(score) { output -> client.download(selected, score, output) }
    }
    fun remove(score: Score) = run("Local copy removed; Cloud was not changed.") { _, target -> target.remove(score) }

    fun cancel() {
        if (operation?.isActive != true || !mutableState.value.busy) return
        generation++
        operation?.cancel()
        operation = null
        if (profile == null) mutableState.value = ScoreLibraryState()
        else {
            mutableState.value = mutableState.value.copy(message = "Canceling operation; checking local copies…")
            run("Operation canceled. Any completed changes were kept.") { _, _ -> }
        }
    }

    private fun run(success: String, onSuccess: () -> Unit = {}, action: suspend (ScoreProfile, ScoreCache) -> Unit) {
        val selected = profile ?: return
        val target = cache ?: return
        if (operation?.isActive == true) return
        val token = generation
        mutableState.value = mutableState.value.copy(busy = true)
        operation = scope.launch {
            try {
                val (rows, cleanupWarning) = withContext(io) {
                    action(selected, target)
                    currentCoroutineContext().ensureActive()
                    target.rows() to target.cleanupWarning
                }
                if (token == generation) {
                    mutableState.value = mutableState.value.copy(rows = rows, busy = false, message = cleanupWarning ?: success)
                    onSuccess()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) {
                    val rows = try { withContext(io) { target.rows() } } catch (_: Exception) { null }
                    if (token == generation) mutableState.value = mutableState.value.copy(
                        rows = rows ?: mutableState.value.rows, busy = false,
                        message = listOfNotNull((e as? ScoreFailure)?.message
                            ?: "Score operation failed. Check storage or connection and retry; offline copies were kept.",
                            target.cleanupWarning).distinct().joinToString("\n")
                    )
                }
            }
        }
    }
}
