// Concern: validates new take names | Non-concern: capture or upload | IO: (title and directory) -> safe unique WAV path
package com.pyano.audio

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RecordingNames {
    fun valid(name: String): Boolean = name.trim().let {
        it.isNotEmpty() && it.length <= 200 && it.toByteArray(Charsets.UTF_8).size <= 220 &&
            it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it && it !in setOf(".", "..") && it.none { char -> char in "/\\<>:\"|?*" || char.isISOControl() }
    }

    fun suggestion(title: String?): String = title?.map { if (it in "/\\<>:\"|?*" || it.isISOControl()) ' ' else it }
        ?.joinToString("")?.trim()?.let { safe ->
            var result = safe
            while (result.length > 180 || result.toByteArray(Charsets.UTF_8).size > 220) result = dropLastCodePoint(result)
            result
        }?.takeIf(::valid)
        ?: "Recording ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))}"

    private fun dropLastCodePoint(value: String) = value.substring(0, value.offsetByCodePoints(value.length, -1))

    fun destination(directory: File, name: String): File {
        require(valid(name)) { "Enter a shorter name without filename punctuation" }
        val title = name.trim()
        var result = File(directory, "$title.wav")
        var suffix = 2
        while (result.exists() || RecordingBundleFiles.midi(result).exists()) {
            val ending = " (${suffix++})"
            var base = title
            while (!valid(base + ending)) base = dropLastCodePoint(base)
            result = File(directory, "$base$ending.wav")
        }
        return result
    }
}
