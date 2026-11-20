// Concern: verifies score profile switching and failed refresh | Non-concern: UI rendering | IO: (concurrent operations) -> isolated state
package com.pyano

import com.pyano.scores.*
import com.pyano.sync.CloudCredentials
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScoreLibraryControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun profile(id: String) = ScoreProfile(id, CloudCredentials("https://music.example.com", "owner_$id", "fixture-token"))
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }

    @Test fun failedRefreshKeepsPersistedOfflineSnapshotAndSanitizesFailure() = runBlocking {
        val root = temp.newFolder()
        val selected = profile("a")
        val pdf = "%PDF-1.4\nfixture".toByteArray()
        val score = Score("s_1", "Offline piece", "1", pdf.size.toLong(), scoreHash(pdf))
        val cache = ScoreCache(root, selected)
        cache.updateRemote(listOf(score)); cache.download(score) { it.write(pdf) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ScoreLibraryController(root, scope, CloudScoreClient { _, _ -> throw IllegalStateException("secret-fixture") })
        try {
            controller.select(selected)
            waitFor { !controller.state.value.busy }
            assertTrue(controller.state.value.rows.single().downloaded)
            controller.refresh()
            waitFor { !controller.state.value.busy }
            assertTrue(controller.state.value.rows.single().downloaded)
            assertFalse(controller.state.value.message.contains("secret-fixture"))
            assertTrue(ScoreCache(root, selected).rows().single().downloaded)
        } finally { scope.cancel() }
    }

    @Test fun switchedProfileClearsOldRowsAndRejectsLateOperationCompletion() = runBlocking {
        val root = temp.newFolder()
        val first = profile("a")
        val next = profile("b")
        val score = Score("s_a", "Owner A title", "1", 5, "a".repeat(64))
        ScoreCache(root, first).updateRemote(listOf(score))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int { started.countDown(); release.await(5, TimeUnit.SECONDS); return -1 }
            override fun close() { release.countDown() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ScoreLibraryController(root, scope, CloudScoreClient { _, _ -> object : ScoreResponse {
            override val status = 200
            override val contentType = "application/json"
            override fun body() = input
            override fun close() = input.close()
        } })
        try {
            controller.select(first); waitFor { !controller.state.value.busy }
            assertEquals("Owner A title", controller.state.value.rows.single().score.title)
            controller.refresh()
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            controller.select(next)
            assertEquals("owner_b", controller.state.value.ownerId)
            assertTrue(controller.state.value.rows.isEmpty())
            waitFor { !controller.state.value.busy }
            delay(50)
            assertEquals(next.namespace, controller.state.value.namespace)
            assertTrue(controller.state.value.rows.isEmpty())
            controller.select(null)
            assertNull(controller.state.value.namespace)
            assertTrue(controller.state.value.rows.isEmpty())
            controller.cancel(); controller.reload()
            assertTrue(controller.state.value.message.contains("Choose a Cloud profile"))
        } finally { release.countDown(); scope.cancel() }
    }
    @Test fun malformedSecondPageNeverReplacesPriorRemoteSnapshot() = runBlocking {
        val root = temp.newFolder()
        val selected = profile("a")
        val original = Score("old", "Prior title", "1", 5, "a".repeat(64))
        ScoreCache(root, selected).updateRemote(listOf(original))
        var requests = 0
        val client = CloudScoreClient { _, _ ->
            val body = if (++requests == 1) """{"scores":[],"nextCursor":"next"}""" else "malformed"
            object : ScoreResponse {
                override val status = 200
                override val contentType = "application/json"
                override fun body() = ByteArrayInputStream(body.toByteArray())
                override fun close() { }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ScoreLibraryController(root, scope, client)
        try {
            controller.select(selected); waitFor { !controller.state.value.busy }
            controller.refresh(); waitFor { !controller.state.value.busy }
            assertEquals(2, requests)
            assertEquals(original, controller.state.value.rows.single().score)
            assertEquals(original, ScoreCache(root, selected).rows().single().score)
        } finally { scope.cancel() }
    }

    @Test fun cancelAfterPublicationReconcilesAuthoritativeOfflineRows() = runBlocking {
        val root = temp.newFolder()
        val selected = profile("a")
        val bytes = "%PDF-1.4\ncompleted before cancellation".toByteArray()
        val score = Score("completed", "Completed download", "1", bytes.size.toLong(), scoreHash(bytes))
        ScoreCache(root, selected).updateRemote(listOf(score))
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CloudScoreClient { _, _ -> object : ScoreResponse {
            override val status = 200
            override val contentType = "application/pdf"
            override fun body() = ByteArrayInputStream(bytes)
            override fun close() { }
        } }
        val controller = ScoreLibraryController(root, scope, client, cacheFactory = { directory, captured ->
            ScoreCache(directory, captured) { source, destination ->
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                if (destination.name == "manifest.json" && armed.compareAndSet(true, false)) {
                    committed.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                Unit
            }
        })
        try {
            controller.select(selected); waitFor { !controller.state.value.busy }
            assertFalse(controller.state.value.rows.single().downloaded)
            armed.set(true); controller.download(score)
            assertTrue(withContext(Dispatchers.IO) { committed.await(5, TimeUnit.SECONDS) })
            controller.cancel()
            release.countDown()
            waitFor { !controller.state.value.busy }
            assertTrue(controller.state.value.rows.single().downloaded)
            assertTrue(controller.state.value.message.contains("completed changes were kept"))
            assertArrayEquals(bytes, ScoreCache(root, selected).localFile(score)!!.readBytes())
        } finally { release.countDown(); scope.cancel() }
    }

}
