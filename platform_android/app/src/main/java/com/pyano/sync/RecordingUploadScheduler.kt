// Concern: queues saved takes durably for private upload | Non-concern: publication | IO: (saved bundle and profile) -> WorkManager job
package com.pyano.sync

import android.content.Context
import androidx.work.*
import com.pyano.audio.RecordingInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class RecordingOwnerUnverified(message: String = "This older recording has no verified Cloud owner. Its local copy is unchanged; re-upload is blocked.") :
    IllegalStateException(message)

class CloudProfileUnavailable : IllegalStateException("Reconfigure Cloud with a valid owner and device token")

class RecordingUploadScheduler(
    private val context: Context,
    private val status: RecordingCloudStatus,
    private val credentialsAvailable: (String) -> Boolean = { CloudCredentialsStore(context).load(it) != null },
) {
    suspend fun enqueue(recording: RecordingInfo, profileId: String) = withContext(Dispatchers.IO) {
        val titles = RecordingTitleSync(context)
        status.migrateLegacyIdentity(recording.path, titles)
        status.requirePendingBundle(recording.path)
        if (!credentialsAvailable(profileId)) throw CloudProfileUnavailable()
        if (recording.path in titles.unresolvedRenamePaths()) throw CloudSyncException(
            "This recording's rename needs recovery before upload. Its local WAV and MIDI files are kept.", false)
        val recordingId = status.id(recording.path)
        val credentials = CloudCredentialsStore(context)
        if (status.unverifiedOwnerOrigin(recordingId)) titles.markUnknownOwner(recordingId)
        if (titles.unknownOwner(recordingId)) throw RecordingOwnerUnverified()
        val intendedProfile = status.pendingProfile(recording.path)
        val sameOwner: (String, String) -> Boolean = { first, second ->
            credentials.load(first)?.let { old -> credentials.load(second)?.let { sameCloudOwner(old, it) } } == true
        }
        requireRecordingUploadOwner(intendedProfile, profileId, sameOwner)
        val existing = titles.binding(recordingId)
        val queue = CloudUploadQueue(File(context.filesDir, "cloud-upload-queue"))
        if (existing == null) {
            val history = WorkManager.getInstance(context).getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + recordingId).get()
            if (status.synced(recordingId) || history.any { "pyano-cloud-upload" in it.tags } ||
                queue.hasLegacySnapshot(File(recording.path), credentials.knownProfileIds())) throw RecordingOwnerUnverified()
        }
        requireRecordingUploadOwner(existing?.profileId, profileId, sameOwner)
        if (existing != null) {
            val active = WorkManager.getInstance(context).getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + recordingId).get()
                .any { "pyano-cloud-upload" in it.tags && it.state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) }
            if (active) {
                synchronized(com.pyano.audio.RecordingBundleFiles) {
                    status.requirePendingBundle(recording.path)
                    validateRecordingUploadBinding(existing, profileId, queue.bundleRemoteId(File(recording.path)), sameOwner)
                }
                status.queued(recording.path)
                return@withContext
            }
        }
        val snapshotProfile = existing?.profileId ?: profileId
        val snapshot = verifiedPendingSnapshot(queue, status, File(recording.path), snapshotProfile, recordingId) {
            if (existing != null) validateRecordingUploadBinding(existing, profileId, queue.bundleRemoteId(File(recording.path)), sameOwner)
        }
        val binding = try {
            val remoteId = CloudSyncClient(context.cacheDir).stableId(snapshot.wav,
                com.pyano.audio.RecordingBundleFiles.midi(snapshot.wav).takeIf { it.isFile })
            titles.bind(recordingId, profileId, remoteId, recording.name).also { bound ->
                validateRecordingUploadBinding(bound, profileId, remoteId, sameOwner)
            }
        } catch (error: Exception) {
            if (snapshot.created) queue.remove(snapshot.wav)
            throw error
        }
        val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
            .setInputData(Data.Builder().putString(CloudUploadWorker.KEY_PATH, snapshot.wav.absolutePath)
                .putLong(CloudUploadWorker.KEY_DURATION_MS, recording.durationMs)
                .putString(CloudUploadWorker.KEY_PROFILE_ID, snapshotProfile)
                .putString(CloudUploadWorker.KEY_RECORDING_ID, recordingId)
                .putString(CloudUploadWorker.KEY_TITLE, binding.uploadTitle)
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

internal fun verifiedPendingSnapshot(queue: CloudUploadQueue, status: RecordingCloudStatus, source: File,
    profileId: String, recordingId: String, beforeCopy: () -> Unit = {}): CloudUploadSnapshot = synchronized(com.pyano.audio.RecordingBundleFiles) {
    status.requirePendingBundle(source.absolutePath)
    beforeCopy()
    val snapshot = queue.snapshot(source, profileId, recordingId)
    try {
        status.requirePendingBundle(source.absolutePath)
        status.requirePendingSnapshot(source.absolutePath, snapshot.wav)
        snapshot
    } catch (error: Exception) {
        if (snapshot.created) queue.remove(snapshot.wav)
        throw error
    }
}

data class SavedTakeResult(val path: String, val message: String, val warning: String? = null)

suspend fun saveAndScheduleTake(
    save: suspend (com.pyano.audio.TakePublicationIntent) -> String?,
    status: RecordingCloudStatus,
    scheduler: RecordingUploadScheduler,
    profileId: String?,
    onSaved: ((RecordingInfo) -> Unit)? = null,
): SavedTakeResult {
    val intent = com.pyano.audio.TakePublicationIntent(
        prepare = { path, identity, midiIdentity -> status.pending(path, identity, profileId, midiIdentity) },
        onAbort = { path, identity, midiIdentity -> status.abandonUnpublishedIntent(path, identity, midiIdentity); Unit },
    )
    val path = save(intent) ?: error("Could not save take")
    var warning: String? = null
    var savedRecording: RecordingInfo? = null
    val message = if (profileId == null) "Saved locally; configure Cloud to upload automatically" else try {
        val recording = withContext(Dispatchers.IO) {
            com.pyano.audio.savedRecordingInfo(path)
        }
        savedRecording = recording
        scheduler.enqueue(recording, profileId)
        "Saved and queued for private Cloud upload"
    } catch (e: CancellationException) { throw e }
    catch (_: CloudProfileUnavailable) {
        "Saved locally; reconfigure Cloud with a valid owner and device token, then retry.".also { warning = it }
    }
    catch (error: RecordingOwnerUnverified) { checkNotNull(error.message).also { warning = it } }
    catch (_: Exception) {
        "Saved locally; cloud upload could not be queued. Tap the cloud icon to retry.".also { warning = it }
    }
    if (onSaved != null) {
        try {
            val recording = savedRecording ?: withContext(Dispatchers.IO) { com.pyano.audio.savedRecordingInfo(path) }
            onSaved(recording)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            warning = listOfNotNull(warning,
                "Recording saved, but mastering could not open. Open it from Saved Recordings to retry.").joinToString("\n")
        }
    }
    return SavedTakeResult(path, message, warning)
}
