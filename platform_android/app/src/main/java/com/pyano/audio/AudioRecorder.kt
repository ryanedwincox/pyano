// Concern: captures synth audio to WAV | Non-concern: MIDI recording | IO: (PCM ring buffer) -> WAV files
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
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WavHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataSize: Long,
    val dataOffset: Long
)

data class RecordingInfo(
    val name: String,
    val path: String,
    val durationMs: Long,
    val dateMs: Long,
    val sizeBytes: Long
)

fun savedRecordingInfo(path: String): RecordingInfo {
    val file = File(path)
    val header = readWavHeader(file)
    require(header.sampleRate in 8000..192000 && header.channels == 2 && header.bitsPerSample == 16) { "Invalid saved audio format" }
    val duration = header.dataSize * 1000 / (header.sampleRate.toLong() * header.channels * header.bitsPerSample / 8)
    return RecordingInfo(file.nameWithoutExtension, file.absolutePath, duration, file.lastModified(), file.length())
}

fun readWavHeader(file: File): WavHeader {
    RandomAccessFile(file, "r").use { raf ->
        require(raf.length() >= 44) { "File too small to be a valid WAV: ${file.name}" }

        val header = ByteArray(44)
        raf.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        val riff = ByteArray(4); buf.get(riff)
        require(String(riff) == "RIFF") { "Not a RIFF file: ${file.name}" }
        buf.getInt() // chunkSize
        val wave = ByteArray(4); buf.get(wave)
        require(String(wave) == "WAVE") { "Not a WAVE file: ${file.name}" }

        // fmt sub-chunk
        val fmt = ByteArray(4); buf.get(fmt)
        require(String(fmt) == "fmt ") { "Missing fmt chunk: ${file.name}" }
        val fmtSize = buf.getInt()
        val audioFormat = buf.getShort().toInt()
        require(audioFormat == 1) { "Not PCM format (audioFormat=$audioFormat): ${file.name}" }
        val channels = buf.getShort().toInt()
        val sampleRate = buf.getInt()
        buf.getInt() // byteRate
        buf.getShort() // blockAlign
        val bitsPerSample = buf.getShort().toInt()

        // Skip any extra fmt bytes beyond the standard 16
        if (fmtSize > 16) {
            raf.seek(12L + 8 + fmtSize) // 12=RIFF+size+WAVE, 8=fmt+fmtSize field
        } else {
            raf.seek(36) // Standard position after fmt chunk
        }

        // Find the data chunk — skip any non-data sub-chunks (e.g., LIST, fact)
        val chunkId = ByteArray(4)
        while (true) {
            if (raf.filePointer + 8 > raf.length()) {
                throw IllegalArgumentException("No data chunk found: ${file.name}")
            }
            raf.readFully(chunkId)
            val chunkSizeBuf = ByteArray(4)
            raf.readFully(chunkSizeBuf)
            val chunkSize = ByteBuffer.wrap(chunkSizeBuf).order(ByteOrder.LITTLE_ENDIAN)
                .getInt().toLong() and 0xFFFFFFFFL

            if (String(chunkId) == "data") {
                return WavHeader(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    dataSize = chunkSize,
                    dataOffset = raf.filePointer
                )
            }
            // Skip this chunk
            raf.seek(raf.filePointer + chunkSize)
        }
    }
}

fun recordingFileIdentity(file: File): String {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    val key = checkNotNull(attributes.fileKey()) { "Could not verify saved take ownership" }
    return "$key:${attributes.size()}:${attributes.lastModifiedTime().toMillis()}"
}

class TakePublicationIntent(
    private val prepare: (String, String, String?) -> Unit = { _, _, _ -> },
    private val onAbort: (String, String, String?) -> Unit = { _, _, _ -> },
) {
    operator fun invoke(path: String, wavIdentity: String, midiIdentity: String?) = prepare(path, wavIdentity, midiIdentity)
    fun abandon(path: String, wavIdentity: String, midiIdentity: String?) = onAbort(path, wavIdentity, midiIdentity)
}

