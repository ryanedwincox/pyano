// Concern: measures gated stereo loudness with K weighting | Non-concern: playback or gain changes | IO: (PCM frames) -> LUFS
package com.pyano.audio

import kotlin.math.*

internal class LoudnessMeter(private val sampleRate: Int) {
    private class Filter(val b: DoubleArray, val a: DoubleArray) {
        var z1 = 0.0; var z2 = 0.0
        fun process(x: Double): Double {
            val y = b[0] * x + z1
            z1 = b[1] * x - a[0] * y + z2
            z2 = b[2] * x - a[1] * y
            return y
        }
    }
    private fun shelf(): Filter {
        val k = tan(PI * 1681.974450955533 / sampleRate)
        val q = .7071752369554196
        val vh = 10.0.pow(3.999843853973347 / 20)
        val vb = vh.pow(.4996667741545416)
        val d = 1 + k / q + k * k
        return Filter(doubleArrayOf((vh + vb * k / q + k * k) / d, 2 * (k * k - vh) / d, (vh - vb * k / q + k * k) / d),
            doubleArrayOf(2 * (k * k - 1) / d, (1 - k / q + k * k) / d))
    }
    private fun highPass(): Filter {
        val k = tan(PI * 38.13547087602444 / sampleRate)
        val q = .5003270373238773
        val d = 1 + k / q + k * k
        return Filter(doubleArrayOf(1.0, -2.0, 1.0), doubleArrayOf(2 * (k * k - 1) / d, (1 - k / q + k * k) / d))
    }
    private val shelves = Array(2) { shelf() }
    private val highPasses = Array(2) { highPass() }
    private val hop = (sampleRate / 10).coerceAtLeast(1)
    private val ring = DoubleArray(hop * 4)
    private val blocks = ArrayList<Double>()
    private var frames = 0L
    private var sum = 0.0
    val momentary: Float get() = energyLufs(sum / minOf(frames, ring.size.toLong()).coerceAtLeast(1))
    fun add(left: Float, right: Float) {
        val l = highPasses[0].process(shelves[0].process(left.toDouble()))
        val r = highPasses[1].process(shelves[1].process(right.toDouble()))
        val energy = l * l + r * r
        val index = (frames % ring.size).toInt()
        sum += energy - ring[index]
        ring[index] = energy
        frames++
        if (frames >= ring.size && (frames - ring.size) % hop == 0L) blocks.add((sum / ring.size).coerceAtLeast(0.0))
    }
    fun integrated(): Float? {
        val energies = if (blocks.isEmpty() && frames > 0) listOf(sum / frames) else blocks
        val absolute = energies.filter { energyLufs(it) >= -70f }
        if (absolute.isEmpty()) return null
        val relativeGate = energyLufs(absolute.average()) - 10f
        val gated = absolute.filter { energyLufs(it) >= relativeGate }
        return energyLufs(gated.average()).takeIf { it.isFinite() }
    }
    private fun energyLufs(energy: Double): Float = if (energy > 1e-15) (-.691 + 10 * log10(energy)).toFloat() else -120f
}
