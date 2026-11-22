// Concern: verifies safe title suggestions and duplicate takes | Non-concern: synth capture | IO: (names and existing files) -> assertions
package com.pyano.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RecordingNamesTest {
    @Test fun suggestionsAreSafeAndEditableNamesRejectTraversal() {
        assertEquals("Star Wars", RecordingNames.suggestion("Star Wars"))
        assertEquals("Suite  Part 1", RecordingNames.suggestion("Suite: Part 1"))
        for (name in listOf("", "..", "../take", "a\nb", "a".repeat(201))) assertFalse(RecordingNames.valid(name))
        assertTrue(RecordingNames.valid(RecordingNames.suggestion(null)))
        assertFalse(RecordingNames.valid("曲".repeat(100)))
        assertTrue(RecordingNames.valid(RecordingNames.suggestion("曲".repeat(100))))
    }

    @Test fun collisionSuffixRespectsFilenameAndCloudTitleLimits() {
        val directory = Files.createTempDirectory("long-named-take").toFile()
        try {
            for (title in listOf("x".repeat(200), "曲".repeat(73), "🎹".repeat(55))) {
                directory.resolve("$title.wav").writeText("prior take")
                val destination = RecordingNames.destination(directory, title)
                assertTrue(RecordingNames.valid(destination.nameWithoutExtension))
                assertTrue(destination.nameWithoutExtension.endsWith(" (2)"))
            }
            assertTrue(RecordingNames.valid(RecordingNames.suggestion("a" + "🎹".repeat(100))))
        } finally { directory.deleteRecursively() }
    }

    @Test fun repeatedScoreTitlesNeverOverwriteAudioOrMidi() {
        val directory = Files.createTempDirectory("named-take").toFile()
        try {
            directory.resolve("Star Wars.wav").writeText("original")
            directory.resolve("Star Wars (2).mid").writeText("sidecar")
            assertEquals("Star Wars (3).wav", RecordingNames.destination(directory, " Star Wars ").name)
            assertEquals("original", directory.resolve("Star Wars.wav").readText())
        } finally { directory.deleteRecursively() }
    }
}