class AudioRecorder(
    private val engine: FluidSynthEngine,
    private val context: Context,
    private val publicationFactory: (File, File) -> RecordingTakePublication = { journals, output -> RecordingTakePublication(journals, output) },
    private val recordingsDirectory: () -> File = {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Pyano")
    }
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
    private var pendingMidi: ByteArray? = null
    private var pendingWav: String? = null
    private var pendingName: String? = null
    private var pendingWavIdentity: String? = null
    private var pendingMidiIdentity: String? = null
    private var pendingPublicationJournal: File? = null
    private var pendingSaveIntent: TakePublicationIntent? = null
    private var publishedWav: PublishedTakeFile? = null
    private var publishedMidi: PublishedTakeFile? = null

    private data class PublishedTakeFile(val file: File, val identity: String) {
        fun stillOwned(): Boolean = runCatching {
            recordingFileProof(file) == identity
        }.getOrDefault(false)
    }

    private fun ownedFile(file: File) = PublishedTakeFile(file, recordingFileProof(file))
    private fun abandonTakeIntent() {
        val path = pendingWav ?: return
        val identity = pendingWavIdentity ?: return
        pendingSaveIntent?.abandon(path, identity, pendingMidiIdentity)
        pendingSaveIntent = null
    }

    val pendingTakeNameLocked: Boolean
        get() = synchronized(RecordingBundleFiles) {
            publishedWav?.stillOwned() == true || publishedMidi?.stillOwned() == true ||
                pendingWav?.let { path ->
                    pendingWavIdentity?.let { PublishedTakeFile(File(path), it).stillOwned() } == true ||
                        path in publications.reservedTargets()
                } == true || publications.automaticUploadBlocked()
        }

    private val publications by lazy { publicationFactory(File(context.filesDir, "take-publications"), recordingsDirectory()) }
    var publicationRecoveryWarning: String? = null
        private set
    var publicationRecoveryKey: String? = null
        private set
    var automaticUploadBlocked: Boolean = false
        private set

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
        if (isRecording || tempPcmFile != null) return

        recordingSampleRate = engine.getRecordingSampleRate()
        totalFloatsWritten = 0

        // Create temp file in app cache dir
        val tempFile = File(context.cacheDir, "pyano_recording_temp.pcm")
        tempFile.delete() // Clear any leftover
        tempPcmFile = tempFile

        engine.startRecording()
        engine.midiCapture.start()
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

    val hasPendingTake get() = tempPcmFile != null

    suspend fun finishTake(): Boolean {
        if (tempPcmFile == null) return false
        if (isRecording) {
            isRecording = false
            engine.stopRecording()
            pendingMidi = engine.midiCapture.finish()
        }

        // Wait for drain coroutine to finish flushing
        drainJob?.join()
        drainJob = null

        // Final drain: read any remaining data in the ring buffer
        val tempFile = tempPcmFile ?: return false
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
            pendingMidi = null
            tempPcmFile = null
            return false
        }
        return true
    }

    suspend fun saveTake(name: String? = null, beforePublish: TakePublicationIntent = TakePublicationIntent(), onSaved: (String) -> Unit = {}): String? {
        val tempFile = tempPcmFile ?: return null
        if (!finishTake()) return null
        val midiBytes = pendingMidi
        val wavPath = withContext(Dispatchers.IO) {
            synchronized(RecordingBundleFiles) {
                pendingPublicationJournal?.takeIf { it.exists() }?.let { publications.rollback(it) }
                pendingPublicationJournal = null
                val stillPublished = pendingWav?.let { path -> pendingWavIdentity?.let { PublishedTakeFile(File(path), it).stillOwned() } } == true
                if (!stillPublished) abandonTakeIntent()
            }
            val output = recordingsDirectory()
            check(output.mkdirs() || output.isDirectory) { "Could not create recordings directory" }
            val prior = synchronized(RecordingBundleFiles) {
                (publishedWav?.takeIf { it.stillOwned() } ?: pendingWav?.let { path ->
                    pendingWavIdentity?.let { PublishedTakeFile(File(path), it) }?.takeIf { it.stillOwned() }
                })?.let {
                    pendingSaveIntent = beforePublish
                    beforePublish(it.file.absolutePath, it.identity, pendingMidiIdentity)
                    onSaved(it.file.absolutePath)
                    it.file.absolutePath
                }
            }
            if (prior != null) {
                prior
            } else {
                val staging = File.createTempFile(".pyano-take-", ".part", output)
                var midiStaging: File? = null
                var publicationJournal: File? = null
                try {
                    val preparedMidi = if (midiBytes != null) File.createTempFile(".pyano-take-midi-", ".part", output) else null
                    midiStaging = preparedMidi
                    val wav = synchronized(RecordingBundleFiles) {
                        val previous = pendingWav?.takeIf { name?.trim() == pendingName }?.let(::File)
                        val wav = previous?.takeUnless { it.exists() || RecordingBundleFiles.midi(it).exists() } ?: run {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                            if (name == null) File(output, "Pyano_${timestamp}_${java.util.UUID.randomUUID()}.wav")
                            else RecordingNames.destination(output, name)
                        }
                        pendingWav = wav.absolutePath
                        pendingName = name?.trim()
                        pendingWavIdentity = null
                        pendingMidiIdentity = null
                        val draft = publications.beginDraft(staging, preparedMidi, wav, tempFile.length(), recordingSampleRate, midiBytes)
                        publicationJournal = draft
                        pendingPublicationJournal = draft
                        wav
                    }
                    check(encodeToWav(tempFile, staging) != null) { "Could not encode take" }
                    preparedMidi?.writeBytes(checkNotNull(midiBytes))
                    val audioIdentity = ownedFile(staging).identity
                    val midiIdentity = preparedMidi?.let { ownedFile(it).identity }
                    synchronized(RecordingBundleFiles) {
                        pendingWavIdentity = audioIdentity
                        pendingMidiIdentity = midiIdentity
                        pendingSaveIntent = beforePublish
                        beforePublish(wav.absolutePath, audioIdentity, midiIdentity)
                        val journal = publications.armDraft(checkNotNull(publicationJournal))
                        publicationJournal = journal
                        pendingPublicationJournal = journal
                        onSaved(wav.absolutePath)
                        publications.publish(journal)
                        if (preparedMidi != null) publishedMidi = PublishedTakeFile(RecordingBundleFiles.midi(wav), checkNotNull(midiIdentity))
                        publishedWav = PublishedTakeFile(wav, audioIdentity)
                        wav.absolutePath
                    }
                } catch (error: Exception) {
                    publicationJournal?.takeIf { it.exists() }?.let { publications.rollback(it) }
                    if (publicationJournal?.exists() != true) abandonTakeIntent()
                    throw error
                } finally {
                    if (publicationJournal?.exists() != true) {
                        staging.delete()
                        midiStaging?.delete()
                    }
                }
            }
        }
        discardTake(saved = true)

        Log.i(TAG, "Recording saved: $wavPath")
        return wavPath
    }

    suspend fun discardTake(saved: Boolean = false) = withContext(Dispatchers.IO) {
        if (!saved) synchronized(RecordingBundleFiles) {
            pendingPublicationJournal?.takeIf { it.exists() }?.let { publications.rollback(it) }
            val recoveredWav = pendingWav?.let { path -> pendingWavIdentity?.let { PublishedTakeFile(File(path), it) } }
            val recoveredMidi = pendingWav?.let { path -> pendingMidiIdentity?.let { PublishedTakeFile(RecordingBundleFiles.midi(File(path)), it) } }
            listOfNotNull(publishedWav, publishedMidi, recoveredWav, recoveredMidi).distinctBy { it.file.absolutePath }.forEach { owned ->
                check(!owned.stillOwned() || owned.file.delete()) { "Could not discard take; try again" }
            }
            abandonTakeIntent()
        }
        val file = tempPcmFile
        check(file == null || !file.exists() || file.delete()) { "Could not remove temporary take; try again" }
        tempPcmFile = null
        pendingMidi = null
        pendingWav = null
        pendingName = null
        pendingWavIdentity = null
        pendingMidiIdentity = null
        pendingPublicationJournal = null
        pendingSaveIntent = null
        publishedWav = null
        publishedMidi = null
        totalFloatsWritten = 0
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

    private suspend fun encodeToWav(pcmFile: File, wavFile: File): String? = withContext(Dispatchers.IO) {
        check(wavFile.parentFile?.let { it.mkdirs() || it.isDirectory } == true) { "Could not create recordings directory" }
        try {
            FileInputStream(pcmFile).use { pcmInput ->
                RandomAccessFile(wavFile, "rw").use { wavOutput ->
                    wavOutput.setLength(0)
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
            readWavHeader(file).sampleRate
        } catch (_: Exception) {
            DEFAULT_SAMPLE_RATE
        }
    }

    fun recoverSavedTakes(recoverConflicts: Boolean = false, relocateIntent: PublicationIntentRelocation? = null) {
        val liveJournal = pendingPublicationJournal?.takeIf { hasPendingTake }?.absolutePath
        publicationRecoveryWarning = publications.recover(recoverConflicts, relocateIntent, setOfNotNull(liveJournal))
        publicationRecoveryKey = publications.recoveryKey()
        automaticUploadBlocked = publications.automaticUploadBlocked()
    }

    fun publicationReserves(path: String): Boolean = synchronized(RecordingBundleFiles) {
        path in publications.reservedTargets() || publications.automaticUploadBlocked()
    }

    fun getSavedRecordings(): List<RecordingInfo> {
        recoverSavedTakes()
        val pyanoDir = recordingsDirectory()
        if (!pyanoDir.exists()) return emptyList()
        val unresolved = publications.unresolvedTargets()
        return pyanoDir.listFiles()
            ?.filter { it.extension.equals("wav", ignoreCase = true) && it.absolutePath !in unresolved }
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

    /** Synchronously stop draining and remove temp PCM without exporting WAV; safe during onCleared. */
    fun cancelRecording() {
        engine.midiCapture.cancel()
        if (!isRecording) return
        isRecording = false
        drainJob?.cancel()
        drainJob = null
        tempPcmFile?.delete()
        tempPcmFile = null
        totalFloatsWritten = 0
        Log.i(TAG, "Recording cancelled")
    }

    fun renameRecording(path: String, newName: String): String = synchronized(RecordingBundleFiles) {
        check(!publicationReserves(path)) { "Use Recover before renaming a recording involved in interrupted take publication" }
        RecordingBundleFiles.rename(File(path), newName.trim()).absolutePath
    }

    fun deleteRecording(path: String) = synchronized(RecordingBundleFiles) {
        check(!publicationReserves(path)) { "Use Recover before deleting a recording involved in interrupted take publication" }
        RecordingBundleFiles.delete(File(path))
    }
}
