// Role: JVM unit tests for the pure DSP math in audio/MasteringEngine
// (dB<->linear, BiquadFilter magnitude, Limiter ceiling, Compressor gain reduction).
// NOT concerned with: AudioTrack playback, file I/O, WAV export, or UI state — only the
// allocation-free math classes that run unchanged on a plain JVM.
package com.pyano

import com.pyano.audio.BiquadFilter
import com.pyano.audio.Compressor
import com.pyano.audio.Limiter
import com.pyano.audio.dbToLinear
import com.pyano.audio.linearToDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MasteringEngineTest {

    private val sampleRate = 48000

    @Test
    fun linearToDbAndBackRoundTrips() {
        // For values well inside the [-96, 0] clamp, dbToLinear(linearToDb(x)) ~= x.
        for (x in listOf(1.0f, 0.5f, 0.25f, 0.1f, 0.01f)) {
            val round = dbToLinear(linearToDb(x))
            assertEquals("round trip for $x", x, round, x * 1e-3f + 1e-6f)
        }
    }

    @Test
    fun linearToDbKnownPoints() {
        assertEquals(0f, linearToDb(1f), 1e-3f)        // unity -> 0 dB
        assertEquals(-6.0206f, linearToDb(0.5f), 1e-2f) // half amplitude -> ~-6 dB
        assertEquals(-96f, linearToDb(0f), 0f)          // silence clamps to floor
        assertEquals(-96f, linearToDb(-1f), 0f)         // non-positive clamps to floor
    }

    @Test
    fun dbToLinearKnownPoints() {
        assertEquals(1f, dbToLinear(0f), 1e-6f)
        assertEquals(0.5f, dbToLinear(-6.0206f), 1e-3f)
        assertEquals(2f, dbToLinear(6.0206f), 1e-3f)
    }

    @Test
    fun passthroughFilterHasZeroDbMagnitudeEverywhere() {
        val f = BiquadFilter.passthrough()
        for (hz in listOf(50f, 200f, 1000f, 5000f, 15000f)) {
            assertEquals("passthrough at $hz", 0f, f.evaluateMagnitudeDb(hz, sampleRate), 1e-3f)
        }
    }

    @Test
    fun peakEqBoostMatchesGainAtCenterFrequency() {
        // A peak EQ at its center frequency should show ~the configured gain.
        val gain = 6f
        val center = 1000f
        val f = BiquadFilter.peakEQ(center, gain, 1f, sampleRate)
        assertEquals(gain, f.evaluateMagnitudeDb(center, sampleRate), 0.2f)
    }

    @Test
    fun lowShelfApproachesGainAtDcAndUnityAtNyquist() {
        val gain = 6f
        val f = BiquadFilter.lowShelf(200f, gain, sampleRate)
        // Near DC the low shelf approaches its full gain.
        assertEquals(gain, f.evaluateMagnitudeDb(10f, sampleRate), 0.5f)
        // High above the corner it flattens toward 0 dB.
        assertTrue(
            "high-frequency magnitude should be near 0 dB",
            abs(f.evaluateMagnitudeDb(20000f, sampleRate)) < 1f
        )
    }

    @Test
    fun limiterNeverExceedsCeiling() {
        val ceilingDb = -0.3f
        val limiter = Limiter(ceilingDb = ceilingDb, releaseMs = 50f, sampleRate = sampleRate)
        val ceilingLinear = dbToLinear(ceilingDb)
        // Drive with a loud signal well above the ceiling for long enough to flush
        // the lookahead delay line.
        var maxOut = 0f
        for (i in 0 until sampleRate) {
            val input = if (i % 2 == 0) 0.95f else -0.95f
            val out = limiter.process(input)
            maxOut = maxOf(maxOut, abs(out))
        }
        // Brick-wall limiter output must not exceed the ceiling (small epsilon for float).
        assertTrue(
            "limiter output $maxOut exceeded ceiling $ceilingLinear",
            maxOut <= ceilingLinear + 1e-3f
        )
    }

    @Test
    fun limiterPassesSignalBelowCeilingUnchanged() {
        val limiter = Limiter(ceilingDb = -0.3f, releaseMs = 50f, sampleRate = sampleRate)
        val quiet = 0.1f
        // After the lookahead delay fills, a quiet steady signal passes through ~unity.
        var last = 0f
        for (i in 0 until sampleRate) last = limiter.process(quiet)
        assertEquals(quiet, last, 1e-3f)
        assertEquals(0f, limiter.currentGainReductionDb, 1e-2f)
    }

    @Test
    fun compressorGainReductionIsMonotonicWithLevel() {
        // Louder input above threshold => more (or equal) gain reduction.
        fun steadyGr(level: Float): Float {
            val c = Compressor(
                thresholdDb = -20f, ratio = 4f, attackMs = 1f, releaseMs = 1f,
                makeupGainDb = 0f, sampleRate = sampleRate
            )
            // Let the envelope settle on a steady tone.
            repeat(sampleRate) { c.process(level) }
            return c.currentGainReductionDb
        }
        val grLow = steadyGr(0.2f)
        val grMid = steadyGr(0.5f)
        val grHigh = steadyGr(0.9f)
        assertTrue("GR should be non-negative", grLow >= 0f)
        assertTrue("GR should increase with level (low<=mid)", grMid >= grLow)
        assertTrue("GR should increase with level (mid<=high)", grHigh >= grMid)
    }

    @Test
    fun compressorBelowThresholdAppliesNoReduction() {
        val c = Compressor(
            thresholdDb = -12f, ratio = 4f, attackMs = 10f, releaseMs = 100f,
            makeupGainDb = 0f, sampleRate = sampleRate
        )
        // Very quiet signal, far below threshold => no gain reduction.
        repeat(sampleRate) { c.process(0.001f) }
        assertEquals(0f, c.currentGainReductionDb, 1e-3f)
    }
}
