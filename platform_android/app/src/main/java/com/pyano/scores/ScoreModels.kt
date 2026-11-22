// Concern: validates score metadata and ownership | Non-concern: PDF rendering | IO: (JSON and profile) -> score identities
package com.pyano.scores

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.pyano.sync.CloudCredentials
import java.io.File
import java.net.URI
import java.security.MessageDigest

const val MAX_SCORE_BYTES = 25L * 1024 * 1024
internal const val MAX_SCORES = 10_000
internal val SCORE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

fun scoreHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class ScoreProfile(val profileId: String, val credentials: CloudCredentials) {
    val ownerId get() = credentials.ownerId
    val endpoint get() = credentials.endpoint.trimEnd('/')
    val namespace: String = scoreHash("$profileId\u0000$ownerId\u0000${URI(endpoint).normalize()}".toByteArray())
    override fun toString() = "ScoreProfile(redacted)"
}

data class Score(val scoreId: String, val title: String, val version: String, val sizeBytes: Long, val sha256: String) {
    val identity get() = scoreHash("$scoreId\u0000$version\u0000$sha256".toByteArray())
}

data class ScoreRow(val score: Score, val downloaded: Boolean, val status: String, val localCopy: Boolean = downloaded, val canDownload: Boolean = true)
data class ScoreLibraryState(
    val namespace: String? = null,
    val endpoint: String = "",
    val ownerId: String = "",
    val rows: List<ScoreRow> = emptyList(),
    val busy: Boolean = false,
    val message: String = "Choose a Cloud profile in Server settings to browse private scores.",
)

class ScoreFailure(message: String) : Exception(message)
internal fun scoreFailure(): Nothing = throw ScoreFailure("Invalid score metadata. Refresh or contact the Cloud administrator.")

internal fun scoreString(element: JsonElement?, max: Int): String {
    if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) scoreFailure()
    return element.asString.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= max && it.none { c -> c < ' ' || c == '\u007f' } } ?: scoreFailure()
}

internal fun decodeCloudScore(value: JsonElement): Score {
    if (!value.isJsonObject) scoreFailure()
    val wire = value.asJsonObject
    if (scoreString(wire.get("contentType"), 128) != "application/pdf") scoreFailure()
    val version = wire.get("metadataVersion") ?: scoreFailure()
    if (!version.isJsonPrimitive || !version.asJsonPrimitive.isNumber) scoreFailure()
    return decodeScore(JsonObject().apply {
        add("scoreId", wire.get("id")); add("title", wire.get("title")); add("version", version)
        add("sizeBytes", wire.get("sizeBytes")); add("sha256", wire.get("sha256"))
    })
}

internal fun decodeScore(value: JsonElement): Score {
    if (!value.isJsonObject) scoreFailure()
    val obj = value.asJsonObject
    val id = scoreString(obj.get("scoreId"), 128).takeIf { SCORE_ID.matches(it) } ?: scoreFailure()
    val title = scoreString(obj.get("title"), 200)
    val versionValue = obj.get("version") ?: scoreFailure()
    val version = if (versionValue.isJsonPrimitive && versionValue.asJsonPrimitive.isString) scoreString(versionValue, 128)
        else if (versionValue.isJsonPrimitive && versionValue.asJsonPrimitive.isNumber && versionValue.asString.matches(Regex("[1-9][0-9]{0,15}"))) {
            versionValue.asString
        } else scoreFailure()
    if (!SCORE_ID.matches(version)) scoreFailure()
    val size = obj.get("sizeBytes") ?: scoreFailure()
    if (!size.isJsonPrimitive || !size.asJsonPrimitive.isNumber || !size.asString.matches(Regex("[1-9][0-9]{0,8}"))) scoreFailure()
    val bytes = size.asString.toLongOrNull()?.takeIf { it in 1..MAX_SCORE_BYTES } ?: scoreFailure()
    val hash = scoreString(obj.get("sha256"), 64).takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: scoreFailure()
    return Score(id, title, version, bytes, hash)
}

internal fun scoreJson(score: Score): JsonObject = JsonObject().apply {
    addProperty("scoreId", score.scoreId); addProperty("title", score.title); addProperty("version", score.version)
    addProperty("sizeBytes", score.sizeBytes); addProperty("sha256", score.sha256)
}

internal fun parseScoreJson(text: String): JsonObject = try {
    GsonBuilder().setStrictness(Strictness.STRICT).create().fromJson(text, JsonObject::class.java) ?: scoreFailure()
} catch (_: Exception) { scoreFailure() }

fun scoreCacheRoot(noBackupFilesDir: File): File = File(noBackupFilesDir, "scores")
