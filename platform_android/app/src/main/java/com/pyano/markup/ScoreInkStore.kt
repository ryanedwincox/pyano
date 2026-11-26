// Concern: atomically preserves immutable page strokes and pending undo operations | Non-concern: network | IO: (private files, edits) -> durable ink
package com.pyano.markup

import com.google.gson.*
import com.pyano.scores.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val INK_WIDTH = .0035f
const val INK_COLOR = 0xff245fbe.toInt()
data class InkStroke(val id: String, val points: List<List<Float>>, val deleted: Boolean = false, val pending: Boolean = true)
internal val inkId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
internal fun decodeInk(value: JsonObject, pending: Boolean): InkStroke {
    val id = value.get("id")?.asString ?: error("Invalid pen identifier")
    require(inkId.matches(id))
    val deleted = value.get("deleted")?.also { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }?.asBoolean ?: false
    val points = value.getAsJsonArray("points") ?: error("Invalid pen points")
    require(points.size() <= 256 && (deleted || points.size() > 0))
    val coordinates = points.map { point ->
        require(point.isJsonArray && point.asJsonArray.size() == 2)
        point.asJsonArray.map { coordinate ->
            require(coordinate.isJsonPrimitive && coordinate.asJsonPrimitive.isNumber)
            coordinate.asFloat.also { require(it.isFinite() && it in 0f..1f) }
        }
    }
    return InkStroke(id, coordinates, deleted, pending)
}
internal fun markedScoreFilename(title: String): String {
    val cleaned = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
    var bytes = 0
    val stem = buildString {
        for (point in cleaned.codePoints().toArray()) {
            val character = String(Character.toChars(point))
            val size = character.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 180) break
            append(character); bytes += size
        }
    }.trim(' ', '.').ifBlank { "Score" }
    return "$stem marked.pdf"
}
class ScoreInkStore(private val root: File, private val ownerOrigin: String, private val scoreId: String, private val hash: String) {
    init { require(hash.matches(Regex("[a-f0-9]{64}"))) }
    private fun file(page: Int): File {
        require(page in 0..4095)
        return File(File(root, scoreHash("$ownerOrigin\u0000$scoreId\u0000$hash".toByteArray())), "$page.json")
    }
    fun read(page: Int): List<InkStroke> = synchronized(LOCK) {
        val target = file(page)
        if (!target.exists()) return@synchronized emptyList()
        require(target.length() <= 16 * 1024 * 1024) { "Saved pen marks are too large" }
        val values = JsonParser.parseString(target.readText()).asJsonArray
        require(values.size() <= 1000)
        values.map { value ->
            val obj = value.asJsonObject
            decodeInk(obj, obj.get("pending")?.asBoolean ?: false)
        }.also { require(it.map { stroke -> stroke.id }.toSet().size == it.size) }
    }
    fun edit(page: Int, stroke: InkStroke) = synchronized(LOCK) {
        decodeInk(Gson().toJsonTree(stroke).asJsonObject, stroke.pending)
        val rows = read(page).associateByTo(linkedMapOf()) { it.id }
        val old = rows[stroke.id]
        require(old == null || stroke.deleted || old.points == stroke.points) { "Pen identifiers cannot be reused" }
        rows[stroke.id] = if (old?.deleted == true) old else stroke
        write(page, rows.values.toList())
    }
    fun merge(page: Int, remote: List<InkStroke>, acknowledged: InkStroke? = null) = synchronized(LOCK) {
        val before = read(page)
        val rows = before.associateByTo(linkedMapOf()) { it.id }
        remote.forEach { incoming ->
            val old = rows[incoming.id]
            rows[incoming.id] = when {
                incoming.deleted -> incoming.copy(pending = false)
                old?.deleted == true -> old
                old?.pending == true && old != acknowledged -> old
                else -> incoming.copy(pending = false)
            }
        }
        if (rows.values.toList() != before) write(page, rows.values.toList())
    }
    private fun write(page: Int, rows: List<InkStroke>) {
        require(rows.size <= 1000) { "This page has reached its pen-stroke limit" }
        val target = file(page)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temporary = File.createTempFile("ink-", ".part", target.parentFile)
        try {
            val bytes = Gson().toJson(rows).toByteArray()
            require(bytes.size <= 16 * 1024 * 1024)
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private companion object { val LOCK = Any() }
}
