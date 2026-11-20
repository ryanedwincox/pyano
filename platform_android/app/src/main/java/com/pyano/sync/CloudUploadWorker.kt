// Concern: persists cloud recording uploads | Non-concern: upload UI state | IO: (queued recording) -> cloud receipt
package com.pyano.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import java.io.File

class CloudUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        Log.i("PyanoCloudUpload", "Upload started job=$id attempt=$runAttemptCount")
        val wav = inputData.getString(KEY_PATH)?.let(::File) ?: return failure("Queued recording path is missing")
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0)
        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return failure("Cloud profile is missing")
        val title = inputData.getString(KEY_TITLE) ?: return failure("Recording title is missing")
        val capturedAt = inputData.getString(KEY_CAPTURED_AT) ?: return failure("Capture time is missing")
        if (!wav.isFile || durationMs <= 0) return failure("Queued recording is no longer available")
        val credentials = CloudCredentialsStore(applicationContext).load(profileId) ?: return failure("Cloud credentials are unavailable")
        return try {
            CloudSyncClient(applicationContext.cacheDir).upload(
                wav,
                title,
                durationMs,
                capturedAt,
                credentials.endpoint,
                credentials.ownerId,
                credentials.token,
            )
            inputData.getString(KEY_RECORDING_ID)?.let { RecordingCloudStatus(applicationContext).complete(it) }
            Log.i("PyanoCloudUpload", "Upload completed job=$id")
            Result.success(Data.Builder().putString(KEY_SNAPSHOT_PATH, wav.absolutePath).build())
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudSyncException) {
            Log.w("PyanoCloudUpload", "Upload rejected job=$id attempt=$runAttemptCount retryable=${error.retryable}: ${error.message}")
            if (error.retryable && runAttemptCount < 5) Result.retry() else failure(error.message ?: "Cloud upload failed")
        } catch (error: Exception) {
            Log.w("PyanoCloudUpload", "Upload failed job=$id attempt=$runAttemptCount type=${error.javaClass.simpleName}")
            if (runAttemptCount < 5) Result.retry() else failure(error.message ?: "Cloud upload failed")
        }
    }

    private fun failure(message: String): Result {
        val detail = boundedUploadError(message)
        Log.e("PyanoCloudUpload", "Upload stopped job=$id: $detail")
        return Result.failure(Data.Builder().putString(KEY_ERROR, detail).build())
    }

    companion object {
        const val KEY_RECORDING_ID = "recordingId"
        const val KEY_PATH = "recordingPath"
        const val KEY_DURATION_MS = "durationMs"
        const val KEY_PROFILE_ID = "profileId"
        const val KEY_TITLE = "title"
        const val KEY_CAPTURED_AT = "capturedAt"
        const val KEY_ERROR = "error"
        const val KEY_SNAPSHOT_PATH = "snapshotPath"
    }
}
