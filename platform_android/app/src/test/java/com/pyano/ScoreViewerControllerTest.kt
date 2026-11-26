// Concern: verifies serialized viewer lifecycle and stale results | Non-concern: native PDF rendering | IO: (fake documents) -> ownership assertions
package com.pyano

import com.pyano.viewer.*
import com.pyano.scores.Score
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ScoreViewerControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private val score = Score("fixture", "Synthetic score", "1", 5, "a".repeat(64))
    private val namespace = "a".repeat(64)
    private class Frame : PageFrame {
        override val width = 100
        override val height = 200
        var discarded = false
        override fun discard() { discarded = true }
    }
    private class Document(override val pageCount: Int = 3, val renderAction: (Int) -> PageFrame = { Frame() }) : PdfDocumentSession {
        val closes = AtomicInteger()
        val pages = mutableListOf<Int>()
        override fun render(page: Int): PageFrame { synchronized(pages) { pages.add(page) }; return renderAction(page) }
        override fun close() { closes.incrementAndGet() }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(5) }

    @Test fun restoresClampsAndPersistsLatestPositionEvenWhenImmediatelyClosed() = runBlocking {
        val root = temp.newFolder()
        ReadingStateStore(root).save(namespace, score, ReadingState(9, 3f))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(root))
        val document = Document()
        try {
            viewer.open(ViewerSource(namespace, score) { document })
            await { viewer.state.value.frame != null }
            assertEquals(2, viewer.state.value.reading.page)
            assertEquals(3f, viewer.state.value.reading.zoom)
            viewer.page(-1); viewer.transform(2f, .3f, -.2f); viewer.close()
            await { document.closes.get() == 1 }
            assertEquals(1, ReadingStateStore(root).load(namespace, score).reading.page)
            assertEquals(2f, ReadingStateStore(root).load(namespace, score).reading.zoom)
            assertNull(viewer.state.value.source)
        } finally { viewer.shutdown(); scope.cancel() }
    }
    @Test fun zeroPageRenderFailureAndAllocationFailureCloseDocumentsWithoutCrashingOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        val zero = Document(0)
        try {
            viewer.open(ViewerSource(namespace, score) { zero })
            await { viewer.state.value.message != null }
            assertEquals(1, zero.closes.get()); assertTrue(zero.pages.isEmpty())
            val allocation = Document(renderAction = { throw OutOfMemoryError("synthetic") })
            viewer.open(ViewerSource(namespace, score) { allocation })
            await { viewer.state.value.message?.contains("memory") == true }
            assertEquals(1, allocation.closes.get()); assertTrue(scope.isActive)
            val bad = Document(renderAction = { error("corrupt test data") })
            viewer.open(ViewerSource(namespace, score) { bad })
            await { !viewer.state.value.loading && viewer.state.value.message != null }
            assertEquals(1, bad.closes.get()); assertTrue(scope.isActive)
        } finally { viewer.shutdown(); scope.cancel() }
    }
    @Test fun profileClearingDuringNativeRenderDiscardsLateFrameAndSerializesClose() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val frame = Frame()
        val document = Document(renderAction = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); frame })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        try {
            viewer.open(ViewerSource(namespace, score) { document })
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            viewer.clearProfile()
            assertNull(viewer.state.value.source); assertEquals(0, document.closes.get())
            release.countDown()
            await { document.closes.get() == 1 }
            assertTrue(frame.discarded); assertNull(viewer.state.value.frame)
        } finally { release.countDown(); viewer.shutdown(); scope.cancel() }
    }
    @Test fun rapidNavigationCoalescesAndResumedTrimHasImmediateRetryRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val documents = mutableListOf<Document>()
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        val source = ViewerSource(namespace, score) { Document(20).also { documents.add(it) } }
        try {
            viewer.open(source); await { viewer.state.value.frame != null }
            repeat(10) { viewer.page(1) }
            await { !viewer.state.value.loading && viewer.state.value.reading.page == 10 }
            viewer.trimMemory()
            assertNull(viewer.state.value.frame); assertTrue(viewer.state.value.message!!.contains("Retry"))
            await { documents.first().closes.get() == 1 }
            viewer.retry()
            await { viewer.state.value.frame != null }
            assertEquals(10, viewer.state.value.reading.page)
            viewer.foreground(false); await { documents.last().closes.get() == 1 }
            assertNull(viewer.state.value.frame)
            viewer.trimMemory()
            viewer.foreground(true); await { viewer.state.value.frame != null }
            assertEquals(10, viewer.state.value.reading.page)
            assertTrue(documents.size >= 3)
        } finally { viewer.shutdown(); scope.cancel() }
    }
    @Test fun readablePageIsPublishedBeforeSlowPositionSaveCompletes() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ReadingStateStore(temp.newFolder()) { from, to ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Unit
        }
        val viewer = ScoreViewerController(scope, store)
        try {
            viewer.open(ViewerSource(namespace, score) { Document() })
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertNotNull(viewer.state.value.frame)
            assertFalse(viewer.state.value.loading)
        } finally { release.countDown(); viewer.shutdown(); scope.cancel() }
    }

    @Test fun lateSaveFailureDoesNotReplaceMemoryReleaseGuidance() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ReadingStateStore(temp.newFolder()) { _, _ ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); error("full storage fixture")
        }
        val viewer = ScoreViewerController(scope, store)
        val document = Document()
        try {
            viewer.open(ViewerSource(namespace, score) { document })
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            viewer.trimMemory()
            release.countDown()
            await { document.closes.get() == 1 }
            assertNull(viewer.state.value.frame)
            assertTrue(viewer.state.value.message!!.contains("PDF memory released"))
        } finally { release.countDown(); viewer.shutdown(); scope.cancel() }
    }

    @Test fun failedPositionSaveLeavesReadableFrameAndActionableWarning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()) { _, _ -> error("full storage fixture") })
        try {
            viewer.open(ViewerSource(namespace, score) { Document() })
            await { viewer.state.value.frame != null && viewer.state.value.message?.contains("save reading position") == true }
            viewer.transform(2f, .1f, .2f)
            await { viewer.state.value.reading.zoom == 2f && viewer.state.value.message != null }
            assertNotNull(viewer.state.value.frame)
        } finally { viewer.shutdown(); scope.cancel() }
    }
    @Test fun queuedNavigationRendersOnlyLatestAfterNativeCallCompletes() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val discarded = Frame()
        val document = Document(20) { page ->
            if (page == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); discarded } else Frame()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        try {
            viewer.open(ViewerSource(namespace, score) { document }); await { viewer.state.value.frame != null }
            viewer.page(1)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            repeat(8) { viewer.page(1) }
            release.countDown()
            await { !viewer.state.value.loading && viewer.state.value.reading.page == 9 }
            assertEquals(listOf(0, 1, 9), document.pages)
            assertTrue(discarded.discarded)
        } finally { release.countDown(); viewer.shutdown(); scope.cancel() }
    }

    @Test fun twoVisibleHostsKeepReaderUntilLastHostStops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        val documents = java.util.concurrent.CopyOnWriteArrayList<Document>()
        try {
            val first = viewer.registerHost(true); val second = viewer.registerHost(true)
            viewer.open(ViewerSource(namespace, score) { Document().also { documents.add(it) } })
            await { viewer.state.value.frame != null }
            val frame = viewer.state.value.frame
            viewer.hostForeground(first, false); viewer.unregisterHost(first)
            assertSame(frame, viewer.state.value.frame)
            assertEquals(0, documents.first().closes.get())
            viewer.hostForeground(second, false)
            assertNull(viewer.state.value.frame)
            await { documents.first().closes.get() == 1 }
            viewer.hostForeground(second, true)
            await { viewer.state.value.frame != null }
            assertEquals(2, documents.size)
            viewer.unregisterHost(second)
        } finally { viewer.shutdown(); scope.cancel() }
    }
    @Test fun stopStartDuringNativeRenderStillClosesAndReopensRenderer() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val stale = Frame()
        val first = Document { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); stale }
        val next = Document()
        val opens = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        try {
            viewer.open(ViewerSource(namespace, score) { if (opens.getAndIncrement() == 0) first else next })
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            viewer.foreground(false); viewer.foreground(true)
            release.countDown()
            await { viewer.state.value.frame != null }
            assertEquals(2, opens.get()); assertEquals(1, first.closes.get())
            assertTrue(stale.discarded)
        } finally { release.countDown(); viewer.shutdown(); scope.cancel() }
    }
    @Test fun settledZoomWritesPositionOnceAndCloseDoesNotRepeatIt() = runBlocking {
        val writes = AtomicInteger()
        val store = ReadingStateStore(temp.newFolder()) { from, to ->
            java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            writes.incrementAndGet()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, store)
        val document = Document()
        try {
            viewer.open(ViewerSource(namespace, score) { document }); await { viewer.state.value.frame != null && writes.get() == 1 }
            writes.set(0)
            viewer.transform(2f, .1f, -.2f)
            await { writes.get() >= 1 }
            viewer.close(); await { document.closes.get() == 1 }
            assertEquals(1, writes.get())
        } finally { viewer.shutdown(); scope.cancel() }
    }
    @Test fun publicationCannotOverwriteGestureWhileItsRequestIsBeingCaptured() = runBlocking {
        val entered = CountDownLatch(1); val releaseRender = CountDownLatch(1)
        val mutationVisible = CountDownLatch(1); val releaseMutation = CountDownLatch(1)
        val renderFinished = CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        val document = Document { page ->
            if (page == 1) { entered.countDown(); check(releaseRender.await(5, TimeUnit.SECONDS)); renderFinished.countDown() }
            Frame()
        }
        val observer = scope.launch(Dispatchers.Unconfined) {
            viewer.state.collect { state ->
                if (state.reading.zoom == 2f && armed.compareAndSet(true, false)) {
                    mutationVisible.countDown(); check(releaseMutation.await(5, TimeUnit.SECONDS))
                }
            }
        }
        try {
            viewer.open(ViewerSource(namespace, score) { document }); await { viewer.state.value.frame != null }
            viewer.page(1)
            assertNull(viewer.state.value.frame)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            armed.set(true)
            val action = scope.launch { viewer.transform(2f, .3f, -.2f) }
            assertTrue(withContext(Dispatchers.IO) { mutationVisible.await(5, TimeUnit.SECONDS) })
            releaseRender.countDown()
            assertTrue(withContext(Dispatchers.IO) { renderFinished.await(5, TimeUnit.SECONDS) })
            delay(100)
            assertEquals(2f, viewer.state.value.reading.zoom)
            releaseMutation.countDown(); action.join()
            await { !viewer.state.value.loading && viewer.state.value.frame != null }
            assertEquals(2f, viewer.state.value.reading.zoom)
            assertEquals(.3f, viewer.state.value.reading.panX)
        } finally { releaseMutation.countDown(); releaseRender.countDown(); observer.cancel(); viewer.shutdown(); scope.cancel() }
    }

    @Test fun immediateSameSourceReopenFlushesLatestPendingPositionBeforeRestore() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val first = Document { page ->
            if (page == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            Frame()
        }
        val opens = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, ReadingStateStore(temp.newFolder()))
        val source = ViewerSource(namespace, score) { if (opens.getAndIncrement() == 0) first else Document() }
        try {
            viewer.open(source); await { viewer.state.value.frame != null }
            viewer.page(1)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            viewer.transform(2f, .2f, -.3f); viewer.close(); viewer.open(source)
            release.countDown()
            await { !viewer.state.value.loading && viewer.state.value.frame != null }
            assertEquals(1, viewer.state.value.reading.page)
            assertEquals(2f, viewer.state.value.reading.zoom)
            assertEquals(2, opens.get())
        } finally { release.countDown(); viewer.shutdown(); scope.cancel() }
    }

    @Test fun failedPendingSaveOnImmediateReopenKeepsWarningWhenPriorDiskStateRestores() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reject = java.util.concurrent.atomic.AtomicBoolean(false)
        val root = temp.newFolder()
        val writes = AtomicInteger()
        val store = ReadingStateStore(root) { from, to ->
            check(!reject.get()) { "simulated storage failure" }
            java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            writes.incrementAndGet()
            Unit
        }
        store.save(namespace, score, ReadingState(page = 2))
        val first = Document { page ->
            if (page == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            Frame()
        }
        val opens = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewer = ScoreViewerController(scope, store)
        val source = ViewerSource(namespace, score) { if (opens.getAndIncrement() == 0) first else Document() }
        try {
            viewer.open(source); await { viewer.state.value.frame != null && writes.get() == 2 }
            reject.set(true); viewer.page(-1)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            viewer.transform(2f, .2f, -.3f); viewer.close(); viewer.open(source)
            release.countDown()
            await { !viewer.state.value.loading && viewer.state.value.frame != null }
            assertEquals(2, viewer.state.value.reading.page)
            assertEquals(ReadingState(page = 2), ReadingStateStore(root).load(namespace, score).reading)
            assertTrue(viewer.state.value.message!!.contains("Could not save reading position"))
        } finally { release.countDown(); viewer.shutdown(); scope.cancel() }
    }

}
