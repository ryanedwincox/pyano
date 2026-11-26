// Concern: verifies drawing, offline persistence and marked PDF export | Non-concern: personal scores | IO: (synthetic pages, touch) -> assertions
package com.pyano

import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.graphics.pdf.*
import android.os.*
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pyano.markup.*
import com.pyano.scores.*
import com.pyano.sync.CloudCredentials
import com.pyano.ui.ScoreViewerContent
import com.pyano.viewer.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ScoreInkAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun showAboveLockScreen() { compose.activity.runOnUiThread {
        if (Build.VERSION.SDK_INT >= 27) { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true) }
    } }
    private fun profile() = ScoreProfile("ink-test-${UUID.randomUUID()}", CloudCredentials("https://example.test", "synthetic", "pyano_fake_000000000000000000000"))
    private val score = Score("synthetic", "Synthetic pen score", "1", 10, "a".repeat(64))
    @Test fun drawsWithoutTurningPagesAndRestoresLocalMarksAndUndo() {
        val base = compose.activity
        val root = File(base.cacheDir, "ink-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val profile = profile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun session() = ScoreInkSession(context, profile, score, scope, synchronize = { _, _ -> }) { error("Not exported") }
        val current = mutableStateOf(session())
        val active = mutableStateOf(true)
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        var pageTurns = 0
        try {
            compose.setContent { MaterialTheme { if (active.value) {
                ScoreViewerContent(ScoreViewerState(source = ViewerSource("a".repeat(64), score, current.value) { error("Unused") },
                    frame = BitmapPageFrame(bitmap), pageCount = 2), {}, { pageTurns++ }, { _, _, _ -> }, {})
            } } }
            compose.waitUntil(5000) { current.value.state.value.loaded }
            compose.onNodeWithText("Reset zoom").assertDoesNotExist()
            compose.onNodeWithText("Export marked PDF").assertDoesNotExist()
            val zoom = compose.onNodeWithContentDescription("Zoom in").getUnclippedBoundsInRoot()
            val pen = compose.onNodeWithContentDescription("Pen").getUnclippedBoundsInRoot()
            val undo = compose.onNodeWithContentDescription("Undo pen mark").getUnclippedBoundsInRoot()
            assertTrue(pen.left >= zoom.right)
            assertTrue(undo.left >= pen.right)
            compose.onNode(hasContentDescription("Score sync:", substring = true)).assertIsDisplayed()
            compose.onNodeWithContentDescription("Pen").performClick()
            compose.onNodeWithTag("score-pen-canvas").performTouchInput {
                swipe(Offset(width * .4f, height * .45f), Offset(width * .6f, height * .55f), 500)
            }
            compose.waitUntil(5000) { current.value.state.value.strokes.any { !it.deleted } }
            compose.runOnIdle { assertEquals(0, pageTurns); current.value = session() }
            compose.waitUntil(5000) { current.value.state.value.loaded && current.value.state.value.strokes.isNotEmpty() }
            assertTrue(current.value.state.value.strokes.single().points.all { p -> p.all { it in 0f..1f } })
            compose.onNodeWithContentDescription("Undo pen mark").performClick()
            compose.waitUntil(5000) { current.value.state.value.strokes.singleOrNull()?.deleted == true && !current.value.state.value.saving }
            val stored = inkStore(context, profile, score.scoreId, score.sha256).read(0)
            assertTrue(stored.single().deleted)
        } finally {
            compose.runOnIdle { active.value = false }; compose.waitForIdle(); scope.cancel(); root.deleteRecursively()
            androidx.work.WorkManager.getInstance(base).cancelUniqueWork("score-ink-" + scoreHash("${profile.profileId}:${score.scoreId}:${score.sha256}:0".toByteArray()))
        }
    }
    @Test fun syncIconShowsFailureDetailsAndKeepsRetryReachable() {
        var retries = 0
        val message = "Local marks could not be saved"
        compose.setContent { MaterialTheme {
            com.pyano.ui.ScoreInkStatus(InkPage(page = 0, loaded = true, message = message, error = true)) { retries++ }
        } }
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithContentDescription("Score sync: $message").performClick()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Score sync").assertDoesNotExist()
    }
    @Test fun unsavedLocalMarkFailureOpensDetailsAutomatically() {
        var retries = 0
        val message = "Could not save this mark. Check storage and tap Retry before leaving."
        compose.setContent { MaterialTheme {
            com.pyano.ui.ScoreInkStatus(InkPage(page = 0, loaded = false, message = message, error = true)) { retries++ }
        } }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun exportsEveryPageWithInkAndKeepsTheOriginalPdfUnchanged() = runBlocking<Unit> {
        val context = compose.activity
        if (Build.VERSION.SDK_INT <= 28) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val root = File(context.cacheDir, "ink-export-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "source.pdf")
        val document = PdfDocument()
        repeat(2) { page ->
            val output = document.startPage(PdfDocument.PageInfo.Builder(200, 300, page + 1).create())
            output.canvas.drawColor(Color.WHITE); document.finishPage(output)
        }
        source.outputStream().use { document.writeTo(it) }; document.close()
        val original = source.readBytes()
        val store = ScoreInkStore(root, "owner", "score", "a".repeat(64))
        store.edit(1, InkStroke(UUID.randomUUID().toString(), listOf(listOf(.2f, .5f), listOf(.8f, .5f))))
        var exported: MarkedExport? = null
        val title = "Ink export ${UUID.randomUUID()}"
        try {
            exported = withContext(Dispatchers.IO) { exportMarkedScore(context, source, title, store) {} }
            assertArrayEquals(original, source.readBytes())
            val resolver = context.contentResolver
            val descriptor = if (Build.VERSION.SDK_INT >= 29) {
            val uri = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf("$title marked.pdf"), null)!!.use {
                assertTrue(it.moveToFirst()); android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
            }
            resolver.openFileDescriptor(uri, "r")!!
            } else ParcelFileDescriptor.open(File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "Pyano/$title marked.pdf"), ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(descriptor)
            try {
                assertEquals(2, renderer.pageCount)
                repeat(2) { index ->
                    val page = renderer.openPage(index)
                    val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val pixel = bitmap.getPixel(600, 900)
                        if (index == 0) assertEquals(Color.WHITE, pixel) else assertEquals(Color.BLACK, pixel)
                    } finally { page.close(); bitmap.recycle() }
                }
            } finally { renderer.close() }
        } finally { exported?.remove?.invoke(); root.deleteRecursively() }
    }
}
