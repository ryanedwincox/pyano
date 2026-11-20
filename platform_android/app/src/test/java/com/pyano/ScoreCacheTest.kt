// Concern: verifies durable private PDF cache boundaries | Non-concern: cloud authentication | IO: (filesystem failures) -> assertions
package com.pyano

import com.pyano.scores.*
import com.pyano.sync.CloudCredentials
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ScoreCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val pdf = "%PDF-1.4\nprivate test fixture\n%%EOF".toByteArray()
    private fun score(bytes: ByteArray = pdf, version: String = "1") = Score("score_1", "Nocturne", version, bytes.size.toLong(), scoreHash(bytes))
    private fun profile(id: String = "profile_a", owner: String = "owner_a", endpoint: String = "https://music.example.com") =
        ScoreProfile(id, CloudCredentials(endpoint, owner, "fixture-only-token"))

    @Test fun reconstructsOfflineAndRetainsOldVersionsAndRemovedRemoteScores() = runBlocking {
        val root = temp.newFolder()
        val cache = ScoreCache(root, profile())
        val old = score()
        cache.updateRemote(listOf(old))
        cache.download(old) { it.write(pdf) }
        val newer = score("%PDF-1.5\nnew\n%%EOF".toByteArray(), "2")
        cache.updateRemote(listOf(newer))
        val restarted = ScoreCache(root, profile())
        val rows = restarted.rows()
        assertEquals(2, rows.size)
        assertTrue(rows.single { it.score == old }.status.contains("older version"))
        assertTrue(rows.single { it.score == old }.downloaded)
        cache.updateRemote(emptyList())
        assertTrue(ScoreCache(root, profile()).rows().single().status.contains("removed from Cloud"))
        assertArrayEquals(pdf, restarted.localFile(old)!!.readBytes())
    }

    @Test fun ownershipNamespacesAndHashedPathsCannotCrossProfilesOwnersOrOrigins() = runBlocking {
        val root = temp.newFolder()
        val cache = ScoreCache(root, profile())
        cache.updateRemote(listOf(score())); cache.download(score()) { it.write(pdf) }
        for (other in listOf(profile("profile_b"), profile(owner = "owner_b"), profile(endpoint = "https://other.example.com"))) {
            assertTrue(ScoreCache(root, other).rows().isEmpty())
            assertNull(ScoreCache(root, other).localFile(score()))
        }
        assertTrue(cache.localFile(score())!!.canonicalPath.startsWith(File(root, profile().namespace).canonicalPath + File.separator))
        val noBackup = File(root, "no-backup")
        assertEquals(File(noBackup, "scores"), scoreCacheRoot(noBackup))
    }

    @Test fun mismatchTruncationAndNonPdfNeverPublishOrLeavePartFiles() = runBlocking {
        val root = temp.newFolder()
        val cache = ScoreCache(root, profile())
        cache.updateRemote(listOf(score()))
        for (bytes in listOf(pdf.dropLast(1).toByteArray(), pdf + byteArrayOf(1), "other fixture without pdf".toByteArray())) {
            try { cache.download(score()) { it.write(bytes) }; fail("invalid PDF accepted") } catch (_: ScoreFailure) { }
            assertFalse(cache.rows().single().downloaded)
            assertNull(cache.localFile(score()))
            assertFalse(root.walkTopDown().any { it.extension == "part" })
        }
    }

    @Test fun failedManifestAfterSameVersionChecksumChangePreservesReferencedBytes() = runBlocking {
        val root = temp.newFolder()
        val original = ScoreCache(root, profile())
        original.updateRemote(listOf(score())); original.download(score()) { it.write(pdf) }
        val replacementBytes = "%PDF-1.4\nsame version changed content\n%%EOF".toByteArray()
        val replacement = score(replacementBytes)
        val failing = ScoreCache(root, profile()) { source, destination ->
            if (destination.name == "manifest.json") throw IOException("synthetic disk failure")
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        }
        try { failing.download(replacement) { it.write(replacementBytes) }; fail("publication succeeded") } catch (_: IOException) { }
        assertArrayEquals(pdf, ScoreCache(root, profile()).localFile(score())!!.readBytes())
        assertNull(ScoreCache(root, profile()).localFile(replacement))
        assertFalse(root.walkTopDown().any { it.extension == "part" })
        assertEquals(1, root.walkTopDown().count { it.extension == "pdf" })
    }

    @Test fun cancellationAndInterruptedTransferCleanTemporaryFiles() = runBlocking {
        val root = temp.newFolder()
        val cache = ScoreCache(root, profile())
        cache.updateRemote(listOf(score()))
        val started = CompletableDeferred<Unit>()
        val work = launch(Dispatchers.IO) { cache.download(score()) { output -> output.write(pdf, 0, 5); started.complete(Unit); awaitCancellation() } }
        started.await(); work.cancelAndJoin()
        assertFalse(cache.rows().single().downloaded)
        assertFalse(root.walkTopDown().any { it.extension == "part" || it.extension == "pdf" })
        try { cache.download(score()) { throw IOException("disconnect") }; fail("transfer completed") } catch (_: IOException) { }
        assertFalse(root.walkTopDown().any { it.extension == "part" })
    }

    @Test fun missingCorruptAndExplicitRemovalRemainLocalOnly() = runBlocking {
        val root = temp.newFolder()
        val cache = ScoreCache(root, profile())
        cache.updateRemote(listOf(score())); cache.download(score()) { it.write(pdf) }
        cache.localFile(score())!!.writeText("corrupt")
        assertFalse(cache.rows().single().downloaded)
        assertTrue(cache.rows().single().localCopy)
        cache.download(score()) { it.write(pdf) }
        assertTrue(cache.rows().single().downloaded)
        cache.remove(score())
        assertFalse(cache.rows().single().downloaded)
        assertFalse(cache.rows().single().localCopy)
        assertNull(cache.localFile(score()))
        assertEquals("score_1", cache.rows().single().score.scoreId)
    }

    @Test fun staleUnreferencedFilesCleanButCorruptManifestNeverDeletesPdfs() = runBlocking {
        val root = temp.newFolder()
        val cache = ScoreCache(root, profile())
        cache.updateRemote(listOf(score())); cache.download(score()) { it.write(pdf) }
        val directory = File(root, profile().namespace)
        File(directory, "interrupted.part").writeText("partial")
        File(directory, "unpublished.pdf").writeBytes(pdf)
        assertTrue(cache.rows().single().downloaded)
        assertFalse(File(directory, "interrupted.part").exists())
        assertFalse(File(directory, "unpublished.pdf").exists())
        File(directory, "manifest.json").writeText("broken")
        try { cache.rows(); fail("corrupt manifest accepted") } catch (_: ScoreFailure) { }
        assertTrue(File(directory, "${score().identity}.pdf").exists())
    }
    @Test fun oversizedUtf8ManifestCannotReplaceReadableIndexOrExistingPdf() = runBlocking {
        val root = temp.newFolder()
        val selected = profile()
        val cache = ScoreCache(root, selected)
        cache.updateRemote(listOf(score())); cache.download(score()) { it.write(pdf) }
        val manifest = File(File(root, selected.namespace), "manifest.json")
        val json = parseScoreJson(manifest.readText())
        val large = (1..10_000).map { index ->
            Score("s" + index.toString().padStart(127, '0'), "字".repeat(200), "v".repeat(128), pdf.size.toLong(), scoreHash(pdf))
        }
        val cached = json.getAsJsonArray("cached")
        large.take(9999).forEach { cached.add(scoreJson(it)) }
        manifest.writeText(json.toString(), Charsets.UTF_8)
        val before = manifest.readBytes()
        assertTrue(before.size < MAX_SCORE_MANIFEST_BYTES)
        try { cache.updateRemote(large); fail("oversized index published") }
        catch (e: ScoreFailure) { assertTrue(e.message!!.contains("index is full")) }
        assertEquals(scoreHash(before), scoreHash(manifest.readBytes()))
        assertArrayEquals(pdf, ScoreCache(root, selected).localFile(score())!!.readBytes())
        assertFalse(root.walkTopDown().any { it.extension == "part" })
    }

    @Test fun nonPdfWithMatchingSizeAndChecksumStillFailsSignatureValidation() = runBlocking {
        val root = temp.newFolder()
        val bytes = "plain text with a valid matching checksum".toByteArray()
        val invalid = score(bytes)
        val cache = ScoreCache(root, profile())
        cache.updateRemote(listOf(invalid))
        try { cache.download(invalid) { it.write(bytes) }; fail("non-PDF accepted") } catch (_: ScoreFailure) { }
        assertFalse(cache.rows().single().downloaded)
        assertNull(cache.localFile(invalid))
        assertFalse(root.walkTopDown().any { it.extension == "pdf" || it.extension == "part" })
    }

}
