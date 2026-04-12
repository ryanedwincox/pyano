// MasteringEngine: DSP chain + AudioTrack playback for mastering suite.
// NOT concerned with: UI state, MIDI, recording, or microphone input.
package com.pyano.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// BiquadFilter — Robert Bristow-Johnson audio EQ cookbook, direct form II transposed
// ---------------------------------------------------------------------------

class BiquadFilter private constructor(
    private var b0: Float,
    private var b1: Float,
    private var b2: Float,
    private var a1: Float,
    private var a2: Float
) {
    // Direct form II transposed state
    private var z1 = 0f
    private var z2 = 0f

    fun process(sample: Float): Float {
        val out = b0 * sample + z1
        z1 = b1 * sample - a1 * out + z2
        z2 = b2 * sample - a2 * out
        return out
    }

    // Evaluate |H(e^jω)| in dB at the given frequency
    fun evaluateMagnitudeDb(freqHz: Float, sampleRate: Int): Float {
        val w = 2.0 * PI * freqHz / sampleRate
        val cosW = cos(w)
        val cos2W = cos(2.0 * w)
        val sinW = sin(w)
        val sin2W = sin(2.0 * w)

        // H(z) = (b0 + b1*z^-1 + b2*z^-2) / (1 + a1*z^-1 + a2*z^-2)
        // z = e^jω, so z^-1 = e^-jω
        val numReal = b0 + b1 * cosW + b2 * cos2W
        val numImag = -(b1 * sinW + b2 * sin2W)
        val denReal = 1.0 + a1 * cosW + a2 * cos2W
        val denImag = -(a1 * sinW + a2 * sin2W)

        val numMagSq = numReal * numReal + numImag * numImag
        val denMagSq = denReal * denReal + denImag * denImag

        return if (denMagSq < 1e-30) {
            0f
        } else {
            (10.0 * log10(numMagSq / denMagSq)).toFloat()
        }
    }

    fun updateCoefficients(other: BiquadFilter) {
        b0 = other.b0; b1 = other.b1; b2 = other.b2
        a1 = other.a1; a2 = other.a2
        // Don't reset state — avoids clicks on parameter changes
    }

    companion object {
        fun lowShelf(freq: Float, gainDb: Float, sampleRate: Int): BiquadFilter {
            val a = 10f.pow(gainDb / 40f) // sqrt of linear gain
            val w0 = (2.0 * PI * freq / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2f * sqrt(2f) // S=1 (shelf slope)
            val twoSqrtAAlpha = 2f * sqrt(a) * alpha

            val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
            val b0 = a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha) / a0
            val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW0) / a0
            val b2 = a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha) / a0
            val a1r = -2f * ((a - 1f) + (a + 1f) * cosW0) / a0
            val a2r = ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha) / a0

            return BiquadFilter(b0, b1, b2, a1r, a2r)
        }

        fun peakEQ(freq: Float, gainDb: Float, q: Float, sampleRate: Int): BiquadFilter {
            val a = 10f.pow(gainDb / 40f)
            val w0 = (2.0 * PI * freq / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2f * q)

            val a0 = 1f + alpha / a
            val b0 = (1f + alpha * a) / a0
            val b1 = (-2f * cosW0) / a0
            val b2 = (1f - alpha * a) / a0
            val a1r = (-2f * cosW0) / a0
            val a2r = (1f - alpha / a) / a0

            return BiquadFilter(b0, b1, b2, a1r, a2r)
        }

        fun highShelf(freq: Float, gainDb: Float, sampleRate: Int): BiquadFilter {
            val a = 10f.pow(gainDb / 40f)
            val w0 = (2.0 * PI * freq / sampleRate).toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / 2f * sqrt(2f)
            val twoSqrtAAlpha = 2f * sqrt(a) * alpha

            val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
            val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha) / a0
            val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0) / a0
            val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha) / a0
            val a1r = 2f * ((a - 1f) - (a + 1f) * cosW0) / a0
            val a2r = ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha) / a0

            return BiquadFilter(b0, b1, b2, a1r, a2r)
        }

        fun passthrough(): BiquadFilter = BiquadFilter(1f, 0f, 0f, 0f, 0f)
    }
}

// ---------------------------------------------------------------------------
// Compressor — Envelope follower with configurable attack/release
// ---------------------------------------------------------------------------

