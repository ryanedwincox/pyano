// Concern: verifies bounded persisted reading state | Non-concern: Android rendering | IO: (positions and storage failures) -> assertions
package com.pyano

import com.pyano.viewer.*
import com.pyano.scores.Score
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingStateTest {
    @get:Rule val temp = TemporaryFolder()
    private val namespace = "a".repeat(64)
    private fun score(version: String) = Score("fixture", "Fixture", version, 5, version.padEnd(64, 'a'))

    @Test fun clampsFinitePageZoomPanAndExtremePageDimensions() {
        assertEquals(ReadingState(1, 1f, 0f, -1f), ReadingState(999, Float.NaN, Float.POSITIVE_INFINITY, -4f).bounded(2))
        assertThrows(IllegalArgumentException::class.java) { ReadingState().bounded(0) }
        listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE, 1200 to 1600).forEach { (w, h) ->
            val size = boundedPageSize(w, h)
            assertTrue(size.first in 1..4096 && size.second in 1..4096)
            assertTrue(size.first.toLong() * size.second <= MAX_PAGE_PIXELS)
        }
        assertThrows(IllegalArgumentException::class.java) { boundedPageSize(0, 10) }
    }
    @Test fun reconstructsExactVersionAndFallsBackThenClampsNewVersion() {
        val root = temp.newFolder()
        val store = ReadingStateStore(root)
        store.save(namespace, score("1"), ReadingState(8, 2f, .5f, -.5f))
        val restarted = ReadingStateStore(root)
        assertEquals(8, restarted.load(namespace, score("1")).reading.page)
        assertEquals(1, restarted.load(namespace, score("2")).reading.bounded(2).page)
        store.save(namespace, score("2"), ReadingState(1))
        assertEquals(8, store.load(namespace, score("1")).reading.page)
        assertEquals(0, store.load("b".repeat(64), score("1")).reading.page)
    }
    @Test fun failedAtomicReplacementPreservesOldPositionAndMalformedRecordsResetVisibly() {
        val root = temp.newFolder()
        ReadingStateStore(root).save(namespace, score("1"), ReadingState(3))
        val failing = ReadingStateStore(root) { _, _ -> error("simulated full storage") }
        assertThrows(IllegalStateException::class.java) { failing.save(namespace, score("1"), ReadingState(8)) }
        assertEquals(3, ReadingStateStore(root).load(namespace, score("1")).reading.page)
        root.walkTopDown().first { it.extension == "json" }.writeText(" ".repeat(65_537))
        val reset = ReadingStateStore(root).load(namespace, score("1"))
        assertEquals(ReadingState(), reset.reading)
        assertNotNull(reset.warning)
        assertTrue(root.walkTopDown().none { it.extension == "part" })
    }
    @Test fun keepsSixteenMostRecentVersionsAndFallsBackAfterEviction() {
        val store = ReadingStateStore(temp.newFolder())
        (1..18).forEach { store.save(namespace, score(it.toString()), ReadingState(it)) }
        assertEquals(18, store.load(namespace, score("1")).reading.page)
        assertEquals(3, store.load(namespace, score("3")).reading.page)
    }
}
