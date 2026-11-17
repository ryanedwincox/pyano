// Concern: encodes WAV audio for upload | Non-concern: network transport | IO: (PCM WAV) -> AAC M4A
package com.pyano.sync

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.pyano.audio.readWavHeader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile

object CompressedAudio {
    suspend fun encode(wav: File, destination: File) {
        val header = readWavHeader(wav)
        require(header.bitsPerSample == 16 && header.channels in 1..2 && header.sampleRate > 0) { "Expected 16-bit mono/stereo WAV" }
        require(header.dataSize > 0 && header.dataSize % (header.channels * 2) == 0L) { "Invalid PCM length" }
        require(header.dataOffset + header.dataSize <= wav.length()) { "Truncated WAV" }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var complete = false
        var saved = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, header.sampleRate, header.channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val output = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            var track = -1
            var inputDone = false
            var consumed = 0L
            var progressNs = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            RandomAccessFile(wav, "r").use { source ->
                source.seek(header.dataOffset)
                while (!complete) {
                    currentCoroutineContext().ensureActive()
                    check(System.nanoTime() - progressNs < 30_000_000_000L) { "Audio encoder stalled" }
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(index))
                            buffer.clear()
                            val frameBytes = header.channels * 2
                            val size = minOf(buffer.remaining().toLong(), header.dataSize - consumed).toInt() / frameBytes * frameBytes
                            check(size > 0 || consumed == header.dataSize) { "Encoder input buffer too small" }
                            val bytes = ByteArray(size)
                            source.readFully(bytes)
                            buffer.put(bytes)
                            val pts = consumed / frameBytes * 1_000_000L / header.sampleRate
                            inputDone = consumed + size == header.dataSize
                            codec.queueInputBuffer(index, 0, size, pts, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            consumed += size
                            progressNs = System.nanoTime()
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder changed format twice" }
                            track = output.addTrack(codec.outputFormat)
                            output.start()
                            muxerStarted = true
                            progressNs = System.nanoTime()
                        }
                        else -> if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    check(muxerStarted) { "Encoder output has no format" }
                                    val buffer = requireNotNull(codec.getOutputBuffer(index))
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    output.writeSampleData(track, buffer, info)
                                }
                                complete = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                progressNs = System.nanoTime()
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                    }
                }
            }
            check(muxerStarted) { "No encoded audio" }
            output.stop()
            muxerStarted = false
            saved = true
        } finally {
            try {
                if (codecStarted) runCatching { codec.stop() }
                codec.release()
            } finally {
                if (muxerStarted) runCatching { muxer?.stop() }
                muxer?.release()
                if (!saved) destination.delete()
            }
        }
    }
}
