// Concern: sends durable recording title changes | Non-concern: audio upload | IO: (queued identity) -> authenticated Cloud title
package com.pyano.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class RecordingTitleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(CloudUploadWorker.KEY_RECORDING_ID) ?: return Result.failure()
        return try {
            syncRecordingTitle(RecordingTitleSync(applicationContext), id) { binding, title ->
                val credentials = CloudCredentialsStore(applicationContext).recordingCredentials(binding.profileId)
                    ?: error("Cloud title update needs device credentials. Reconfigure Cloud and retry.")
                val found = CloudSyncClient(applicationContext.cacheDir).rename(
                    credentials.endpoint, credentials.ownerId, credentials.token, binding.remoteId, title
                )
                if (!found && RecordingCloudStatus(applicationContext).synced(id)) {
                    throw CloudSyncException("Recording is no longer available in Cloud. Local name was kept.", false)
                }
                found
            }
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if ((error !is CloudSyncException || error.retryable) && runAttemptCount < 5) Result.retry()
            else titleSyncFailure(error.message ?: "Retry the rename.")
        }
    }
}

internal fun titleSyncFailure(message: String): androidx.work.ListenableWorker.Result =
    androidx.work.ListenableWorker.Result.success(Data.Builder().putString(CloudUploadWorker.KEY_ERROR,
        "Could not update Cloud recording name. ${boundedUploadError(message)}").build())
