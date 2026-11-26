// Concern: preloads bounded neighboring PDF pages | Non-concern: UI bitmap ownership | IO: (serialized page requests) -> cached frames
package com.pyano.viewer

internal class AdjacentPageSession(private val delegate: PdfDocumentSession) : PdfDocumentSession {
    private val ready = mutableMapOf<Int, PageFrame>()
    override val pageCount get() = delegate.pageCount
    override fun render(page: Int): PageFrame = ready.remove(page) ?: delegate.render(page)
    override fun prefetch(page: Int, shouldContinue: () -> Boolean) {
        val neighbors = listOf(page + 1, page - 1).filter { it in 0 until pageCount }
        ready.keys.toList().filter { it !in neighbors }.forEach { ready.remove(it)?.discard() }
        for (neighbor in neighbors) {
            if (!shouldContinue()) return
            if (neighbor in ready) continue
            val frame = try { delegate.render(neighbor) }
            catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
            catch (_: Exception) { continue }
            catch (_: OutOfMemoryError) { clear(); return }
            ready[neighbor] = frame
            if (!shouldContinue()) return
        }
    }
    private fun clear() { ready.values.forEach { it.discard() }; ready.clear() }
    override fun close() { clear(); delegate.close() }
}
