// Concern: exports a flattened marked PDF copy without altering originals | Non-concern: Cloud sync | IO: (private PDF, page ink) -> Downloads copy
package com.pyano.markup

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.pyano.viewer.boundedPageSize
import java.io.File
import java.io.OutputStream

internal fun paintInk(canvas: Canvas, width: Float, height: Float, strokes: List<InkStroke>) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK_COLOR; strokeWidth = width * INK_WIDTH
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.filter { !it.deleted }.forEach { stroke ->
        if (stroke.points.size == 1) canvas.drawPoint(stroke.points[0][0] * width, stroke.points[0][1] * height, paint)
        else {
            val path = Path()
            stroke.points.forEachIndexed { index, p ->
                if (index == 0) path.moveTo(p[0] * width, p[1] * height) else path.lineTo(p[0] * width, p[1] * height)
            }
            canvas.drawPath(path, paint)
        }
    }
}
internal class MarkedExport(val message: String, val remove: () -> Unit)
internal fun exportMarkedScore(context: Context, source: File, title: String, store: ScoreInkStore, check: () -> Unit): MarkedExport {
    val name = markedScoreFilename(title)
    val temporary = File.createTempFile("marked-score-", ".part", context.cacheDir)
    var uri: android.net.Uri? = null
    var destination: File? = null
    fun cleanup() {
        uri?.let { target ->
            val removed = context.contentResolver.delete(target, null, null)
            val remains = removed == 0 && context.contentResolver.query(target, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
            kotlin.check(!remains) { "An incomplete marked PDF remains in Downloads/Pyano. Remove $name manually." }
        }
        destination?.let { target -> kotlin.check(!target.exists() || target.delete()) {
            "An incomplete marked PDF remains in Downloads/Pyano. Remove ${target.name} manually."
        } }
    }
    try {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
        try {
            require(renderer.pageCount <= 200) { "Marked export supports up to 200 pages" }
            val document = PdfDocument()
            try {
                repeat(renderer.pageCount) { page ->
                    check()
                    val original = renderer.openPage(page)
                    try {
                        val (width, height) = boundedPageSize((original.width.toLong() * 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            (original.height.toLong() * 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            original.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val strokes = store.read(page)
                            val output = document.startPage(PdfDocument.PageInfo.Builder(original.width, original.height, page + 1).create())
                            try {
                            output.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, original.width.toFloat(), original.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                            paintInk(output.canvas, original.width.toFloat(), original.height.toFloat(), strokes)
                            } finally { document.finishPage(output) }
                        } finally { bitmap.recycle() }
                    } finally { original.close() }
                }
                temporary.outputStream().use { document.writeTo(it) }
            } finally { document.close() }
        } finally { renderer.close() }
        check()
        if (Build.VERSION.SDK_INT >= 29) {
            uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pyano")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Could not create the marked PDF")
            context.contentResolver.openOutputStream(uri!!)?.use { output -> temporary.inputStream().use { it.copyTo(output) } }
                ?: error("Could not write the marked PDF")
            check()
            kotlin.check(context.contentResolver.update(uri!!, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "Could not publish the marked PDF to Downloads"
            }
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Pyano")
            kotlin.check(directory.isDirectory || directory.mkdirs())
            destination = (0..999).asSequence().map { File(directory, if (it == 0) name else name.removeSuffix(".pdf") + " ($it).pdf") }
                .firstOrNull { it.createNewFile() } ?: error("No unused PDF filename available")
            destination!!.outputStream().use { output -> temporary.inputStream().use { it.copyTo(output) } }
            check()
        }
        val actualName = if (uri != null) context.contentResolver.query(uri!!, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else name } ?: name else destination?.name ?: name
        return MarkedExport("Exported to Downloads/Pyano: $actualName") {
            cleanup()
        }
    } catch (error: Exception) {
        try { cleanup() } catch (failure: Exception) { throw java.io.IOException("${error.message}. Cleanup failed: ${failure.message}", error) }
        throw error
    } finally { temporary.delete() }
}
