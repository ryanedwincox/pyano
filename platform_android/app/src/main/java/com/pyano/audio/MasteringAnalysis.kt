// Concern: scans whole recordings for consistent loudness and reduction graphs | Non-concern: UI | IO: (WAV, settings) -> verified render plan
package com.pyano.audio

import kotlinx.coroutines.*
import java.io.File
import kotlin.math.*

data class MasteringPoint(val fraction: Float, val lufs: Float, val compressorDb: Float = 0f, val limiterDb: Float = 0f)
data class MasteringMeasurement(val lufs: Float?, val peakDb: Float, val points: List<MasteringPoint>)
data class MasteringPlan(
    val settings: MasteringSettings, val sampleRate: Int, val inputGain: Float, val outputGain: Float,
    val before: MasteringMeasurement, val after: MasteringMeasurement, val sourceIdentity: String, val durationMs: Long,
) {
    fun chain() = settings.chain(sampleRate, inputGain, outputGain)
    val warning: String? get() = when {
        before.lufs == null -> "No measurable loudness. Automatic gain is left unchanged."
        settings.normalize && abs((after.lufs ?: -120f) - settings.targetLufs) > 1f ->
            "This recording cannot reach the target within the gain limit. Try Even compression or a quieter target."
        after.points.any { it.limiterDb > 6f } -> "Some peaks need substantial limiting. Preview the highlighted passages."
        else -> null
    }
}

suspend fun analyzeMastering(file: File, settings: MasteringSettings, onProgress: (Float) -> Unit = {}): MasteringPlan = withContext(Dispatchers.Default) {
    val identity = recordingFileIdentity(file)
    val header = masteringHeader(file)
    settings.chain(header.sampleRate)
    val before = measureMastering(file, null) { onProgress(it * .2f) }
    val input = if (settings.normalize && before.lufs != null) (settings.targetLufs - before.lufs).coerceIn(-24f, 24f) else 0f
    var output = 0f
    var after = measureMastering(file, settings.chain(header.sampleRate, input)) { onProgress(.2f + it * .2f) }
    if (settings.normalize && before.lufs != null) {
        repeat(3) { iteration ->
            val delta = after.lufs?.let { settings.targetLufs - it } ?: 0f
            if (abs(delta) > .15f) {
                output = (output + delta).coerceIn(-24f, 24f)
                after = measureMastering(file, settings.chain(header.sampleRate, input, output)) {
                    onProgress(.4f + iteration * .2f + it * .2f)
                }
            }
        }
    }
    check(recordingFileIdentity(file) == identity) { "Recording changed during analysis. Reopen it and try again." }
    onProgress(1f)
    MasteringPlan(settings, header.sampleRate, input, output, before, after, identity, header.dataSize / 4 * 1000 / header.sampleRate)
}

internal suspend fun measureMastering(file: File, chain: MasteringChain?, onProgress: (Float) -> Unit = {}): MasteringMeasurement {
    val header = masteringHeader(file)
    val meter = LoudnessMeter(header.sampleRate)
    val frames = header.dataSize / 4
    val binFrames = maxOf(header.sampleRate / 4L, (frames + 999) / 1000)
    val points = ArrayList<MasteringPoint>()
    var count = 0L; var peak = 0f; var compressor = 0f; var limiter = 0f
    streamMastering(file, chain, onProgress) { left, right ->
        meter.add(left, right)
        peak = maxOf(peak, abs(left), abs(right))
        compressor = maxOf(compressor, chain?.compressorGrDb ?: 0f)
        limiter = maxOf(limiter, chain?.limiterGrDb ?: 0f)
        count++
        if (count % binFrames == 0L || count == frames) {
            points.add(MasteringPoint(count.toFloat() / frames, meter.momentary, compressor, limiter))
            compressor = 0f; limiter = 0f
        }
    }
    return MasteringMeasurement(meter.integrated(), linearToDb(peak), points)
}