class Compressor(
    thresholdDb: Float = -12f,
    ratio: Float = 4f,
    attackMs: Float = 10f,
    releaseMs: Float = 100f,
    makeupGainDb: Float = 0f,
    private val sampleRate: Int = 48000
) {
    var thresholdDb: Float = thresholdDb; internal set
    var ratio: Float = ratio; internal set
    var attackMs: Float = attackMs; internal set
    var releaseMs: Float = releaseMs; internal set
    var makeupGainDb: Float = makeupGainDb; internal set

    private var attackCoeff = 0f
    private var releaseCoeff = 0f
    private var envelopeDb = -96f
    var currentGainReductionDb: Float = 0f
        private set

    init { recomputeCoeffs() }

    internal fun recomputeCoeffs() {
        attackCoeff = exp(-1f / (attackMs * sampleRate / 1000f))
        releaseCoeff = exp(-1f / (releaseMs * sampleRate / 1000f))
    }

    fun process(sample: Float): Float {
        val inputDb = linearToDb(abs(sample))

        // Envelope follower — smoothed peak detection
        envelopeDb = if (inputDb > envelopeDb) {
            attackCoeff * envelopeDb + (1f - attackCoeff) * inputDb
        } else {
            releaseCoeff * envelopeDb + (1f - releaseCoeff) * inputDb
        }

        // Gain computation
        val overDb = envelopeDb - thresholdDb
        currentGainReductionDb = if (overDb > 0f) {
            overDb * (1f - 1f / ratio)
        } else {
            0f
        }

        val gainDb = -currentGainReductionDb + makeupGainDb
        return sample * dbToLinear(gainDb)
    }
}

// ---------------------------------------------------------------------------
// Limiter — Brick-wall lookahead limiter with 5ms delay line
// ---------------------------------------------------------------------------

class Limiter(
    ceilingDb: Float = -0.3f,
    releaseMs: Float = 50f,
    private val sampleRate: Int = 48000
) {
    var ceilingDb: Float = ceilingDb; internal set
    var releaseMs: Float = releaseMs; internal set

    private var ceiling = dbToLinear(ceilingDb)
    private var releaseCoeff = exp(-1f / (releaseMs * sampleRate / 1000f))

    internal fun recomputeCoeffs() {
        ceiling = dbToLinear(ceilingDb)
        releaseCoeff = exp(-1f / (releaseMs * sampleRate / 1000f))
    }
    private val lookaheadSamples = (0.005f * sampleRate).toInt() // 5ms
    private val delayLine = FloatArray(lookaheadSamples)
    private var writePos = 0
    private var gainReduction = 1f // linear multiplier, <=1

    var currentGainReductionDb: Float = 0f
        private set
    var isActive: Boolean = false
        private set

    fun process(sample: Float): Float {
        // Determine needed gain reduction from incoming sample
        val absSample = abs(sample)
        val neededGain = if (absSample > ceiling) ceiling / absSample else 1f

        // Smooth gain: instant attack (brick-wall), smooth release
        gainReduction = if (neededGain < gainReduction) {
            neededGain // instant attack
        } else {
            releaseCoeff * gainReduction + (1f - releaseCoeff) * neededGain
        }

        // Read from delay line (oldest sample), apply gain reduction
        val delayedSample = delayLine[writePos]
        val output = delayedSample * gainReduction

        // Write new sample into delay line
        delayLine[writePos] = sample
        writePos = (writePos + 1) % lookaheadSamples

        currentGainReductionDb = abs(linearToDb(gainReduction)).coerceIn(0f, 20f)
        isActive = currentGainReductionDb > 0.5f

        return output
    }
}

// ---------------------------------------------------------------------------
// MasteringChain — Composes 3-band EQ + compressor + limiter, stereo
// ---------------------------------------------------------------------------

class MasteringChain(private val sampleRate: Int = 48000) {

    // 3 EQ bands × 2 channels (L=0, R=1)
    private val eqBands = Array(3) { band ->
        Array(2) {
            when (band) {
                0 -> BiquadFilter.lowShelf(200f, 0f, sampleRate)
                1 -> BiquadFilter.peakEQ(1000f, 0f, 1f, sampleRate)
                2 -> BiquadFilter.highShelf(4000f, 0f, sampleRate)
                else -> error("unreachable")
            }
        }
    }

    // Default EQ frequencies for recalculation
    private val eqFreqs = floatArrayOf(200f, 1000f, 4000f)
    private val eqGains = floatArrayOf(0f, 0f, 0f)

    // Compressor × 2 channels
    private val compressors = Array(2) { Compressor(sampleRate = sampleRate) }

