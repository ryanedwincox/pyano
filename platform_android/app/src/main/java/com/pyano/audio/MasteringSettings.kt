// Concern: shares mastering parameters across presets and manual controls | Non-concern: playback | IO: (settings) -> independent DSP chain
package com.pyano.audio

data class MasteringEq(val frequency: Float, val gain: Float = 0f)
data class MasteringCompression(
    val threshold: Float = -20f, val ratio: Float = 1.5f, val attack: Float = 30f,
    val release: Float = 250f, val makeup: Float = 0f,
)
enum class CompressionPreset(val title: String, val description: String, val compression: MasteringCompression) {
    GENTLE("Gentle", "Preserve more of the piano's dynamics", MasteringCompression(-16f, 1.25f, 40f, 250f)),
    BALANCED("Balanced", "A little more control for consistent listening", MasteringCompression()),
    EVEN("Even", "More control over louder passages", MasteringCompression(-24f, 1.8f, 25f, 250f)),
}
data class MasteringSettings(
    val eq: List<MasteringEq> = listOf(MasteringEq(250f), MasteringEq(1200f), MasteringEq(8000f)),
    val compression: MasteringCompression = CompressionPreset.BALANCED.compression,
    val ceiling: Float = -1f,
    val limiterRelease: Float = 100f,
    val normalize: Boolean = true,
    val targetLufs: Float = -18f,
) {
    val preset get() = CompressionPreset.entries.find { it.compression == compression }
    fun chain(sampleRate: Int, inputGain: Float = 0f, outputGain: Float = 0f): MasteringChain {
        require(eq.size == 3 && targetLufs.isFinite() && targetLufs in -30f..-10f)
        require(ceiling.isFinite() && ceiling in -12f..0f && limiterRelease.isFinite() && limiterRelease in 1f..500f)
        require(compression.threshold.isFinite() && compression.threshold in -60f..0f)
        require(compression.ratio.isFinite() && compression.ratio in 1f..20f)
        require(compression.attack.isFinite() && compression.attack in .1f..100f)
        require(compression.release.isFinite() && compression.release in 10f..1000f)
        require(compression.makeup.isFinite() && compression.makeup in 0f..24f)
        return MasteringChain(sampleRate).also { chain ->
            eq.forEachIndexed { band, value ->
                require(value.frequency.isFinite() && value.frequency in 20f..20000f && value.gain.isFinite() && value.gain in -12f..12f)
                chain.updateEq(band, value.frequency.coerceAtMost(sampleRate * .45f), value.gain)
            }
            chain.updateCompressor(compression.threshold, compression.ratio, compression.attack, compression.release, compression.makeup)
            chain.updateLimiter(ceiling, limiterRelease)
            chain.updateNormalization(inputGain, outputGain)
        }
    }
}
