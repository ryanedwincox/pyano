// Concern: maps responsive piano touches to note ownership | Non-concern: synth rendering | IO: (pointers) -> note transitions
package com.pyano.keyboard

import kotlin.math.floor

class PianoKeys(val octaves: Int) {
    val whiteNotes = (60 until 60 + octaves * 12).filter { it % 12 !in listOf(1, 3, 6, 8, 10) }
    val blackNotes = (60 until 60 + octaves * 12).filter { it % 12 in listOf(1, 3, 6, 8, 10) }
    fun blackPosition(note: Int): Float = whiteNotes.count { it < note } - 0.31f
    fun noteAt(x: Float, y: Float, width: Float, height: Float): Int? {
        if (width <= 0 || height <= 0 || x < 0 || x >= width || y < 0 || y >= height) return null
        val unit = width / whiteNotes.size
        if (y < height * .62f) blackNotes.firstOrNull { x / unit >= blackPosition(it) && x / unit < blackPosition(it) + .62f }?.let { return it }
        return whiteNotes.getOrNull(floor(x / unit).toInt())
    }
    companion object {
        fun octavesForWidth(widthDp: Float): Int = when { widthDp < 560 -> 1; widthDp < 1120 -> 2; else -> 3 }
        fun label(note: Int): String = arrayOf("C", "C sharp", "D", "D sharp", "E", "F", "F sharp", "G", "G sharp", "A", "A sharp", "B")[note % 12] + (note / 12 - 1)
    }
}

class PianoTouches(private val onNote: (Int, Boolean) -> Unit) {
    private var notes = emptySet<Int>()
    fun update(pointerNotes: Collection<Int>) {
        val next = pointerNotes.toSet()
        (notes - next).forEach { onNote(it, false) }
        (next - notes).forEach { onNote(it, true) }
        notes = next
    }
    fun release() = update(emptyList())
}
