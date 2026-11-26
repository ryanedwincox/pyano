// Concern: verifies real PDF rendering and reader UI | Non-concern: personal scores or audible continuity | IO: (synthetic PDFs) -> assertions
package com.pyano

import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import com.pyano.ui.ScoreNightLight
import com.pyano.ui.rememberScoreNightLight
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pyano.scores.*
import com.pyano.sync.CloudCredentials
import com.pyano.viewer.*
import com.pyano.ui.ScoreLibraryContent
import com.pyano.ui.ScoreViewerScreen
import com.pyano.ui.ScoreViewerContent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class ScoreViewerAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun root() = File(compose.activity.noBackupFilesDir, "viewer-test-${UUID.randomUUID()}").apply { mkdirs() }
    private fun pdf(): ByteArray {
        val document = PdfDocument()
        try {
        repeat(3) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(if (index == 2) 10_000 else 600, if (index == 2) 12_000 else 900, index).create())
            page.canvas.drawText("Synthetic PDF page ${index + 1}", 20f, 60f, Paint().apply { textSize = 24f })
            document.finishPage(page)
        }
        return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
        } finally { document.close() }
    }
    @Before fun showTestHostAboveKeyguard() {
        compose.activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 27) { compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true) }
            else {
                @Suppress("DEPRECATION")
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }
    @Test fun realRendererBoundsLargePagesAndOwnsDescriptorsOnSuccessAndFailure() {
        val root = root()
        try {
            val file = File(root, "synthetic.pdf").apply { writeBytes(pdf()) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val document = AndroidPdfDocumentSession.open(descriptor)
            assertEquals(3, document.pageCount)
            repeat(3) { index ->
                val frame = document.render(index)
                assertTrue(frame.width <= 4096 && frame.height <= 4096)
                assertTrue(frame.width.toLong() * frame.height <= MAX_PAGE_PIXELS)
                frame.discard()
            }
            document.close()
            assertFalse(descriptor.fileDescriptor.valid())
            file.writeText("%PDF-not a valid document")
            val bad = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            assertThrows(Exception::class.java) { AndroidPdfDocumentSession.open(bad) }
            assertFalse(bad.fileDescriptor.valid())
        } finally { root.deleteRecursively() }
    }
    @Test fun cacheLeaseSurvivesLocalRemovalButLaterOpenFails() = runBlocking {
        val root = root()
        try {
            val profile = ScoreProfile("fixture", CloudCredentials("https://music.example.com", "owner_fixture", "test-only-token"))
            val bytes = pdf()
            val score = Score("fixture", "Synthetic score", "1", bytes.size.toLong(), scoreHash(bytes))
            val cache = ScoreCache(root, profile)
            cache.download(score) { it.write(bytes) }
            val descriptor = cache.openReadLease(score) { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
            cache.remove(score)
            AndroidPdfDocumentSession.open(descriptor).use { document -> document.render(1).discard() }
            var failed = false
            try { cache.openReadLease(score) { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) } }
            catch (_: ScoreFailure) { failed = true }
            assertTrue(failed)
        } finally { root.deleteRecursively() }
    }
    @Test fun openNavigateZoomGestureRestoreTrimRetryAndBackOnRealCachedPdf() = runBlocking {
        val root = root()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val positions = ReadingStateStore(File(root, "positions"))
        val viewer = ScoreViewerController(scope, positions)
        try {
            val profile = ScoreProfile("fixture", CloudCredentials("https://music.example.com", "owner_fixture", "test-only-token"))
            val bytes = pdf()
            val score = Score("fixture", "Synthetic score", "1", bytes.size.toLong(), scoreHash(bytes))
            val cache = ScoreCache(File(root, "cache"), profile)
            cache.download(score) { it.write(bytes) }
            val opens = java.util.concurrent.atomic.AtomicInteger()
            val closes = java.util.concurrent.atomic.AtomicInteger()
            val source = ViewerSource(profile.namespace, score) {
                cache.openReadLease(score) { file ->
                    val delegate = AndroidPdfDocumentSession.open(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                    opens.incrementAndGet()
                    object : PdfDocumentSession {
                        override val pageCount get() = delegate.pageCount
                        override fun render(page: Int) = delegate.render(page)
                        override fun close() { delegate.close(); closes.incrementAndGet() }
                    }
                }
            }
            val content: @Composable () -> Unit = {
                val state by viewer.state.collectAsState()
                MaterialTheme {
                    if (state.source != null) ScoreViewerScreen(viewer)
                    else ScoreLibraryContent(ScoreLibraryState(profile.namespace, profile.endpoint, profile.ownerId,
                        listOf(ScoreRow(score, true, "Available offline"))), {}, {}, {}, { viewer.open(source) })
                }
            }
            compose.setContent(content)
            compose.onNodeWithText("Synthetic score").performClick()
            compose.waitUntil(5000) { viewer.state.value.frame != null }
            compose.onNodeWithText("Page 1 of 3").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pdf-viewport").performTouchInput {
                pinch(center + Offset(-40f, 0f), center + Offset(40f, 0f), center + Offset(-100f, 0f), center + Offset(100f, 0f))
            }
            compose.runOnIdle { assertEquals(1f, viewer.state.value.reading.zoom); assertEquals(0, viewer.state.value.reading.page) }
            compose.onNodeWithTag("pdf-viewport").performTouchInput { swipeRight() }
            compose.runOnIdle { assertEquals(0, viewer.state.value.reading.page) }
            compose.onNodeWithTag("pdf-viewport").performTouchInput { swipeLeft() }
            compose.waitUntil(5000) { !viewer.state.value.loading && viewer.state.value.reading.page == 1 }
            compose.onNodeWithTag("pdf-viewport").performTouchInput { swipeRight() }
            compose.waitUntil(5000) { !viewer.state.value.loading && viewer.state.value.reading.page == 0 }
            compose.onNodeWithText("Next").performClick()
            compose.waitUntil(5000) { !viewer.state.value.loading && viewer.state.value.reading.page == 1 }
            compose.onNodeWithContentDescription("Zoom in").performScrollTo().performClick()
            compose.waitUntil(5000) { viewer.state.value.reading.zoom > 1f }
            compose.onNodeWithTag("pdf-viewport").performTouchInput { swipeLeft() }
            compose.runOnIdle { assertEquals(1, viewer.state.value.reading.page) }
            compose.onNodeWithContentDescription("Zoom out").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("pdf-viewport").performTouchInput {
                pinch(center + Offset(-40f, 0f), center + Offset(40f, 0f), center + Offset(-100f, 0f), center + Offset(100f, 0f))
            }
            compose.runOnIdle { assertEquals(1.25f, viewer.state.value.reading.zoom) }
            compose.onNodeWithContentDescription("Zoom in").performScrollTo().performClick()
            compose.waitUntil(5000) { viewer.state.value.reading.zoom > 1.3f }
            compose.onNodeWithTag("pdf-viewport").performTouchInput { swipeUp() }
            compose.runOnIdle { assertTrue(kotlin.math.abs(viewer.state.value.reading.panY) > .01f) }
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { activity ->
                if (Build.VERSION.SDK_INT >= 27) { activity.setShowWhenLocked(true); activity.setTurnScreenOn(true) }
                else {
                    @Suppress("DEPRECATION")
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                }
                activity.setContent(content = content)
            }
            compose.waitUntil(5000) { opens.get() >= 2 && closes.get() >= 1 && viewer.state.value.frame != null }
            compose.onNodeWithText("Page 2 of 3").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { viewer.trimMemory() }
            compose.onNodeWithText("PDF memory released. Tap Retry to reopen the saved page.").assertIsDisplayed()
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(5000) { viewer.state.value.frame != null }
            compose.onNodeWithContentDescription("Back to scores").performClick()
            compose.onNodeWithText("Synthetic score").assertIsDisplayed()
            compose.waitUntil(5000) { positions.load(profile.namespace, score).reading.page == 1 }
            compose.onNodeWithText("Synthetic score").performClick()
            compose.waitUntil(5000) { viewer.state.value.frame != null }
            compose.onNodeWithText("Page 2 of 3").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { viewer.clearProfile() }
            compose.onNodeWithContentDescription("Back to scores").assertDoesNotExist()
        } finally { viewer.shutdown(); scope.cancel(); root.deleteRecursively() }
    }
    @Test fun nightLightDefaultsWarmCyclesLevelsAndRemembersChoiceWithoutChangingPdfPixels() {
        val preferences = compose.activity.getSharedPreferences("score-reading", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString("night-light", null)
        preferences.edit().remove("night-light").commit()
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val frame = BitmapPageFrame(bitmap)
        val generation = mutableStateOf(0)
        val active = mutableStateOf(true)
        try {
            compose.setContent { MaterialTheme { if (active.value) androidx.compose.runtime.key(generation.value) {
                val (selected, cycleNightLight) = rememberScoreNightLight()
                ScoreViewerContent(ScoreViewerState(pageCount = 1, frame = frame), {}, {}, { _, _, _ -> }, {},
                    nightLight = selected, onNightLight = { cycleNightLight(); generation.value++ })
            } } }
            fun pixel(): androidx.compose.ui.graphics.Color {
                val pixels = compose.onNodeWithContentDescription("PDF page 1").captureToImage().toPixelMap()
                return pixels[pixels.width / 2, pixels.height / 2]
            }
            compose.onNodeWithContentDescription("Night light: Gentle. Switch to Dim").assertIsDisplayed()
            val gentle = pixel()
            assertEquals(.89f, gentle.red, .02f)
            assertEquals(.84f, gentle.green, .02f)
            assertEquals(.75f, gentle.blue, .02f)
            compose.onNodeWithContentDescription("Night light: Gentle. Switch to Dim").performClick()
            val dim = pixel()
            assertEquals(.74f, dim.red, .02f)
            assertEquals(.685f, dim.green, .02f)
            assertEquals(.615f, dim.blue, .02f)
            assertTrue(dim.red < gentle.red && dim.green < gentle.green && dim.blue < gentle.blue)
            assertEquals(ScoreNightLight.DIM.name, preferences.getString("night-light", null))
            compose.onNodeWithContentDescription("Night light: Dim. Switch to Off").performClick()
            assertTrue(pixel().red > .98f)
            compose.onNodeWithContentDescription("Night light: Off. Switch to Gentle").performClick()
            compose.onNodeWithContentDescription("Night light: Gentle. Switch to Dim").assertIsDisplayed()
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(10, 15))
        } finally {
            compose.runOnIdle { active.value = false }
            compose.waitForIdle()
            preferences.edit().apply { if (previous == null) remove("night-light") else putString("night-light", previous) }.commit()
        }
    }

    @Test fun shortSwipeTurnsBeforeFingerLiftsAndOnlyOncePerGesture() {
        val frame = BitmapPageFrame(Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888))
        val state = mutableStateOf(ScoreViewerState(pageCount = 3, frame = frame, session = 1))
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent { MaterialTheme { ScoreViewerContent(state.value, {}, { delta ->
            state.value = state.value.copy(reading = ReadingState(page = state.value.reading.page + delta))
        }, { _, _, _ -> }, {}) } }
        val viewport = compose.onNodeWithTag("pdf-viewport")
        viewport.performTouchInput { down(center); moveBy(Offset(-12f * density, 0f)); up() }
        compose.runOnIdle { assertEquals(0, state.value.reading.page) }
        viewport.performTouchInput { down(center); moveBy(Offset(-40f * density, 0f)) }
        compose.runOnIdle { assertEquals(1, state.value.reading.page) }
        viewport.performTouchInput { moveBy(Offset(-80f * density, 0f)); up() }
        compose.runOnIdle { assertEquals(1, state.value.reading.page) }
        viewport.performTouchInput { down(center); moveBy(Offset(-40f * density, 0f)); up() }
        compose.runOnIdle { assertEquals(2, state.value.reading.page) }
        viewport.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, state.value.reading.page) }
        viewport.performTouchInput { down(center); moveBy(Offset(40f * density, 0f)) }
        compose.runOnIdle { assertEquals(1, state.value.reading.page) }
        viewport.performTouchInput { up() }
    }

    @Test fun pageSlidesInBothDirectionsAndClearsImmediatelyWhenReleased() {
        val first = BitmapPageFrame(Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888))
        val second = BitmapPageFrame(Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888))
        val state = mutableStateOf(ScoreViewerState(pageCount = 3, frame = first, session = 1))
        compose.setContent { MaterialTheme { ScoreViewerContent(state.value, {}, {}, { _, _, _ -> }, {}) } }
        compose.onNodeWithContentDescription("Back to scores").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.value = state.value.copy(reading = ReadingState(page = 1), frame = second) }
        compose.mainClock.advanceTimeBy(50)
        val oldForward = compose.onNodeWithContentDescription("PDF page 1").getUnclippedBoundsInRoot().left.value
        val newForward = compose.onNodeWithContentDescription("PDF page 2").getUnclippedBoundsInRoot().left.value
        assertTrue(oldForward < newForward)
        compose.onNodeWithText("Next").assertIsEnabled()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("PDF page 1").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(reading = ReadingState(), frame = first) }
        compose.mainClock.advanceTimeBy(50)
        val oldBackward = compose.onNodeWithContentDescription("PDF page 2").getUnclippedBoundsInRoot().left.value
        val newBackward = compose.onNodeWithContentDescription("PDF page 1").getUnclippedBoundsInRoot().left.value
        assertTrue(newBackward < oldBackward)
        compose.runOnIdle { state.value = state.value.copy(frame = null, loading = false) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("PDF page 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("PDF page 2").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
    @Test fun missingAndCorruptDocumentsShowRetryWithoutCrash() = runBlocking {
        val root = root()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val viewer = ScoreViewerController(scope, ReadingStateStore(root))
        val score = Score("bad", "Synthetic invalid PDF", "1", 5, "a".repeat(64))
        try {
            compose.setContent { MaterialTheme { ScoreViewerScreen(viewer) } }
            compose.runOnIdle { viewer.open(ViewerSource("a".repeat(64), score) {
                AndroidPdfDocumentSession.open(ParcelFileDescriptor.open(File(root, "missing.pdf"), ParcelFileDescriptor.MODE_READ_ONLY))
            }) }
            compose.waitUntil(5000) { !viewer.state.value.loading && viewer.state.value.message != null }
            compose.onNodeWithText("Retry").assertIsEnabled()
            val corrupt = File(root, "bad.pdf").apply { writeText("%PDF-invalid fixture") }
            compose.runOnIdle { viewer.open(ViewerSource("a".repeat(64), score) {
                AndroidPdfDocumentSession.open(ParcelFileDescriptor.open(corrupt, ParcelFileDescriptor.MODE_READ_ONLY))
            }) }
            compose.waitUntil(5000) { !viewer.state.value.loading && viewer.state.value.message != null }
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(5000) { !viewer.state.value.loading && viewer.state.value.message != null }
            compose.onNodeWithContentDescription("Back to scores").performClick()
            assertNull(viewer.state.value.source)
        } finally { viewer.shutdown(); scope.cancel(); root.deleteRecursively() }
    }
}