    // Limiter × 2 channels
    private val limiters = Array(2) { Limiter(sampleRate = sampleRate) }

    val compressorGrDb: Float
        get() = max(compressors[0].currentGainReductionDb, compressors[1].currentGainReductionDb)

    val limiterGrDb: Float
        get() = max(limiters[0].currentGainReductionDb, limiters[1].currentGainReductionDb)

    val limiterActive: Boolean
        get() = limiters[0].isActive || limiters[1].isActive

    @Synchronized
    fun processFrame(left: Float, right: Float): Pair<Float, Float> {
        var l = left
        var r = right

        // EQ: 3 bands in series
        for (band in 0..2) {
            l = eqBands[band][0].process(l)
            r = eqBands[band][1].process(r)
        }

        // Compressor
        l = compressors[0].process(l)
        r = compressors[1].process(r)

        // Limiter
        l = limiters[0].process(l)
        r = limiters[1].process(r)

        return Pair(l, r)
    }

    @Synchronized
    fun updateEq(band: Int, freq: Float, gainDb: Float) {
        require(band in 0..2) { "EQ band must be 0, 1, or 2" }
        eqFreqs[band] = freq
        eqGains[band] = gainDb
        val newFilter = when (band) {
            0 -> BiquadFilter.lowShelf(freq, gainDb, sampleRate)
            1 -> BiquadFilter.peakEQ(freq, gainDb, 1f, sampleRate)
            else -> BiquadFilter.highShelf(freq, gainDb, sampleRate)
        }
        eqBands[band][0].updateCoefficients(newFilter)
        eqBands[band][1].updateCoefficients(newFilter)
    }

    @Synchronized
    fun updateCompressor(
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupGainDb: Float
    ) {
        for (c in compressors) {
            c.thresholdDb = thresholdDb
            c.ratio = ratio
            c.attackMs = attackMs
            c.releaseMs = releaseMs
            c.makeupGainDb = makeupGainDb
            c.recomputeCoeffs()
        }
    }

    @Synchronized
    fun updateLimiter(ceilingDb: Float, releaseMs: Float) {
        for (l in limiters) {
            l.ceilingDb = ceilingDb
            l.releaseMs = releaseMs
            l.recomputeCoeffs()
        }
    }

    // Returns L channel filter instance for the given band (for magnitude evaluation)
    fun getEqBand(band: Int): BiquadFilter {
        require(band in 0..2) { "EQ band must be 0, 1, or 2" }
        return eqBands[band][0]
    }

    /** Create an independent copy with matching parameters — safe for concurrent export. */
    fun copyWithCurrentParams(): MasteringChain {
        val copy = MasteringChain(sampleRate)
        for (band in 0..2) copy.updateEq(band, eqFreqs[band], eqGains[band])
        copy.updateCompressor(
            compressors[0].thresholdDb, compressors[0].ratio,
            compressors[0].attackMs, compressors[0].releaseMs, compressors[0].makeupGainDb
        )
        copy.updateLimiter(limiters[0].ceilingDb, limiters[0].releaseMs)
        return copy
    }
}

// ---------------------------------------------------------------------------
// MasteringPlayer — AudioTrack streaming playback through MasteringChain
// ---------------------------------------------------------------------------

class MasteringPlayer {
    val chain = MasteringChain()

    private val _leftPeakDb = MutableStateFlow(-96f)
    val leftPeakDb: StateFlow<Float> = _leftPeakDb.asStateFlow()

    private val _rightPeakDb = MutableStateFlow(-96f)
    val rightPeakDb: StateFlow<Float> = _rightPeakDb.asStateFlow()

    private val _leftClip = MutableStateFlow(false)
    val leftClip: StateFlow<Boolean> = _leftClip.asStateFlow()

