// Concern: queues saved takes durably for private upload | Non-concern: publication | IO: (saved bundle and profile) -> WorkManager job
package com.pyano.sync

import android.content.Context
import androidx.work.*
import com.pyano.audio.RecordingInfo
import com.pyano.audio.readWavHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class CloudProfileUnavailable : IllegalStateException("Reconfigure Cloud with a valid owner and device token")

class RecordingUploadScheduler(
    private val context: Context,
    private val status: RecordingCloudStatus,
    private val credentialsAvailable: (String) -> Boolean = { CloudCredentialsStore(context).load(it) != null },
) {
    suspend fun enqueue(recording: RecordingInfo, profileId: String) = withContext(Dispatchers.IO) {
        if (!credentialsAvailable(profileId)) throw CloudProfileUnavailable()
        val snapshot = CloudUploadQueue(File(context.filesDir, "cloud-upload-queue")).snapshot(File(recording.path), profileId)
        val recordingId = status.id(recording.path)
        val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setInputData(Data.Builder().putString(CloudUploadWorker.KEY_PATH, snapshot.wav.absolutePath)
                .putLong(CloudUploadWorker.KEY_DURATION_MS, recording.durationMs)
                .putString(CloudUploadWorker.KEY_PROFILE_ID, profileId)
                .putString(CloudUploadWorker.KEY_RECORDING_ID, recordingId)
                .putString(CloudUploadWorker.KEY_TITLE, recording.name)
                .putString(CloudUploadWorker.KEY_CAPTURED_AT, Instant.ofEpochMilli(recording.dateMs).toString()).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("pyano-cloud-upload").addTag(RecordingCloudStatus.TAG_PREFIX + recordingId).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CloudUploadQueue.uniqueWorkName(snapshot.id), ExistingWorkPolicy.KEEP, request
        ).result.get()
        status.queued(recording.path)
    }
}

data class SavedTakeResult(val path: String, val message: String)

suspend fun saveAndScheduleTake(
    save: suspend ((String) -> Unit) -> String?,
    status: RecordingCloudStatus,
    scheduler: RecordingUploadScheduler,
    profileId: String?,
): SavedTakeResult {
    val path = save { status.pending(it) } ?: error("Could not save take")
    val message = if (profileId == null) "Saved locally; configure Cloud to upload automatically" else try {
        val recording = withContext(Dispatchers.IO) {
            val wav = File(path)
            val header = readWavHeader(wav)
            val duration = header.dataSize * 1000 / (header.sampleRate.toLong() * header.channels * header.bitsPerSample / 8)
            RecordingInfo(wav.nameWithoutExtension, path, duration, wav.lastModified(), wav.length())
        }
        scheduler.enqueue(recording, profileId)
        "Saved and queued for private Cloud upload"
    } catch (e: CancellationException) { throw e }
    catch (_: CloudProfileUnavailable) { "Saved locally; reconfigure Cloud with a valid owner and device token, then retry." }
    catch (_: Exception) { "Saved locally; cloud upload could not be queued. Tap the cloud icon to retry." }
    return SavedTakeResult(path, message)
}
