// Concern: downloads authenticated score metadata and PDFs | Non-concern: cache publication | IO: (profile and score) -> validated network bytes
package com.pyano.scores

import com.pyano.sync.validateCloudSettings
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

interface ScoreResponse : Closeable {
    val status: Int
    val contentType: String?
    fun body(): InputStream
}

fun interface ScoreTransport { fun open(profile: ScoreProfile, path: String): ScoreResponse }

class HttpScoreTransport : ScoreTransport {
    override fun open(profile: ScoreProfile, path: String): ScoreResponse {
        validateCloudSettings(profile.endpoint, profile.ownerId, profile.credentials.token)
        val connection = URI(profile.endpoint + path).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer ${profile.credentials.token}")
        connection.setRequestProperty("Accept", if (path.contains("/download")) "application/pdf" else "application/json")
        return object : ScoreResponse {
            override val status get() = connection.responseCode
            override val contentType get() = connection.contentType
            override fun body(): InputStream = connection.inputStream
            override fun close() = connection.disconnect()
        }
    }
}

class CloudScoreClient(private val transport: ScoreTransport = HttpScoreTransport()) {
    suspend fun list(profile: ScoreProfile): List<Score> {
        val result = mutableListOf<Score>()
        val seen = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val path = "/api/v1/scores?limit=100" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val page = response(profile, path, "application/json") { response ->
                val bytes = response.body().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    copyBounded(input, output, 1024L * 1024)
                    output.toByteArray()
                }
                val text = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) { scoreFailure() }
                parseScoreJson(text)
            }
            val scores = page.get("scores") ?: scoreFailure()
            if (!scores.isJsonArray || scores.asJsonArray.size() > 100) scoreFailure()
            for (entry in scores.asJsonArray) {
                val score = decodeScore(entry)
                if (!seen.add(score.scoreId) || result.size >= MAX_SCORES) scoreFailure()
                result.add(score)
            }
            cursor = page.get("nextCursor")?.takeUnless { it.isJsonNull }?.let { scoreString(it, 2048) }
            if (cursor != null && !cursors.add(cursor)) scoreFailure()
            if (cursors.size > 1000) scoreFailure()
        } while (cursor != null)
        return result
    }

    suspend fun download(profile: ScoreProfile, score: Score, output: OutputStream) {
        response(profile, "/api/v1/scores/${score.scoreId}/download", "application/pdf") { response ->
            response.body().use { input -> copyBounded(input, output, score.sizeBytes) }
        }
    }

    private suspend fun <T> response(profile: ScoreProfile, path: String, type: String, block: suspend (ScoreResponse) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                coroutineScope {
                    val response = transport.open(profile, path)
                    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { response.close() }
                    }
                    try {
                        when (response.status) {
                            200 -> Unit
                            401, 403 -> throw ScoreFailure("Provision a new Cloud device token with scores:read permission in Recorder settings.")
                            404 -> throw ScoreFailure("This score is no longer available. Refresh the library; offline copies are kept.")
                            else -> throw ScoreFailure("Cloud scores are unavailable. Retry when connected; offline copies are kept.")
                        }
                        if (response.contentType?.substringBefore(';')?.trim()?.lowercase() != type) {
                            throw ScoreFailure("Cloud returned an unsupported score response. Refresh or contact the administrator.")
                        }
                        block(response)
                    } finally { watcher.cancelAndJoin(); response.close() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ScoreFailure) { throw e }
            catch (_: Exception) { throw ScoreFailure("Could not reach Cloud scores. Check your connection and retry; offline copies are kept.") }
        }
}

internal suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        if (total > limit) throw ScoreFailure("Score response exceeds its size limit. Refresh the library.")
        output.write(buffer, 0, count)
    }
}