    private val _rightClip = MutableStateFlow(false)
    val rightClip: StateFlow<Boolean> = _rightClip.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0f)
    val playbackPosition: StateFlow<Float> = _playbackPosition.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _compressorGrDb = MutableStateFlow(0f)
    val compressorGrDb: StateFlow<Float> = _compressorGrDb.asStateFlow()

    private val _limiterGrDb = MutableStateFlow(0f)
    val limiterGrDb: StateFlow<Float> = _limiterGrDb.asStateFlow()

    private val _limiterActive = MutableStateFlow(false)
    val limiterActive: StateFlow<Boolean> = _limiterActive.asStateFlow()

    private var wavHeader: WavHeader? = null
    private var wavFile: File? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    // Single-writer (seekTo from UI thread), single-reader (playbackLoop on Default dispatcher)
    @Volatile
    private var seekFraction: Float = -1f // -1 means no pending seek

    fun load(wavPath: String) {
        stop()
        val file = File(wavPath)
        require(file.exists()) { "WAV file not found: $wavPath" }
        val header = readWavHeader(file)
        require(header.channels == 2) { "Expected stereo WAV, got ${header.channels} channels" }
        require(header.bitsPerSample == 16) { "Expected 16-bit WAV, got ${header.bitsPerSample}-bit" }
        wavHeader = header
        wavFile = file
        _playbackPosition.value = 0f
    }

    fun play(scope: CoroutineScope) {
        val header = wavHeader ?: return
        val file = wavFile ?: return
        if (_isPlaying.value) return

        _isPlaying.value = true

        playbackJob = scope.launch(Dispatchers.Default) {
            val bufferSize = AudioTrack.getMinBufferSize(
                header.sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_FRAMES * header.channels * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(header.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            try {
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use { raf ->
                        playbackLoop(raf, track, header)
                    }
                }
            } finally {
                _isPlaying.value = false
                // AudioTrack cleanup handled by stop() — avoid double-stop race
            }
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        audioTrack = null
        _isPlaying.value = false
    }

    fun seekTo(fraction: Float) {
        seekFraction = fraction.coerceIn(0f, 1f)
    }

    fun resetClip() {
        _leftClip.value = false
        _rightClip.value = false
    }

    fun release() {
        stop()
        wavHeader = null
        wavFile = null
    }

    private fun playbackLoop(raf: RandomAccessFile, track: AudioTrack, header: WavHeader) {
        val bytesPerFrame = header.channels * (header.bitsPerSample / 8)
        val totalFrames = header.dataSize / bytesPerFrame
        val readBytes = ByteArray(CHUNK_FRAMES * bytesPerFrame)
        val readBuf = ByteBuffer.wrap(readBytes).order(ByteOrder.LITTLE_ENDIAN)
        val outputShorts = ShortArray(CHUNK_FRAMES * header.channels)

        var frameOffset = 0L
        raf.seek(header.dataOffset)

        while (_isPlaying.value) {
            // Handle pending seek
            val pending = seekFraction
            if (pending >= 0f) {
                seekFraction = -1f
                frameOffset = (pending * totalFrames).toLong()
                raf.seek(header.dataOffset + frameOffset * bytesPerFrame)
            }

            // Read chunk from WAV
            val framesToRead = CHUNK_FRAMES.toLong().coerceAtMost(totalFrames - frameOffset).toInt()
            if (framesToRead <= 0) break // EOF

            val bytesToRead = framesToRead * bytesPerFrame
            val bytesRead = raf.read(readBytes, 0, bytesToRead)
            if (bytesRead <= 0) break

            val framesRead = bytesRead / bytesPerFrame
            readBuf.clear().limit(bytesRead)

            // Process through chain: 16-bit → float → DSP → float → 16-bit
            var peakL = 0f
            var peakR = 0f

            for (i in 0 until framesRead) {
                val leftIn = readBuf.getShort() / 32768f
                val rightIn = readBuf.getShort() / 32768f

                val (leftOut, rightOut) = chain.processFrame(leftIn, rightIn)

                // Clip detection (latching)
                if (abs(leftOut) >= 1f) _leftClip.value = true
                if (abs(rightOut) >= 1f) _rightClip.value = true

                // Peak tracking
                peakL = max(peakL, abs(leftOut))
                peakR = max(peakR, abs(rightOut))

                // Convert back to 16-bit
                outputShorts[i * 2] = (leftOut.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                outputShorts[i * 2 + 1] = (rightOut.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }

            // Update meters
            _leftPeakDb.value = linearToDb(peakL)
            _rightPeakDb.value = linearToDb(peakR)
            _compressorGrDb.value = chain.compressorGrDb
            _limiterGrDb.value = chain.limiterGrDb
            _limiterActive.value = chain.limiterActive

            // Write to AudioTrack (blocking)
            track.write(outputShorts, 0, framesRead * header.channels)

            frameOffset += framesRead
            _playbackPosition.value = (frameOffset.toFloat() / totalFrames).coerceIn(0f, 1f)
        }
    }

    companion object {
        private const val CHUNK_FRAMES = 8192
    }
}

// ---------------------------------------------------------------------------
// Export — Process source WAV through chain, write new WAV
// ---------------------------------------------------------------------------

suspend fun exportMaster(
    wavPath: String,
    chain: MasteringChain,
    onProgress: (Float) -> Unit
): String = withContext(Dispatchers.IO) {
    val srcFile = File(wavPath)
    require(srcFile.exists()) { "Source WAV not found: $wavPath" }
    val header = readWavHeader(srcFile)
    require(header.channels == 2) { "Expected stereo WAV, got ${header.channels} channels" }
    require(header.bitsPerSample == 16) { "Expected 16-bit WAV, got ${header.bitsPerSample}-bit" }

    val outFile = generateOutputFile(srcFile)
    val bytesPerFrame = header.channels * (header.bitsPerSample / 8)
    val totalFrames = header.dataSize / bytesPerFrame
    val chunkFrames = 8192
    val readBytes = ByteArray(chunkFrames * bytesPerFrame)
    val readBuf = ByteBuffer.wrap(readBytes).order(ByteOrder.LITTLE_ENDIAN)

    RandomAccessFile(srcFile, "r").use { raf ->
        RandomAccessFile(outFile, "rw").use { wavOut ->
            // Write placeholder WAV header
            wavOut.write(ByteArray(44))

            raf.seek(header.dataOffset)
            var framesProcessed = 0L
            var totalDataBytes = 0L

            val outputBuf = ByteBuffer.allocate(chunkFrames * bytesPerFrame)
                .order(ByteOrder.LITTLE_ENDIAN)

            while (framesProcessed < totalFrames) {
                val framesToRead = chunkFrames.toLong()
                    .coerceAtMost(totalFrames - framesProcessed).toInt()
                val bytesToRead = framesToRead * bytesPerFrame
                val bytesRead = raf.read(readBytes, 0, bytesToRead)
                if (bytesRead <= 0) break

                val framesRead = bytesRead / bytesPerFrame
                readBuf.clear().limit(bytesRead)
                outputBuf.clear()

                for (i in 0 until framesRead) {
                    val leftIn = readBuf.getShort() / 32768f
                    val rightIn = readBuf.getShort() / 32768f

                    val (leftOut, rightOut) = chain.processFrame(leftIn, rightIn)

                    outputBuf.putShort((leftOut.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    outputBuf.putShort((rightOut.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }

                outputBuf.flip()
                val outBytes = ByteArray(outputBuf.remaining())
                outputBuf.get(outBytes)
                wavOut.write(outBytes)
                totalDataBytes += outBytes.size

                framesProcessed += framesRead
                onProgress((framesProcessed.toFloat() / totalFrames).coerceIn(0f, 1f))
            }

            // Write final WAV header
            writeExportWavHeader(wavOut, header.sampleRate, header.channels, totalDataBytes)
        }
    }

    outFile.absolutePath
}

private fun generateOutputFile(srcFile: File): File {
    val dir = srcFile.parentFile ?: srcFile
    val baseName = srcFile.nameWithoutExtension

    // Strip existing _final[N] suffix to avoid stacking
    val coreName = baseName.replace(Regex("_final\\d*$"), "")

    val candidate = File(dir, "${coreName}_final.wav")
    if (!candidate.exists()) return candidate

    for (n in 1..999) {
        val numbered = File(dir, "${coreName}_final$n.wav")
        if (!numbered.exists()) return numbered
    }
    throw java.io.IOException("Could not generate unique output filename after 999 attempts")
}

private fun writeExportWavHeader(
    file: RandomAccessFile,
    sampleRate: Int,
    channels: Int,
    dataSize: Long
) {
    require(dataSize <= Int.MAX_VALUE - 36L) { "WAV data exceeds 2GB limit: $dataSize bytes" }
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * (bitsPerSample / 8)
    val blockAlign = channels * (bitsPerSample / 8)

    file.seek(0)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

    header.put("RIFF".toByteArray())
    header.putInt((36 + dataSize).toInt())
    header.put("WAVE".toByteArray())

    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1) // PCM
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(bitsPerSample.toShort())

    header.put("data".toByteArray())
    header.putInt(dataSize.toInt())

    file.write(header.array())
}

// ---------------------------------------------------------------------------
// Utility — dB/linear conversions shared across DSP classes
// ---------------------------------------------------------------------------

internal fun linearToDb(linear: Float): Float {
    return if (linear <= 0f) -96f else (20f * log10(linear)).coerceAtLeast(-96f)
}

internal fun dbToLinear(db: Float): Float {
    return 10f.pow(db / 20f)
}
