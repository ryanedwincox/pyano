// Concern: persists bounded private reading positions | Non-concern: PDF rendering | IO: (reading state) -> atomic records
package com.pyano.viewer

import com.google.gson.JsonObject
import com.pyano.scores.Score
import com.pyano.scores.scoreHash
import com.pyano.scores.parseScoreJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt

const val MAX_PAGE_PIXELS = 2_000_000
const val MAX_PAGE_DIMENSION = 4096

data class ReadingState(val page: Int = 0, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun bounded(pages: Int): ReadingState {
        require(pages > 0)
        fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
        return copy(page = page.coerceIn(0, pages - 1), zoom = finite(zoom, 1f).coerceIn(1f, 4f),
            panX = finite(panX, 0f).coerceIn(-1f, 1f), panY = finite(panY, 0f).coerceIn(-1f, 1f))
    }
}

fun boundedPageSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "PDF page dimensions are invalid" }
    val scale = minOf(1.0, MAX_PAGE_DIMENSION.toDouble() / width, MAX_PAGE_DIMENSION.toDouble() / height,
        sqrt(MAX_PAGE_PIXELS.toDouble() / (width.toDouble() * height)))
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

data class RestoredReading(val reading: ReadingState, val warning: String? = null)

class ReadingStateStore(private val root: File, private val publish: (File, File) -> Unit = { from, to ->
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit
}) {
    private fun record(namespace: String, score: Score): File {
        require(namespace.matches(Regex("[0-9a-f]{64}")))
        return File(File(root, namespace), "${scoreHash(score.scoreId.toByteArray())}.json")
    }
    private fun read(file: File): JsonObject {
        if (!file.exists()) return JsonObject().apply { add("versions", JsonObject()) }
        require(file.length() <= 65_536)
        val value = parseScoreJson(file.readText())
        val versions = value.getAsJsonObject("versions") ?: error("Missing reading states")
        require(versions.size() <= 16)
        versions.entrySet().forEach { (key, entry) ->
            require(key.matches(Regex("[0-9a-f]{64}")))
            decode(entry.asJsonObject)
        }
        value.get("last")?.let { require(it.isJsonPrimitive && it.asString in versions.keySet()) }
        return value
    }
    private fun decode(value: JsonObject): ReadingState {
        fun number(key: String): Double {
            val entry = value.get(key) ?: error("Missing position")
            require(entry.isJsonPrimitive && entry.asJsonPrimitive.isNumber)
            return entry.asDouble.also { require(it.isFinite()) }
        }
        val page = number("page")
        val zoom = number("zoom"); val x = number("panX"); val y = number("panY")
        require(page in 0.0..Int.MAX_VALUE.toDouble() && page % 1 == 0.0 && zoom in 1.0..4.0 && x in -1.0..1.0 && y in -1.0..1.0)
        return ReadingState(page.toInt(), zoom.toFloat(), x.toFloat(), y.toFloat())
    }
    @Synchronized fun load(namespace: String, score: Score): RestoredReading = try {
        val value = read(record(namespace, score))
        val versions = value.getAsJsonObject("versions")
        val key = if (versions.has(score.identity)) score.identity else value.get("last")?.asString
        RestoredReading(key?.let { decode(versions.getAsJsonObject(it)) } ?: ReadingState())
    } catch (_: Exception) { RestoredReading(ReadingState(), "Saved reading position is invalid; starting at the first page.") }

    @Synchronized fun save(namespace: String, score: Score, state: ReadingState) {
        val target = record(namespace, score)
        val value = try { read(target) } catch (_: Exception) { JsonObject().apply { add("versions", JsonObject()) } }
        val versions = value.getAsJsonObject("versions")
        versions.remove(score.identity)
        versions.add(score.identity, JsonObject().apply {
            addProperty("page", state.page); addProperty("zoom", state.zoom); addProperty("panX", state.panX); addProperty("panY", state.panY)
        })
        while (versions.size() > 16) versions.remove(versions.keySet().first())
        value.addProperty("last", score.identity)
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_536)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temporary = File.createTempFile("reading-", ".part", target.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            publish(temporary, target)
        } finally { temporary.delete() }
    }
}
