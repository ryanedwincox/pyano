package com.pyano.audio

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SF2Info(
    val path: String,
    val fileName: String,
    val name: String,
    val date: String? = null,
    val engineer: String? = null,
    val copyright: String? = null,
    val comment: String? = null,
    val sizeBytes: Long = 0,
    val isBundled: Boolean = false
)

object SF2MetadataReader {

    fun readFromFile(file: File, isBundled: Boolean = false): SF2Info? {
        if (!file.exists()) return null
        return try {
            file.inputStream().use { stream ->
                readFromStream(stream, file.absolutePath, file.name, file.length(), isBundled)
            }
        } catch (e: Exception) {
            // Fallback: use filename as name
            SF2Info(
                path = file.absolutePath,
                fileName = file.name,
                name = file.nameWithoutExtension,
                sizeBytes = file.length(),
                isBundled = isBundled
            )
        }
    }

    fun readFromStream(
        stream: InputStream,
        path: String,
        fileName: String,
        sizeBytes: Long,
        isBundled: Boolean = false
    ): SF2Info? {
        val header = ByteArray(12)
        if (stream.read(header) != 12) return null

        // Verify RIFF header
        val riff = String(header, 0, 4)
        val sfbk = String(header, 8, 4)
        if (riff != "RIFF" || sfbk != "sfbk") return null

        var name: String? = null
        var date: String? = null
        var engineer: String? = null
        var copyright: String? = null
        var comment: String? = null

        // Read chunks until we find LIST/INFO
        var bytesRead = 12
        val maxRead = 8192 // Don't read more than 8KB looking for INFO

        while (bytesRead < maxRead) {
            val chunkHeader = ByteArray(8)
            if (stream.read(chunkHeader) != 8) break
            bytesRead += 8

            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

            if (chunkId == "LIST") {
                val listType = ByteArray(4)
                if (stream.read(listType) != 4) break
                bytesRead += 4

                if (String(listType) == "INFO") {
                    // Parse INFO sub-chunks
                    var infoRead = 4L // already read list type
                    while (infoRead < chunkSize) {
                        val subHeader = ByteArray(8)
                        if (stream.read(subHeader) != 8) break
                        infoRead += 8
                        bytesRead += 8

                        val subId = String(subHeader, 0, 4)
                        val subSize = ByteBuffer.wrap(subHeader, 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).int

                        if (subSize <= 0 || subSize > 4096) break

                        val data = ByteArray(subSize)
                        if (stream.read(data) != subSize) break
                        infoRead += subSize
                        bytesRead += subSize

                        // RIFF chunks are word-aligned (pad to even)
                        if (subSize % 2 != 0) {
                            stream.read()
                            infoRead++
                            bytesRead++
                        }

                        val value = String(data).trimEnd('\u0000').trim()
                        if (value.isEmpty()) continue

                        when (subId) {
                            "INAM" -> name = value
                            "ICRD" -> date = value
                            "IENG" -> engineer = value
                            "ICOP" -> copyright = value
                            "ICMT" -> comment = value
                        }
                    }
                    break // Done with INFO, no need to read further
                } else {
                    // Skip non-INFO LIST chunk
                    val skip = chunkSize - 4
                    stream.skip(skip)
                    bytesRead += skip.toInt()
                }
            } else {
                // Skip non-LIST chunk
                stream.skip(chunkSize)
                bytesRead += chunkSize.toInt()
            }
        }

        return SF2Info(
            path = path,
            fileName = fileName,
            name = name ?: fileName.removeSuffix(".sf2").removeSuffix(".SF2"),
            date = date,
            engineer = engineer,
            copyright = copyright,
            comment = comment,
            sizeBytes = sizeBytes,
            isBundled = isBundled
        )
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%d MB".format(bytes / 1_048_576)
            bytes >= 1024 -> "%d KB".format(bytes / 1024)
            else -> "$bytes B"
        }
    }
}
