// Concern: publishes verified private offline PDFs atomically | Non-concern: network protocols | IO: (score bytes) -> durable local versions
package com.pyano.scores

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val MAX_SCORE_MANIFEST_BYTES = 16 * 1024 * 1024

internal data class ScoreManifest(val remote: List<Score> = emptyList(), val cached: List<Score> = emptyList())

class ScoreCache(
    root: File,
    val profile: ScoreProfile,
    private val publish: (File, File) -> Unit = { source, destination ->
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
) {
    private val directory = File(root, profile.namespace)
    private val manifest = File(directory, "manifest.json")
    private val lock = locks.computeIfAbsent(directory.absolutePath) { Mutex() }
    @Volatile var cleanupWarning: String? = null
        private set

    suspend fun rows(): List<ScoreRow> = lock.withLock {
        val saved = readManifest()
        cleanupWarning = try { clean(saved); null } catch (error: ScoreFailure) { error.message }
        val local = saved.cached.associateBy { it.identity }
        val remote = saved.remote.associateBy { it.scoreId }
        val scores = (saved.remote + saved.cached).distinctBy { it.identity }
        scores.map { score ->
            val cached = local.containsKey(score.identity)
            val ready = cached && verify(file(score), score)
            val latest = remote[score.scoreId]
            val status = when {
                cached && !ready -> "Local copy missing or corrupt; download again."
                ready && latest == null -> "Available offline · removed from Cloud"
                ready && latest?.identity != score.identity -> "Available offline · older version ${score.version}"
                ready -> "Available offline · version ${score.version}"
                else -> "Cloud only · version ${score.version}"
            }
            ScoreRow(score, ready, status, cached, latest?.identity == score.identity)
        }
    }

    suspend fun updateRemote(scores: List<Score>) = lock.withLock {
        val old = readManifest()
        writeManifest(old.copy(remote = scores))
    }

    suspend fun download(score: Score, transfer: suspend (java.io.OutputStream) -> Unit) = lock.withLock {
        val old = readManifest()
        if (old.cached.any { it.identity == score.identity } && verify(file(score), score)) return@withLock
        if (!directory.isDirectory && !directory.mkdirs()) throw ScoreFailure("Could not create private score storage. Check free space.")
        val part = File(directory, "${UUID.randomUUID()}.part")
        val destination = file(score)
        val referenced = old.cached.any { it.identity == score.identity }
        var published = false
        try {
            FileOutputStream(part).use { output -> transfer(output); currentCoroutineContext().ensureActive(); output.fd.sync() }
            if (!verify(part, score)) throw ScoreFailure("Score size, checksum or PDF signature did not match. Refresh and download again.")
            currentCoroutineContext().ensureActive()
            publish(part, destination)
            val next = old.copy(cached = old.cached.filterNot { it.identity == score.identity } + score)
            writeManifest(next)
            published = true
        } finally {
            val partRemoved = !part.exists() || part.delete()
            val destinationRemoved = published || referenced || !destination.exists() || destination.delete()
            if (!partRemoved || !destinationRemoved) throw ScoreFailure("Temporary score cleanup failed. Reopen the library to retry cleanup.")
        }
    }

    suspend fun remove(score: Score) = lock.withLock {
        val old = readManifest()
        writeManifest(old.copy(cached = old.cached.filterNot { it.identity == score.identity }))
        val file = file(score)
        if (file.exists() && !file.delete()) throw ScoreFailure("Local copy removal needs cleanup. Reopen the library to retry; Cloud was not changed.")
    }

    suspend fun localFile(score: Score): File? = lock.withLock {
        val saved = readManifest()
        file(score).takeIf { saved.cached.any { entry -> entry.identity == score.identity } && verify(it, score) }
    }

    suspend fun <T> openReadLease(score: Score, open: (File) -> T): T = lock.withLock {
        val saved = readManifest()
        val target = file(score)
        if (saved.cached.none { it.identity == score.identity } || !verify(target, score)) {
            throw ScoreFailure("Local PDF is missing or corrupt. Download it again before opening.")
        }
        open(target)
    }

    private fun file(score: Score) = File(directory, "${score.identity}.pdf")

    private fun readManifest(): ScoreManifest {
        if (!manifest.exists()) return ScoreManifest()
        try {
            if (manifest.length() > MAX_SCORE_MANIFEST_BYTES) scoreFailure()
            val json = parseScoreJson(manifest.readText(Charsets.UTF_8))
            if (json.get("schema")?.asInt != 1 || json.get("namespace")?.asString != profile.namespace) scoreFailure()
            fun entries(name: String): List<Score> {
                val array = json.get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: scoreFailure()
                if (array.size() > MAX_SCORES) scoreFailure()
                val values = array.map { decodeScore(it) }
                if (values.map { it.identity }.toSet().size != values.size) scoreFailure()
                return values
            }
            return ScoreManifest(entries("remote"), entries("cached"))
        } catch (_: Exception) { throw ScoreFailure("Private score index is unreadable. Keep the app data and retry; no cached PDFs were deleted.") }
    }

    private fun writeManifest(value: ScoreManifest) {
        if (!directory.isDirectory && !directory.mkdirs()) throw ScoreFailure("Could not create private score storage. Check free space.")
        if (value.cached.size > MAX_SCORES || value.remote.size > MAX_SCORES) throw ScoreFailure("Offline score library is full. Remove local copies first.")
        val json = JsonObject().apply {
            addProperty("schema", 1); addProperty("namespace", profile.namespace)
            add("remote", JsonArray().apply { value.remote.forEach { add(scoreJson(it)) } })
            add("cached", JsonArray().apply { value.cached.forEach { add(scoreJson(it)) } })
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SCORE_MANIFEST_BYTES) throw ScoreFailure("Offline score index is full. Remove local copies before adding more scores.")
        val temporary = File(directory, "${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            publish(temporary, manifest)
        } finally {
            if (temporary.exists() && !temporary.delete()) throw ScoreFailure("Score index cleanup failed. Reopen the library to retry.")
        }
    }

    private fun clean(value: ScoreManifest) {
        val referenced = value.cached.map { "${it.identity}.pdf" }.toSet() + "manifest.json"
        directory.listFiles()?.forEach { entry ->
            if ((entry.name.endsWith(".part") || entry.name.endsWith(".pdf")) && entry.name !in referenced && !entry.delete()) {
                throw ScoreFailure("Temporary score cleanup failed. Reopen the library to retry.")
            }
        }
    }

    private suspend fun verify(file: File, score: Score): Boolean {
        if (!file.isFile || file.length() != score.sizeBytes) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val signature = ByteArray(5)
                if (input.read(signature) != 5 || signature.toString(Charsets.US_ASCII) != "%PDF-") return false
                digest.update(signature)
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == score.sha256
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
