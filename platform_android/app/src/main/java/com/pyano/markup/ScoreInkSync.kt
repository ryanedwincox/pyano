// Concern: merges owner-scoped Cloud ink without persisting credentials | Non-concern: rendering | IO: (profile, durable edits) -> synced page
package com.pyano.markup

import android.content.Context
import androidx.work.*
import com.google.gson.*
import com.pyano.scores.*
import com.pyano.sync.CloudCredentialsStore
import com.pyano.sync.validateCloudSettings
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

internal class InkSyncFailure(val status: Int, message: String) : Exception(message)
internal fun inkStore(context: Context, profile: ScoreProfile, score: String, hash: String) = ScoreInkStore(
    File(context.noBackupFilesDir, "score-ink"), com.pyano.sync.canonicalCloudOrigin(profile.endpoint) + "\u0000" + profile.ownerId, score, hash)
internal suspend fun syncInk(profile: ScoreProfile, score: String, hash: String, page: Int, store: ScoreInkStore,
    connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) = withContext(Dispatchers.IO) {
    validateCloudSettings(profile.endpoint, profile.ownerId, profile.credentials.token)
    require(SCORE_ID.matches(score) && hash.matches(Regex("[a-f0-9]{64}")) && page in 0..4095)
    val base = "/api/v1/scores/$score/markup/$hash/$page"
    fun request(path: String, body: JsonObject? = null): JsonObject {
        val connection = connect(URI(profile.endpoint + path))
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer ${profile.credentials.token}")
            if (body != null) {
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray()
                connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw InkSyncFailure(status,
                when (status) {
                    401, 403 -> "Cloud pen permission is missing or expired. Local marks are kept."
                    404 -> "This PDF version is no longer in Cloud. Local marks are kept."
                    409 -> "Cloud could not accept this mark. The page may have reached its stroke limit."
                    else -> "Cloud is unavailable. Local marks will retry automatically."
                })
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            return parseScoreJson(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    var cursor: String? = null
    val downloaded = mutableListOf<InkStroke>()
    val seen = mutableSetOf<String>()
    do {
        currentCoroutineContext().ensureActive()
        val response = request(base + (cursor?.let { "?cursor=$it" } ?: ""))
        val strokes = response.getAsJsonArray("strokes") ?: error("Cloud returned invalid pen marks")
        require(strokes.size() <= 50)
        val parsed = strokes.map { decodeInk(it.asJsonObject, false) }
        require(parsed.zipWithNext().all { (a, b) -> a.id < b.id })
        require(cursor == null || parsed.isEmpty() || parsed.first().id > cursor!!)
        val next = response.get("nextCursor")?.takeUnless { it.isJsonNull }?.asString
        require(next == null || (inkId.matches(next) && parsed.lastOrNull()?.id == next && seen.add(next) && seen.size <= 10))
        downloaded.addAll(parsed)
        require(downloaded.size <= 500)
        cursor = next
    } while (cursor != null)
    store.merge(page, downloaded)
    var failure: Exception? = null
    store.read(page).filter { it.pending }.forEach { stroke ->
        if (failure != null && !(failure is InkSyncFailure && (failure as InkSyncFailure).status == 409 && stroke.deleted)) return@forEach
        try {
        currentCoroutineContext().ensureActive()
        val body = JsonObject().apply { add("points", Gson().toJsonTree(stroke.points)); addProperty("deleted", stroke.deleted) }
        val saved = decodeInk(request("$base/${stroke.id}", body), false)
        require(saved.id == stroke.id)
        store.merge(page, listOf(saved), stroke)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = error }
    }
    failure?.let { throw it }

}
internal fun queueInk(context: Context, profileId: String, score: String, hash: String, page: Int) {
    val data = workDataOf("profile" to profileId, "score" to score, "hash" to hash, "page" to page)
    val job = OneTimeWorkRequestBuilder<ScoreInkWorker>().setInputData(data)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    val name = "score-ink-" + scoreHash("$profileId:$score:$hash:$page".toByteArray())
    WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, job)
}
class ScoreInkWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result { return try {
        val profileId = checkNotNull(inputData.getString("profile"))
        val credentials = withContext(Dispatchers.IO) { CloudCredentialsStore(applicationContext).recordingCredentials(profileId) }
            ?: return Result.failure()
        val profile = ScoreProfile(profileId, credentials)
        val score = checkNotNull(inputData.getString("score")); val hash = checkNotNull(inputData.getString("hash"))
        val page = inputData.getInt("page", -1)
        syncInk(profile, score, hash, page, inkStore(applicationContext, profile, score, hash))
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }
}
