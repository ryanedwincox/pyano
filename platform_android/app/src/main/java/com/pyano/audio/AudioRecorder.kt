// AudioRecorder: PCM capture from Oboe ring buffer, drain to temp file, WAV export.
// NOT concerned with: MP3 encoding, microphone input, or MIDI recording.
package com.pyano.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingInfo(
    val name: String,
    val path: String,
    val durationMs: Long,
    val dateMs: Long,
    val sizeBytes: Long
)

class AudioRecorder(
    private val engine: FluidSynthEngine,
    private val context: Context
) {
    companion object {
        private const val TAG = "Pyano"
        private const val CHANNELS = 2
        private const val DRAIN_CHUNK_FLOATS = 8192 // floats per drain read
        private const val BYTES_PER_SAMPLE_16BIT = 2
        private const val DEFAULT_SAMPLE_RATE = 48000
    }

    @Volatile
    var isRecording: Boolean = false
        private set

    // Total float samples written to temp file (stereo interleaved)
    @Volatile
    private var totalFloatsWritten: Long = 0

    private var drainJob: Job? = null
    private var tempPcmFile: File? = null
    private var recordingSampleRate: Int = DEFAULT_SAMPLE_RATE

    /** Duration of current recording in milliseconds. */
    val recordingDurationMs: Long
        get() {
            if (!isRecording && totalFloatsWritten == 0L) return 0
            // totalFloatsWritten = stereo interleaved, so frames = floats / channels
            val frames = totalFloatsWritten / CHANNELS
            return if (recordingSampleRate > 0) frames * 1000 / recordingSampleRate else 0
        }

    fun startRecording(scope: CoroutineScope) {
        if (isRecording) return

        recordingSampleRate = engine.getRecordingSampleRate()
        totalFloatsWritten = 0

        // Create temp file in app cache dir
        val tempFile = File(context.cacheDir, "pyano_recording_temp.pcm")
        tempFile.delete() // Clear any leftover
        tempPcmFile = tempFile

        engine.startRecording()
        isRecording = true

        // Launch drain coroutine: reads ring buffer -> writes raw PCM floats to temp file
        drainJob = scope.launch(Dispatchers.IO) {
            BufferedOutputStream(FileOutputStream(tempFile, true), 65536).use { outputStream ->
                val buffer = FloatArray(DRAIN_CHUNK_FLOATS)
                val byteBuffer = ByteBuffer.allocate(DRAIN_CHUNK_FLOATS * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)

                while (isActive && isRecording) {
                    val written = drainChunk(buffer, byteBuffer, outputStream)
                    if (written == 0) {
                        delay(5) // No data available, back off briefly
                    }
                }
                outputStream.flush()
            }
        }

        Log.i(TAG, "Recording started, sampleRate=$recordingSampleRate")
    }

    suspend fun stopRecording(): String? {
        if (!isRecording) return null
        isRecording = false

        engine.stopRecording()

        // Wait for drain coroutine to finish flushing
        drainJob?.join()
        drainJob = null

        // Final drain: read any remaining data in the ring buffer
        val tempFile = tempPcmFile ?: return null
        withContext(Dispatchers.IO) {
            BufferedOutputStream(FileOutputStream(tempFile, true), 65536).use { outputStream ->
                val buffer = FloatArray(DRAIN_CHUNK_FLOATS)
                val byteBuffer = ByteBuffer.allocate(DRAIN_CHUNK_FLOATS * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                while (drainChunk(buffer, byteBuffer, outputStream) > 0) { /* drain remaining */ }
                outputStream.flush()
            }
        }

        if (totalFloatsWritten == 0L) {
            tempFile.delete()
            Log.w(TAG, "Recording produced no audio data")
            return null
        }

        // Encode to WAV
        val wavPath = encodeToWav(tempFile)
        tempFile.delete()
        totalFloatsWritten = 0

        Log.i(TAG, "Recording saved: $wavPath")
        return wavPath
    }

    /** Read one chunk from ring buffer and write raw PCM floats to stream. Returns floats written. */
    private fun drainChunk(
        buffer: FloatArray,
        byteBuffer: ByteBuffer,
        outputStream: BufferedOutputStream
    ): Int {
        val read = engine.readRecordingBuffer(buffer, DRAIN_CHUNK_FLOATS)
        if (read <= 0) return 0
        byteBuffer.clear()
        for (i in 0 until read) {
            byteBuffer.putFloat(buffer[i])
        }
        outputStream.write(byteBuffer.array(), 0, read * 4)
        totalFloatsWritten += read
        return read
    }

    private suspend fun encodeToWav(pcmFile: File): String? = withContext(Dispatchers.IO) {
        val pyanoDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Pyano"
        )
        pyanoDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val wavFile = File(pyanoDir, "Pyano_$timestamp.wav")

        try {
            FileInputStream(pcmFile).use { pcmInput ->
                RandomAccessFile(wavFile, "rw").use { wavOutput ->
                    val floatBuffer = ByteBuffer.allocate(DRAIN_CHUNK_FLOATS * 4)
                        .order(ByteOrder.LITTLE_ENDIAN)

                    // Write placeholder WAV header (44 bytes), fill in sizes after
                    wavOutput.write(ByteArray(44))

                    var totalDataBytes = 0L
                    val readBuf = ByteArray(DRAIN_CHUNK_FLOATS * 4)
                    val shortBuffer = ByteBuffer.allocate(DRAIN_CHUNK_FLOATS * BYTES_PER_SAMPLE_16BIT)
                        .order(ByteOrder.LITTLE_ENDIAN)

                    while (true) {
                        val bytesRead = pcmInput.read(readBuf)
                        if (bytesRead <= 0) break

                        floatBuffer.clear()
                        floatBuffer.put(readBuf, 0, bytesRead)
                        floatBuffer.flip()

                        val floatCount = bytesRead / 4
                        shortBuffer.clear()

                        for (i in 0 until floatCount) {
                            val sample = floatBuffer.getFloat()
                            // Clamp to [-1, 1] then scale to 16-bit
                            val clamped = sample.coerceIn(-1.0f, 1.0f)
                            val intSample = (clamped * 32767.0f).toInt().toShort()
                            shortBuffer.putShort(intSample)
                        }

                        shortBuffer.flip()
                        val shortBytes = ByteArray(shortBuffer.remaining())
                        shortBuffer.get(shortBytes)
                        wavOutput.write(shortBytes)
                        totalDataBytes += shortBytes.size
                    }

                    // Write WAV header with final sizes
                    writeWavHeader(wavOutput, recordingSampleRate, CHANNELS, totalDataBytes)

                    Log.i(TAG, "WAV encoded: ${wavFile.absolutePath}, size=${wavFile.length()}")
                }
            }
            wavFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "WAV encoding failed", e)
            wavFile.delete()
            null
        }
    }

    private fun writeWavHeader(
        file: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        dataSize: Long
    ) {
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE_16BIT
        val blockAlign = channels * BYTES_PER_SAMPLE_16BIT

        file.seek(0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataSize).toInt()) // ChunkSize
        header.put("WAVE".toByteArray())

        // fmt sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16)                  // Subchunk1Size (PCM)
        header.putShort(1)                 // AudioFormat (PCM = 1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)                // BitsPerSample

        // data sub-chunk
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())

        file.write(header.array())
    }

    /** Parse WAV header to extract sample rate for accurate duration calculation. */
    private fun readWavSampleRate(file: File): Int {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(24) // Sample rate is at byte offset 24 in WAV header
                // WAV is little-endian; read 4 bytes manually
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) DEFAULT_SAMPLE_RATE
                else b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            }
        } catch (_: Exception) {
            DEFAULT_SAMPLE_RATE
        }
    }

    fun getSavedRecordings(): List<RecordingInfo> {
        val pyanoDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Pyano"
        )
        if (!pyanoDir.exists()) return emptyList()

        return pyanoDir.listFiles()
            ?.filter { it.extension.equals("wav", ignoreCase = true) }
            ?.map { file ->
                val sizeBytes = file.length()
                val sampleRate = readWavSampleRate(file)
                val dataBytes = (sizeBytes - 44).coerceAtLeast(0)
                val durationMs = dataBytes * 1000 / (sampleRate * CHANNELS * BYTES_PER_SAMPLE_16BIT)
                RecordingInfo(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    durationMs = durationMs,
                    dateMs = file.lastModified(),
                    sizeBytes = sizeBytes
                )
            }
            ?.sortedByDescending { it.dateMs }
            ?: emptyList()
    }

    /**
     * Cancel recording without producing a WAV file. Stops the drain coroutine and cleans up
     * the temp file. Safe to call from onCleared (synchronous, non-suspend).
     */
    fun cancelRecording() {
        if (!isRecording) return
        isRecording = false
        drainJob?.cancel()
        drainJob = null
        tempPcmFile?.delete()
        tempPcmFile = null
        totalFloatsWritten = 0
        Log.i(TAG, "Recording cancelled")
    }

    fun deleteRecording(path: String): Boolean {
        val file = File(path)
        if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted recording: $path, success=$deleted")
            return deleted
        }
        return false
    }
}
