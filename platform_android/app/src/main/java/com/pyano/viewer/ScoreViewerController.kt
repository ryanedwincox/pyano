// Concern: serializes private PDF viewing and lifecycle | Non-concern: audio ownership | IO: (reader actions) -> bounded page state
package com.pyano.viewer

import com.pyano.scores.Score
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class ViewerSource(val namespace: String, val score: Score, val markup: com.pyano.markup.ScoreInkSession?, val open: suspend () -> PdfDocumentSession) {
    constructor(namespace: String, score: Score, open: suspend () -> PdfDocumentSession) : this(namespace, score, null, open)
}
data class ScoreViewerState(
    val source: ViewerSource? = null,
    val reading: ReadingState = ReadingState(),
    val pageCount: Int = 0,
    val frame: PageFrame? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val session: Long = 0,
)

class ScoreViewerController(
    scope: CoroutineScope,
    private val positions: ReadingStateStore,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Request(val state: ScoreViewerState, val id: Long, val active: Boolean, val restore: Boolean, val resourceEpoch: Long)
    private val mutableState = MutableStateFlow(ScoreViewerState())
    val state = mutableState.asStateFlow()
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val sequence = AtomicLong()
    private val pendingWrites = java.util.concurrent.ConcurrentHashMap<String, Pair<ViewerSource, ReadingState>>()
    private val hosts = mutableMapOf<Any, Boolean>()
    private var foreground = true
    private var suspended = false
    private var resourceEpoch = 0L
    private val worker = scope.launch(io) {
        val workerContext = currentCoroutineContext()
        var document: PdfDocumentSession? = null
        var openedSource: ViewerSource? = null
        var openedEpoch = -1L
        var renderedPage = -1
        val savedPositions = linkedMapOf<String, ReadingState>()
        fun save(source: ViewerSource, reading: ReadingState): String? {
            val key = source.namespace + source.score.identity
            if (savedPositions[key] == reading) return null
            return try {
                positions.save(source.namespace, source.score, reading)
                savedPositions.remove(key); savedPositions[key] = reading
                while (savedPositions.size > 32) savedPositions.remove(savedPositions.keys.first())
                null
            } catch (_: Exception) { "Could not save reading position. Reading remains available; check free space." }
        }
        try {
            for (request in requests) {
                val source = request.state.source
                var pendingWarning: String? = null
                try {
                    pendingWrites.entries.toList().forEach { entry ->
                        if ((!request.active || request.restore || entry.value.first !== source) && pendingWrites.remove(entry.key, entry.value)) {
                            val (savedSource, savedReading) = entry.value
                            save(savedSource, savedReading)?.let {
                                if (savedSource === source) pendingWarning = it
                                warning(savedSource, it)
                            }
                        }
                    }
                    if (!request.active || source == null || openedSource !== source || openedEpoch != request.resourceEpoch) {
                        document?.close(); document = null; openedSource = null; renderedPage = -1
                    }
                    if (!request.active || source == null || request.id != sequence.get()) continue
                    var message: String? = pendingWarning
                    var reading = request.state.reading
                    if (document == null) {
                        document = source.open(); openedSource = source; openedEpoch = request.resourceEpoch
                        require(document.pageCount > 0) { "Empty document" }
                    }
                    if (request.restore) positions.load(source.namespace, source.score).let { reading = it.reading; message = message ?: it.warning }
                    val pages = document.pageCount
                    reading = reading.bounded(pages)
                    val replacement = if (renderedPage != reading.page || request.state.frame == null) document.render(reading.page) else null
                    if (request.id != sequence.get() || !currentCoroutineContext().isActive) {
                        replacement?.discard(); currentCoroutineContext().ensureActive(); continue
                    }
                    val published = synchronized(this@ScoreViewerController) {
                        if (request.id != sequence.get() || !workerContext.isActive || mutableState.value.session != request.state.session) false
                        else {
                            mutableState.value = mutableState.value.copy(
                                reading = reading, pageCount = pages, frame = replacement ?: mutableState.value.frame, loading = false, message = message
                            )
                            true
                        }
                    }
                    if (!published) replacement?.discard() else {
                        renderedPage = reading.page
                        save(source, reading)?.let { positionWarning(request, it) }
                        pendingWrites.remove(source.namespace + source.score.identity, source to reading)
                        document.prefetch(reading.page) { request.id == sequence.get() && workerContext.isActive }
                    }
                } catch (canceled: CancellationException) { throw canceled }
                catch (_: OutOfMemoryError) {
                    try { document?.close() } catch (_: Exception) { }
                    document = null; openedSource = null; renderedPage = -1
                    failure(request, "Not enough memory to render this PDF. Close other apps, then Retry or return to scores.")
                } catch (_: Exception) {
                    try { document?.close() } catch (_: Exception) { }
                    document = null; openedSource = null; renderedPage = -1
                    failure(request, "Cannot open this PDF. It may be missing, empty, encrypted or unsupported. Download it again, then Retry.")
                }
            }
        } finally { try { document?.close() } catch (_: Exception) { } }
    }

    @Synchronized private fun warning(source: ViewerSource, message: String) {
        if (mutableState.value.source === source) mutableState.value = mutableState.value.copy(message = message)
    }
    @Synchronized private fun positionWarning(request: Request, message: String) {
        if (request.id == sequence.get() && request.resourceEpoch == resourceEpoch &&
            mutableState.value.session == request.state.session && mutableState.value.frame != null) {
            mutableState.value = mutableState.value.copy(message = message)
        }
    }
    @Synchronized private fun failure(request: Request, message: String) {
        if (mutableState.value.session == request.state.session && request.id == sequence.get()) {
            mutableState.value = mutableState.value.copy(frame = null, loading = false, message = message)
        }
    }
    private fun rememberPosition() {
        val current = mutableState.value
        val source = current.source ?: return
        if (current.pageCount > 0) pendingWrites[source.namespace + source.score.identity] = source to current.reading
    }
    private fun enqueue(restore: Boolean = false) {
        val id = sequence.incrementAndGet()
        requests.trySend(Request(mutableState.value, id, foreground && !suspended, restore, resourceEpoch))
    }
    @Synchronized fun open(source: ViewerSource) {
        rememberPosition(); suspended = false
        mutableState.value = ScoreViewerState(source = source, loading = true, session = sequence.get() + 1)
        enqueue(restore = true)
    }
    @Synchronized fun close() {
        rememberPosition(); resourceEpoch++
        mutableState.value = ScoreViewerState(); suspended = false; enqueue()
    }
    fun clearProfile() = close()
    @Synchronized fun page(delta: Int) {
        val current = mutableState.value
        if (current.pageCount <= 0) return
        val next = (current.reading.page.toLong() + delta).coerceIn(0, (current.pageCount - 1).toLong()).toInt()
        if (next == current.reading.page) return
        mutableState.value = current.copy(reading = current.reading.copy(page = next, panX = 0f, panY = 0f), frame = null, loading = true)
        rememberPosition(); enqueue()
    }
    @Synchronized fun transform(zoom: Float, panX: Float, panY: Float) {
        val current = mutableState.value
        if (current.pageCount <= 0) return
        val reading = current.reading.copy(zoom = zoom, panX = panX, panY = panY).bounded(current.pageCount)
        if (reading == current.reading) return
        mutableState.value = current.copy(reading = reading)
        rememberPosition(); enqueue()
    }
    @Synchronized fun registerHost(active: Boolean): Any = Any().also { hosts[it] = active; foreground(hosts.values.any { visible -> visible }) }
    @Synchronized fun hostForeground(host: Any, active: Boolean) {
        if (host in hosts) { hosts[host] = active; foreground(hosts.values.any { it }) }
    }
    @Synchronized fun unregisterHost(host: Any) { hosts.remove(host); foreground(hosts.values.any { it }) }
    @Synchronized fun foreground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (!active) {
            resourceEpoch++
            mutableState.value = mutableState.value.copy(frame = null, loading = false)
            enqueue()
        } else if (mutableState.value.source != null) retry()
    }
    @Synchronized fun trimMemory() {
        suspended = foreground; resourceEpoch++
        if (mutableState.value.source != null) mutableState.value = mutableState.value.copy(
            frame = null, loading = false, message = "PDF memory released. Tap Retry to reopen the saved page."
        )
        enqueue()
    }
    @Synchronized fun retry() {
        if (mutableState.value.source == null) return
        suspended = false
        mutableState.value = mutableState.value.copy(loading = true, message = null)
        enqueue(restore = mutableState.value.pageCount == 0)
    }
    suspend fun shutdown() { synchronized(this) { sequence.incrementAndGet(); requests.close() }; worker.cancelAndJoin() }
}
