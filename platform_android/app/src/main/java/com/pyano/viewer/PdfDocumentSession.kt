// Concern: renders bounded Android PDF pages | Non-concern: navigation state | IO: (read-only descriptor) -> page frames
package com.pyano.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor

interface PageFrame {
    val width: Int
    val height: Int
    fun discard()
}
interface PdfDocumentSession : AutoCloseable {
    val pageCount: Int
    fun render(page: Int): PageFrame
}
class BitmapPageFrame(val bitmap: Bitmap) : PageFrame {
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override fun discard() = bitmap.recycle()
}

class AndroidPdfDocumentSession private constructor(private val renderer: PdfRenderer) : PdfDocumentSession {
    override val pageCount get() = renderer.pageCount
    override fun render(page: Int): PageFrame = renderer.openPage(page).use { opened ->
        val (width, height) = boundedPageSize(opened.width, opened.height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            opened.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            BitmapPageFrame(bitmap)
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
    override fun close() = renderer.close()
    companion object {
        fun open(descriptor: ParcelFileDescriptor): AndroidPdfDocumentSession {
            val renderer = try { PdfRenderer(descriptor) } catch (failure: Throwable) { descriptor.close(); throw failure }
            return AndroidPdfDocumentSession(renderer)
        }
    }
}
