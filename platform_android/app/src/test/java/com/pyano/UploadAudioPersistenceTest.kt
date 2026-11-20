// Concern: verifies encoded retry persistence | Non-concern: hardware encoding | IO: none
package com.pyano

import com.pyano.sync.persistUploadAudio
import com.pyano.sync.boundedUploadError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UploadAudioPersistenceTest {
    @Test
    fun retryReusesEncodedBytesAfterInterruptedTransfer() = runBlocking {
        val root = Files.createTempDirectory("pyano-encoded-retry").toFile()
        try {
            val audio = root.resolve("upload.m4a")
            persistUploadAudio(audio) { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val before = audio.readBytes()
            persistUploadAudio(audio) { error("Retry must not re-encode") }
            assertArrayEquals(before, audio.readBytes())
            assertFalse(root.resolve("upload.m4a.part").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun interruptedEncodingIsNotPublished() = runBlocking {
        val root = Files.createTempDirectory("pyano-encoding-failure").toFile()
        try {
            val audio = root.resolve("upload.m4a")
            try {
                persistUploadAudio(audio) { it.writeBytes(byteArrayOf(1)); error("Interrupted") }
                fail("Encoding should throw")
            } catch (_: IllegalStateException) { }
            assertFalse(audio.exists())
            persistUploadAudio(audio) { it.writeBytes(byteArrayOf(2)) }
            assertArrayEquals(byteArrayOf(2), audio.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun serverErrorFitsWorkManagerOutput() {
        assertEquals(1000, boundedUploadError("x".repeat(12000)).length)
        assertEquals("Rejected", boundedUploadError("Rejected"))
    }
}
