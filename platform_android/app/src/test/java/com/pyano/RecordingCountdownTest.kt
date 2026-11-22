// Concern: verifies delayed capture and cancellation | Non-concern: audio hardware | IO: (controlled ticks) -> state and start assertions
package com.pyano

import com.pyano.audio.RecordingCountdown
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class RecordingCountdownTest {
    @Test fun startsOnceOnlyAfterFiveSecondsAndIgnoresRepeatedRequests() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        var started = 0
        val countdown = RecordingCountdown(this) { ticks.receive() }
        countdown.start { started++ }
        countdown.start { error("Duplicate start") }
        assertEquals(5, countdown.seconds.value)
        yield()
        for (second in 5 downTo 1) {
            assertEquals(second, countdown.seconds.value)
            assertEquals(0, started)
            ticks.send(Unit)
            yield()
        }
        assertEquals(0, countdown.seconds.value)
        assertEquals(1, started)
    }

    @Test fun stoppingAnotherWindowDoesNotCancelTheInitiatingWindow() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val countdown = RecordingCountdown(this) { ticks.receive() }
        val initiatingWindow = Any()
        var started = false
        countdown.start(initiatingWindow) { started = true }
        yield()
        countdown.cancel(Any())
        assertEquals(5, countdown.seconds.value)
        repeat(5) { ticks.send(Unit); yield() }
        assertTrue(started)
        countdown.start(initiatingWindow) { error("Stopped window started capture") }
        countdown.cancel(initiatingWindow)
        yield()
        assertEquals(0, countdown.seconds.value)
    }

    @Test fun cancellationPreventsCaptureAndOldCleanupCannotClearRestartedCountdown() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        var started = 0
        val countdown = RecordingCountdown(this) { ticks.receive() }
        countdown.start { error("Canceled capture started") }
        yield()
        ticks.send(Unit)
        yield()
        assertEquals(4, countdown.seconds.value)
        countdown.cancel()
        assertEquals(0, countdown.seconds.value)
        countdown.start { started++ }
        yield()
        assertEquals(5, countdown.seconds.value)
        repeat(5) { ticks.send(Unit); yield() }
        assertEquals(1, started)
        countdown.start { error("Disposed capture started") }
        countdown.cancel()
        yield()
        assertEquals(0, countdown.seconds.value)
    }
}
