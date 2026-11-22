// Concern: streams bounded PCM with latency compensation | Non-concern: UI or cloud | IO: (validated WAV, chain) -> aligned processed frames
package com.pyano.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun masteringHeader(file: File): WavHeader = readWavHeader(file).also {
    require(it.channels == 2 && it.bitsPerSample == 16) { "Mastering requires stereo 16-bit PCM WAV audio." }
    require(it.sampleRate in 8000..192000) { "Unsupported sample rate." }
    require(it.dataSize <= Int.MAX_VALUE - 36L) { "Mastering supports WAV files smaller than 2 GB." }
    require(it.dataSize > 0 && it.dataSize % 4 == 0L && it.dataOffset <= file.length() - it.dataSize) { "The WAV audio is empty or incomplete." }
}

internal suspend fun streamMastering(
    file: File, chain: MasteringChain?, onProgress: (Float) -> Unit = {}, consume: (Float, Float) -> Unit,
) {
    val header = masteringHeader(file)
    val frames = header.dataSize / 4
    val latency = chain?.latencyFrames ?: 0
    val bytes = ByteArray(8192 * 4)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var processed = 0L
    RandomAccessFile(file, "r").use { source ->
        source.seek(header.dataOffset)
        while (processed < frames + latency) {
            currentCoroutineContext().ensureActive()
            val count = minOf(8192L, frames + latency - processed).toInt()
            val sourceFrames = minOf(count.toLong(), (frames - processed).coerceAtLeast(0)).toInt()
            source.readFully(bytes, 0, sourceFrames * 4)
            buffer.clear()
            repeat(count) { index ->
                val left = if (index < sourceFrames) buffer.short / 32768f else 0f
                val right = if (index < sourceFrames) buffer.short / 32768f else 0f
                chain?.process(left, right)
                if (processed + index >= latency) consume(chain?.outputLeft ?: left, chain?.outputRight ?: right)
            }
            processed += count
            onProgress((processed.toFloat() / (frames + latency)).coerceAtMost(1f))
        }
    }
}
