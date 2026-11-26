// Concern: verifies neighboring-page cache ownership | Non-concern: Android PDF decoding | IO: (fake page frames) -> rendering and discard assertions
package com.pyano

import com.pyano.viewer.*
import org.junit.Assert.*
import org.junit.Test

class AdjacentPageSessionTest {
    private class Frame : PageFrame {
        override val width = 100
        override val height = 200
        var discarded = false
        override fun discard() { discarded = true }
    }
    private class Document : PdfDocumentSession {
        override val pageCount = 10
        val rendered = mutableListOf<Pair<Int, Frame>>()
        var closed = false
        var failedPage = -1
        override fun render(page: Int): PageFrame {
            check(page != failedPage)
            return Frame().also { rendered.add(page to it) }
        }
        override fun close() { closed = true }
    }
    @Test fun adjacentTurnsReuseFramesAndCloseRecyclesOnlyUnpublishedPages() {
        val document = Document()
        val session = AdjacentPageSession(document)
        val first = session.render(0) as Frame
        session.prefetch(0) { true }
        val next = session.render(1) as Frame
        assertEquals(listOf(0, 1), document.rendered.map { it.first })
        session.prefetch(1) { true }
        assertEquals(listOf(0, 1, 2, 0), document.rendered.map { it.first })
        session.close()
        assertTrue(document.closed)
        assertFalse(first.discarded); assertFalse(next.discarded)
        assertTrue(document.rendered.drop(2).all { it.second.discarded })
    }
    @Test fun jumpingPrunesOldNeighborsAndKeepsAtMostTwoUnpublishedFrames() {
        val document = Document()
        val session = AdjacentPageSession(document)
        try {
            session.prefetch(4) { true }
            session.prefetch(8) { true }
            assertTrue(document.rendered.take(2).all { it.second.discarded })
            assertEquals(listOf(5, 3, 9, 7), document.rendered.map { it.first })
            assertEquals(2, document.rendered.count { !it.second.discarded })
        } finally { session.close() }
    }
    @Test fun supersededPrefetchRetainsLateFrameForQueuedNavigationWithoutRenderingOtherNeighbor() {
        val document = Document()
        val session = AdjacentPageSession(document)
        try {
            session.prefetch(4) { document.rendered.isEmpty() }
            assertEquals(listOf(5), document.rendered.map { it.first })
            val ready = document.rendered.single().second
            assertSame(ready, session.render(5))
            assertEquals(1, document.rendered.size)
            assertFalse(ready.discarded)
        } finally { session.close() }
    }
    @Test fun supersededPrefetchIsRecycledWhenClosedBeforeNavigation() {
        val document = Document()
        val session = AdjacentPageSession(document)
        session.prefetch(4) { document.rendered.isEmpty() }
        val ready = document.rendered.single().second
        assertFalse(ready.discarded)
        session.close()
        assertTrue(ready.discarded)
    }
    @Test fun speculativeFailureKeepsCurrentPageUsableAndIsRetriedOnNavigation() {
        val document = Document().apply { failedPage = 1 }
        val session = AdjacentPageSession(document)
        try {
            val current = session.render(0) as Frame
            session.prefetch(0) { true }
            assertFalse(current.discarded)
            document.failedPage = -1
            assertNotNull(session.render(1))
        } finally { session.close() }
    }
}
