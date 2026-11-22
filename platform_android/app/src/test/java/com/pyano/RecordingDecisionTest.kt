// Concern: verifies saving and discarding temporary audio takes | Non-concern: real synthesis | IO: (captured PCM and decision) -> files
package com.pyano

import android.content.Context
import android.content.SharedPreferences
import com.pyano.sync.RecordingCloudStatus
import com.pyano.sync.RecordingUploadScheduler
import com.pyano.sync.saveAndScheduleTake
import com.pyano.audio.AudioRecorder
import com.pyano.audio.FluidSynthEngine
import com.pyano.audio.MidiCapture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RecordingDecisionTest {
    @get:Rule val directory = TemporaryFolder()

    private class SimulatedProcessDeath : Error()

    private fun cloudStatusContext(): Pair<Context, MutableMap<String, Any>> {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val values = mutableMapOf<String, Any>()
        `when`(context.getSharedPreferences("recording_cloud_status", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getString(anyString(), isNull())).thenAnswer { values[it.getArgument<String>(0)] as? String }
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] as? Boolean ?: false }
        `when`(prefs.getStringSet(anyString(), anySet())).thenAnswer {
            @Suppress("UNCHECKED_CAST") (values[it.getArgument<String>(0)] as? Set<String>) ?: emptySet<String>()
        }
        `when`(editor.putString(anyString(), anyString())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<String>(1); editor
        }
        `when`(editor.putString(anyString(), isNull())).thenAnswer {
            values.remove(it.getArgument<String>(0)); editor
        }
        `when`(editor.putStringSet(anyString(), anySet())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument<Set<String>>(1).toSet(); editor
        }
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); editor }
        `when`(editor.remove(anyString())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
        `when`(editor.commit()).thenReturn(true)
        `when`(context.filesDir).thenReturn(directory.root)
        `when`(context.cacheDir).thenReturn(directory.root)
        return context to values
    }

    private suspend fun capture(scope: CoroutineScope,
        publicationFactory: (File, File) -> com.pyano.audio.RecordingTakePublication = { journals, output ->
            com.pyano.audio.RecordingTakePublication(journals, output) }): AudioRecorder {
        val context = mock(Context::class.java)
        `when`(context.cacheDir).thenReturn(directory.root)
        `when`(context.filesDir).thenReturn(directory.root)
        val engine = mock(FluidSynthEngine::class.java)
        val midi = mock(MidiCapture::class.java)
        `when`(engine.midiCapture).thenReturn(midi)
        `when`(engine.getRecordingSampleRate()).thenReturn(48000)
        `when`(midi.finish()).thenReturn(byteArrayOf(1, 2, 3))
        val drained = AtomicBoolean()
        `when`(engine.readRecordingBuffer((any(FloatArray::class.java) ?: FloatArray(0)), anyInt())).thenAnswer {
            if (drained.compareAndSet(false, true)) {
                val values = it.getArgument<FloatArray>(0)
                values[0] = 0.2f; values[1] = -0.2f
                2
            } else 0
        }
        val recorder = AudioRecorder(engine, context, publicationFactory) { File(directory.root, "saved") }
        recorder.startRecording(scope)
        withTimeout(2000) { while (recorder.recordingDurationMs == 0L && !drained.get()) delay(5) }
        assertTrue(recorder.finishTake())
        return recorder
    }

    @Test fun stopDoesNotPublishAndDiscardNeverEncodesWav() = runBlocking {
        val recorder = capture(this)
        assertTrue(File(directory.root, "pyano_recording_temp.pcm").exists())
        assertFalse(directory.root.walkTopDown().any { it.extension in setOf("wav", "mid") })
        recorder.discardTake()
        assertFalse(File(directory.root, "pyano_recording_temp.pcm").exists())
    }

    @Test fun savePublishesPairAndRetryDoesNotDuplicateAfterQueueFailure() = runBlocking {
        val recorder = capture(this)
        try { recorder.saveTake { error("queue unavailable") }; fail("Expected queue failure") } catch (_: IllegalStateException) { }
        val path = recorder.saveTake { assertFalse(File(it).exists()) }!!
        assertTrue(File(path).isFile)
        assertTrue(File(path.replace(".wav", ".mid")).isFile)
        assertEquals(1, directory.root.walkTopDown().count { it.extension == "wav" })
        assertFalse(File(directory.root, "pyano_recording_temp.pcm").exists())
    }
    @Test fun namedSavePublishesChosenNameBeforePendingIntentAndRetriesSameBundle() = runBlocking {
        val recorder = capture(this)
        try { recorder.saveTake("Star Wars") { assertEquals("Star Wars.wav", File(it).name); error("pending failed") } }
        catch (_: IllegalStateException) { }
        val path = recorder.saveTake("Star Wars") { assertEquals("Star Wars.wav", File(it).name) }!!
        assertEquals("Star Wars.wav", File(path).name)
        assertTrue(File(path).isFile)
        assertTrue(File(path.replace(".wav", ".mid")).isFile)
        assertEquals(1, directory.root.walkTopDown().count { it.extension == "wav" })
    }
    @Test fun rejectedUnpublishedNameCanBeChangedWithoutDuplicatingTake() = runBlocking {
        val recorder = capture(this)
        try { recorder.saveTake("Rejected name") { error("prepublication rejected") }; fail("Expected save failure") }
        catch (_: IllegalStateException) { }
        assertTrue(recorder.hasPendingTake)
        assertFalse(recorder.pendingTakeNameLocked)
        val result = checkNotNull(recorder.saveTake("Corrected name"))
        assertEquals("Corrected name.wav", File(result).name)
        assertEquals(1, File(directory.root, "saved").listFiles().orEmpty().count { it.extension == "wav" })
        assertFalse(File(directory.root, "saved/Rejected name.wav").exists())
    }

    @Test fun retryAfterFailedNamedSaveDoesNotAcceptForeignRecordingAtPendingPath() = runBlocking {
        val recorder = capture(this)
        var candidate = ""
        try { recorder.saveTake("Shared name") { candidate = it; error("pending failed") }; fail("Expected failure") }
        catch (_: IllegalStateException) { }
        val foreign = File(candidate)
        foreign.writeText("unrelated audio")
        val foreignMidi = File(candidate.replace(".wav", ".mid"))
        foreignMidi.writeText("unrelated MIDI")
        val result = recorder.saveTake("Shared name")!!
        assertNotEquals(candidate, result)
        assertEquals("Shared name (2).wav", File(result).name)
        assertEquals("unrelated audio", foreign.readText())
        assertEquals("unrelated MIDI", foreignMidi.readText())
    }

    @Test fun discardAfterFailedNamedSaveNeverDeletesForeignFilesAtPendingPath() = runBlocking {
        val recorder = capture(this)
        var candidate = ""
        try { recorder.saveTake("Shared name") { candidate = it; error("pending failed") }; fail("Expected failure") }
        catch (_: IllegalStateException) { }
        val foreign = File(candidate)
        foreign.writeText("unrelated audio")
        val foreignMidi = File(candidate.replace(".wav", ".mid"))
        foreignMidi.writeText("unrelated MIDI")
        recorder.discardTake()
        assertEquals("unrelated audio", foreign.readText())
        assertEquals("unrelated MIDI", foreignMidi.readText())
        assertFalse(recorder.hasPendingTake)
    }

    @Test fun publicationDoesNotReplaceFileCreatedAfterSaveCallback() = runBlocking {
        val recorder = capture(this)
        var candidate = ""
        try {
            recorder.saveTake("Collision") { candidate = it; File(it).writeText("foreign") }
            fail("Expected publication conflict")
        } catch (_: java.nio.file.FileAlreadyExistsException) { }
        recorder.discardTake()
        assertEquals("foreign", File(candidate).readText())
    }

    @Test fun failedPublicationIntentNeverQueuesForeignAudioAfterRestartOrDiscard() = runBlocking {
        val (context, values) = cloudStatusContext()
        val status = RecordingCloudStatus(context)
        val recorder = capture(this)
        var candidate = ""
        try {
            saveAndScheduleTake({ beforePublish ->
                recorder.saveTake("Collision", beforePublish) { candidate = it; File(it).writeText("foreign recording") }
            }, status, RecordingUploadScheduler(context, status), null)
            fail("Expected publication conflict")
        } catch (_: java.nio.file.FileAlreadyExistsException) { }
        val foreignId = status.id(candidate)
        val restored = RecordingCloudStatus(context)
        assertFalse(candidate in restored.pendingPaths())
        recorder.discardTake()
        assertEquals("foreign recording", File(candidate).readText())
        assertEquals(foreignId, restored.existingId(candidate))
        assertFalse(candidate in RecordingCloudStatus(context).pendingPaths())
        RecordingCloudStatus(context).requirePendingBundle(candidate)
        assertFalse(RecordingCloudStatus(context).unverifiedOwnerOrigin(foreignId))
        assertFalse(values.keys.any { it.startsWith("pending-") && it.endsWith(candidate) })
        values["pending"] = setOf("legacy-unverified.wav")
        assertEquals(setOf("legacy-unverified.wav"), RecordingCloudStatus(context).legacyPendingPaths())
        assertTrue(RecordingCloudStatus(context).pendingPaths().isEmpty())
    }

    @Test fun interruptedPublicationRecoversPairAndOriginalProductionUploadIntent() = runBlocking {
        val (context, _) = cloudStatusContext()
        val status = RecordingCloudStatus(context)
        val recorder = capture(this)
        try {
            saveAndScheduleTake({ beforePublish ->
                recorder.saveTake("Recovered", beforePublish) { throw SimulatedProcessDeath() }
            }, status, RecordingUploadScheduler(context, status), "original-profile")
            fail("Expected process interruption")
        } catch (_: SimulatedProcessDeath) { }
        assertTrue(recorder.pendingTakeNameLocked)
        val output = File(directory.root, "saved")
        assertFalse(File(output, "Recovered.wav").exists())
        assertTrue(File(directory.root, "take-publications").listFiles().orEmpty().any { it.extension == "journal" })
        assertEquals(2, output.listFiles().orEmpty().count { it.extension == "part" })
        val restarted = AudioRecorder(mock(FluidSynthEngine::class.java), context) { output }
        val recovered = restarted.getSavedRecordings().single()
        assertEquals("Recovered", recovered.name)
        assertTrue(File(output, "Recovered.mid").isFile)
        val restored = RecordingCloudStatus(context)
        assertEquals(setOf(recovered.path), restored.pendingPaths())
        assertEquals("original-profile", restored.pendingProfile(recovered.path))
        assertTrue(File(directory.root, "take-publications").listFiles().orEmpty().isEmpty())
        assertFalse(output.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test fun processDeathBeforeUploadIntentLeavesVisibleRecoverableDraftWithoutChoosingCloudOwner() = runBlocking {
        val (context, _) = cloudStatusContext()
        val recorder = capture(this)
        try {
            recorder.saveTake("Draft title", com.pyano.audio.TakePublicationIntent({ _, _, _ -> throw SimulatedProcessDeath() }))
            fail("Expected process interruption")
        } catch (_: SimulatedProcessDeath) { }
        val output = File(directory.root, "saved")
        val drafts = File(directory.root, "take-publications")
        assertEquals(1, drafts.listFiles().orEmpty().count { it.extension == "draft" })
        assertFalse(drafts.listFiles().orEmpty().any { it.extension == "journal" })
        assertEquals(2, output.listFiles().orEmpty().count { it.extension == "part" })
        val draft = drafts.listFiles().orEmpty().single { it.extension == "draft" }
        val stagedAudio = output.listFiles().orEmpty().single { it.name.startsWith(".pyano-take-") && !it.name.startsWith(".pyano-take-midi-") }
        File(drafts, draft.nameWithoutExtension + ".target").writeText(
            "wav.target=Draft title.wav\nwav.identity=${com.pyano.audio.recordingFileProof(stagedAudio)}\n")
        val restarted = AudioRecorder(mock(FluidSynthEngine::class.java), context) { output }
        assertTrue(restarted.getSavedRecordings().isEmpty())
        assertNotNull(restarted.publicationRecoveryWarning)
        assertNotNull(restarted.publicationRecoveryKey)
        restarted.recoverSavedTakes(recoverConflicts = true)
        val saved = restarted.getSavedRecordings().single()
        assertEquals("Draft title", saved.name)
        assertTrue(File(output, "Draft title.mid").isFile)
        assertTrue(RecordingCloudStatus(context).pendingPaths().isEmpty())
        assertTrue(drafts.listFiles().orEmpty().isEmpty())
    }

    @Test fun processDeathAfterUploadIntentBeforeJournalPreservesOriginalOwnerThroughExplicitRecovery() = runBlocking {
        val (context, _) = cloudStatusContext()
        val status = RecordingCloudStatus(context)
        val recorder = capture(this)
        try {
            recorder.saveTake("Bound draft", com.pyano.audio.TakePublicationIntent({ path, wav, midi ->
                status.pending(path, wav, "original-profile", midi)
                throw SimulatedProcessDeath()
            }))
            fail("Expected process interruption")
        } catch (_: SimulatedProcessDeath) { }
        val output = File(directory.root, "saved")
        val restarted = AudioRecorder(mock(FluidSynthEngine::class.java), context) { output }
        assertTrue(restarted.getSavedRecordings().isEmpty())
        restarted.recoverSavedTakes(recoverConflicts = true)
        val saved = restarted.getSavedRecordings().single()
        val restored = RecordingCloudStatus(context)
        assertEquals(setOf(saved.path), restored.pendingPaths())
        assertEquals("original-profile", restored.pendingProfile(saved.path))
        restored.requirePendingBundle(saved.path)
    }

    @Test fun corruptPublicationDescriptorQuarantinesOnlyItsKnownPartialTake() = runBlocking {
        val (context, _) = cloudStatusContext()
        val recorder = capture(this)
        val healthy = File(checkNotNull(recorder.saveTake("Healthy")))
        val output = healthy.parentFile
        val journals = File(directory.root, "take-publications")
        val staged = healthy.copyTo(File(output, ".pyano-take-partial.part"))
        val midi = File(output, ".pyano-take-midi-partial.part").apply { writeText("MIDI") }
        val incomplete = File(output, "Incomplete.wav")
        val publisher = com.pyano.audio.RecordingTakePublication(journals, output)
        val journal = publisher.prepare(staged, midi, incomplete)
        val status = RecordingCloudStatus(context)
        status.pending(incomplete.absolutePath, com.pyano.audio.recordingFileProof(staged), "profile-a",
            com.pyano.audio.recordingFileProof(midi))
        java.nio.file.Files.move(staged.toPath(), incomplete.toPath())
        journal.writeText("\\uZZZZ")
        val restarted = AudioRecorder(mock(FluidSynthEngine::class.java), context) { output }
        val listed = restarted.getSavedRecordings()
        assertEquals(listOf(healthy.absolutePath), listed.map { it.path })
        assertTrue(healthy.isFile && healthy.canRead())
        assertTrue(com.pyano.audio.readWavHeader(healthy).dataSize > 0)
        assertTrue(incomplete.isFile)
        assertNotNull(restarted.publicationRecoveryWarning)
        assertTrue(listed.filter { it.path in status.pendingPaths() }.isEmpty())
        assertTrue(incomplete.absolutePath in publisher.unresolvedTargets())
        assertFalse(healthy.absolutePath in publisher.unresolvedTargets())
        assertFalse(restarted.automaticUploadBlocked)
        File(journals, "unknown.journal").writeText("\\uZZZZ")
        assertEquals(listOf(healthy.absolutePath), restarted.getSavedRecordings().map { it.path })
        assertTrue(restarted.automaticUploadBlocked)
    }

    @Test fun discardCannotClearTakeUntilAbortMarkerIsDurable() = runBlocking {
        val (context, _) = cloudStatusContext()
        val status = RecordingCloudStatus(context)
        val rejectAbort = AtomicBoolean(true)
        val recorder = capture(this) { journals, output ->
            com.pyano.audio.RecordingTakePublication(journals, output, beforeAbort = {
                if (rejectAbort.get()) error("abort journal write failed")
            })
        }
        try {
            saveAndScheduleTake({ beforePublish -> recorder.saveTake("Discard me", beforePublish) { error("save failed") } },
                status, RecordingUploadScheduler(context, status), "original-profile")
            fail("Expected save and abort failure")
        } catch (_: IllegalStateException) { }
        assertTrue(recorder.getSavedRecordings().isEmpty())
        assertTrue(recorder.pendingTakeNameLocked)
        assertFalse(File(directory.root, "saved/Discard me.wav").exists())
        assertTrue(recorder.hasPendingTake)
        try { recorder.discardTake(); fail("Expected discard refusal") }
        catch (_: IllegalStateException) { }
        assertTrue(recorder.hasPendingTake)
        assertTrue(File(directory.root, "pyano_recording_temp.pcm").isFile)
        assertTrue(File(directory.root, "take-publications").listFiles().orEmpty().any { it.extension == "journal" })
        rejectAbort.set(false)
        recorder.discardTake()
        assertFalse(recorder.hasPendingTake)
        val restarted = AudioRecorder(mock(FluidSynthEngine::class.java), context) { File(directory.root, "saved") }
        assertTrue(restarted.getSavedRecordings().isEmpty())
        assertTrue(RecordingCloudStatus(context).pendingPaths().isEmpty())
        assertFalse(File(directory.root, "saved").listFiles().orEmpty().any { it.extension in setOf("wav", "mid", "part") })
    }

    @Test fun retrySaveResolvesExistingJournalBeforePreparingAnotherPublication() = runBlocking {
        val (context, _) = cloudStatusContext()
        val status = RecordingCloudStatus(context)
        val rejectAbort = AtomicBoolean(true)
        val recorder = capture(this) { journals, output ->
            com.pyano.audio.RecordingTakePublication(journals, output, beforeAbort = {
                if (rejectAbort.get()) error("abort journal write failed")
            })
        }
        val scheduler = RecordingUploadScheduler(context, status)
        try {
            saveAndScheduleTake({ beforePublish -> recorder.saveTake("Retry", beforePublish) { error("save failed") } },
                status, scheduler, null)
            fail("Expected save failure")
        } catch (_: IllegalStateException) { }
        val journals = File(directory.root, "take-publications")
        val original = journals.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(1, original.count { it.endsWith(".journal") })
        try {
            saveAndScheduleTake({ beforePublish -> recorder.saveTake("Retry", beforePublish) }, status, scheduler, null)
            fail("Retry must refuse a second publication while abort fails")
        } catch (_: IllegalStateException) { }
        assertEquals(original, journals.listFiles().orEmpty().map { it.name }.toSet())
        assertTrue(recorder.hasPendingTake)
        rejectAbort.set(false)
        val saved = saveAndScheduleTake({ beforePublish -> recorder.saveTake("Retry", beforePublish) }, status, scheduler, null)
        assertEquals("Retry.wav", File(saved.path).name)
        assertEquals(listOf(saved.path), recorder.getSavedRecordings().map { it.path })
        assertTrue(com.pyano.audio.RecordingBundleFiles.midi(File(saved.path)).isFile)
        assertTrue(journals.listFiles().orEmpty().isEmpty())
        assertFalse(File(directory.root, "saved").listFiles().orEmpty().any { it.extension == "part" })
        assertEquals(setOf(saved.path), status.pendingPaths())
    }

    @Test fun deferredTakeRejectsChangedOrMissingMidiBeforeAnyUploadPreflight() = runBlocking {
        val (context, values) = cloudStatusContext()
        val prefs = context.getSharedPreferences("recording_cloud_status", Context.MODE_PRIVATE)
        `when`(context.getSharedPreferences("recording_title_sync", Context.MODE_PRIVATE)).thenReturn(prefs)
        val status = RecordingCloudStatus(context)
        val recorder = capture(this)
        val saved = saveAndScheduleTake({ beforePublish -> recorder.saveTake("Deferred", beforePublish) }, status,
            RecordingUploadScheduler(context, status), null)
        assertEquals(setOf(saved.path), status.pendingPaths())
        val midi = com.pyano.audio.RecordingBundleFiles.midi(File(saved.path))
        midi.writeText("externally replaced MIDI")
        assertTrue(status.pendingPaths().isEmpty())
        assertTrue(status.pendingBundleWarnings().getValue(saved.path).contains("MIDI"))
        var credentialLookup = false
        val scheduler = RecordingUploadScheduler(context, status) { credentialLookup = true; true }
        val info = com.pyano.audio.RecordingInfo("Deferred", saved.path, 10, 0, File(saved.path).length())
        try { scheduler.enqueue(info, "profile-a"); fail("Expected original-pair rejection") }
        catch (error: com.pyano.sync.CloudSyncException) { assertTrue(error.message!!.contains("MIDI")); assertFalse(error.retryable) }
        assertFalse(credentialLookup)
        assertFalse(values.keys.any { it.startsWith("synced:") || it.endsWith(".remote") })
        assertFalse(File(directory.root, "cloud-upload-queue").exists())
        assertTrue(midi.delete())
        assertTrue(status.pendingBundleWarnings().getValue(saved.path).contains("MIDI"))
        try { status.requirePendingBundle(saved.path); fail("Missing captured MIDI must stay rejected") }
        catch (error: com.pyano.sync.CloudSyncException) { assertTrue(error.message!!.contains("MIDI")) }
    }

    @Test fun pendingPairChangedBetweenVerificationAndSnapshotNeverBindsOrLeavesNewSnapshot() = runBlocking {
        val (context, values) = cloudStatusContext()
        val status = RecordingCloudStatus(context)
        val recorder = capture(this)
        val saved = saveAndScheduleTake({ intent -> recorder.saveTake("Snapshot race", intent) }, status,
            RecordingUploadScheduler(context, status), null)
        val wav = File(saved.path)
        val midi = com.pyano.audio.RecordingBundleFiles.midi(wav)
        val original = midi.readBytes()
        val root = directory.newFolder("pair-race-queue")
        val queue = com.pyano.sync.CloudUploadQueue(root)
        assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            com.pyano.sync.verifiedPendingSnapshot(queue, status, wav, "profile", "new-race") { midi.writeText("replacement") }
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertFalse(values.keys.any { it.endsWith(".remote") || it.startsWith("synced:") })
        midi.writeBytes(original)
        status.pending(wav.absolutePath)
        val frozen = queue.snapshot(wav, "profile", "existing-race")
        assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            com.pyano.sync.verifiedPendingSnapshot(queue, status, wav, "profile", "existing-race") { midi.writeText("second replacement") }
        }
        assertTrue(frozen.wav.isFile)
        assertArrayEquals(original, com.pyano.audio.RecordingBundleFiles.midi(frozen.wav).readBytes())
    }

    @Test fun explicitRecoveryCompletesInterruptedPairUnderNewNameWithOriginalCloudOwner() = runBlocking {
        val (context, _) = cloudStatusContext()
        val initial = capture(this)
        val seed = File(checkNotNull(initial.saveTake("Seed")))
        val originalAudio = seed.readBytes()
        val originalMidi = com.pyano.audio.RecordingBundleFiles.midi(seed).readBytes()
        val output = seed.parentFile
        val staged = File(output, ".pyano-take-recovery.part")
        val stagedMidi = File(output, ".pyano-take-midi-recovery.part")
        check(seed.renameTo(staged))
        check(com.pyano.audio.RecordingBundleFiles.midi(seed).renameTo(stagedMidi))
        val destination = File(output, "Practice.wav")
        val status = RecordingCloudStatus(context)
        status.pending(destination.path, com.pyano.audio.recordingFileProof(staged), "original-owner-profile",
            com.pyano.audio.recordingFileProof(stagedMidi))
        val journals = File(directory.root, "take-publications")
        val publisher = com.pyano.audio.RecordingTakePublication(journals, output)
        val journal = publisher.prepare(staged, stagedMidi, destination)
        try { publisher.publish(journal) { throw SimulatedProcessDeath() }; fail("Expected interrupted move") }
        catch (_: SimulatedProcessDeath) { }
        destination.writeBytes(originalAudio)
        val foreignId = status.id(destination.path)
        status.complete(foreignId)
        val restarted = AudioRecorder(mock(FluidSynthEngine::class.java), context) { output }
        assertEquals(listOf(destination.path), restarted.getSavedRecordings().map { it.path })
        assertTrue(com.pyano.audio.readWavHeader(destination).dataSize > 0)
        assertNotNull(restarted.publicationRecoveryWarning)
        assertThrows(IllegalStateException::class.java) { restarted.deleteRecording(destination.path) }
        assertThrows(IllegalStateException::class.java) { restarted.renameRecording(destination.path, "Foreign renamed") }
        assertArrayEquals(originalAudio, destination.readBytes())
        assertArrayEquals(originalMidi, com.pyano.audio.RecordingBundleFiles.midi(destination).readBytes())
        restarted.recoverSavedTakes(true, status::retargetPending)
        assertNull(restarted.publicationRecoveryWarning)
        val recovered = File(output, "Practice (2).wav")
        assertArrayEquals(originalAudio, recovered.readBytes())
        assertArrayEquals(originalMidi, com.pyano.audio.RecordingBundleFiles.midi(recovered).readBytes())
        assertArrayEquals(originalAudio, destination.readBytes())
        assertEquals(foreignId, status.existingId(destination.path))
        assertTrue(status.synced(foreignId))
        assertEquals(setOf(recovered.path), status.pendingPaths())
        assertEquals("original-owner-profile", status.pendingProfile(recovered.path))
        assertTrue(journals.listFiles().orEmpty().isEmpty())
        assertFalse(output.listFiles().orEmpty().any { it.extension == "part" })
        assertEquals(setOf(destination.path, recovered.path), restarted.getSavedRecordings().map { it.path }.toSet())
    }

    @Test fun recoveryRetargetIntentAndSameJournalReplaySafelyAfterProcessInterruption() = runBlocking {
        val (context, _) = cloudStatusContext()
        val initial = capture(this)
        val seed = File(checkNotNull(initial.saveTake("Seed")))
        val output = seed.parentFile
        val staged = File(output, ".pyano-take-recovery.part")
        val stagedMidi = File(output, ".pyano-take-midi-recovery.part")
        check(seed.renameTo(staged))
        check(com.pyano.audio.RecordingBundleFiles.midi(seed).renameTo(stagedMidi))
        val destination = File(output, "Practice.wav")
        val status = RecordingCloudStatus(context)
        status.pending(destination.path, com.pyano.audio.recordingFileProof(staged), "original-owner-profile",
            com.pyano.audio.recordingFileProof(stagedMidi))
        val journals = File(directory.root, "take-publications")
        val publisher = com.pyano.audio.RecordingTakePublication(journals, output)
        publisher.prepare(staged, stagedMidi, destination)
        destination.writeText("foreign audio")
        val foreignMidi = com.pyano.audio.RecordingBundleFiles.midi(destination).apply { writeText("foreign MIDI") }
        var newPath = ""
        try {
            publisher.recover(true, { old, new, wav, midi ->
                status.retargetPending(old, new, wav, midi)
                newPath = new
                throw SimulatedProcessDeath()
            })
            fail("Expected interruption after durable intent moved")
        } catch (_: SimulatedProcessDeath) { }
        assertEquals(1, journals.listFiles().orEmpty().count { it.extension == "journal" })
        assertFalse(File(newPath).exists())
        assertNotNull(com.pyano.audio.RecordingTakePublication(journals, output).recover())
        val restarted = AudioRecorder(mock(FluidSynthEngine::class.java), context) { output }
        restarted.recoverSavedTakes(true, status::retargetPending)
        assertNull(restarted.publicationRecoveryWarning)
        assertTrue(File(newPath).isFile)
        assertTrue(com.pyano.audio.RecordingBundleFiles.midi(File(newPath)).isFile)
        assertEquals("foreign audio", destination.readText())
        assertEquals("foreign MIDI", foreignMidi.readText())
        assertEquals(setOf(newPath), status.pendingPaths())
        assertEquals("original-owner-profile", status.pendingProfile(newPath))
        assertTrue(journals.listFiles().orEmpty().isEmpty())
    }

    @Test fun clearingOneRecoveryWarningNeverDismissesADifferentUnresolvedTake() {
        val (context, _) = cloudStatusContext()
        val output = File(directory.root, "saved").apply { mkdirs() }
        val journals = File(directory.root, "take-publications")
        val publisher = com.pyano.audio.RecordingTakePublication(journals, output)
        fun conflict(name: String) {
            val staged = File.createTempFile(".pyano-take-", ".part", output).apply { writeText("saved take") }
            val foreign = File(output, "$name.wav").apply { writeText("unrelated recording") }
            publisher.prepare(staged, null, foreign)
        }
        fun restarted() = AudioRecorder(mock(FluidSynthEngine::class.java), context) { output }.apply { recoverSavedTakes() }
        conflict("First")
        val first = restarted()
        assertNotNull(first.publicationRecoveryWarning)
        val firstKey = checkNotNull(first.publicationRecoveryKey)
        val prefix = "take-publication-recovery:"
        val status = RecordingCloudStatus(context)
        status.dismissWarning(prefix + firstKey)
        val same = restarted()
        assertEquals(firstKey, same.publicationRecoveryKey)
        assertTrue(RecordingCloudStatus(context).warningDismissed(prefix + checkNotNull(same.publicationRecoveryKey)))
        conflict("Second")
        val next = restarted()
        val nextKey = checkNotNull(next.publicationRecoveryKey)
        assertNotEquals(firstKey, nextKey)
        assertFalse(RecordingCloudStatus(context).warningDismissed(prefix + nextKey))
        assertEquals(nextKey, restarted().publicationRecoveryKey)
        assertEquals(first.publicationRecoveryWarning, next.publicationRecoveryWarning)
        assertEquals("unrelated recording", File(output, "First.wav").readText())
        assertEquals("unrelated recording", File(output, "Second.wav").readText())
    }

    @Test fun failedFinalDrainStillAllowsDiscardAndAnotherTake() = runBlocking {
        val recorder = capture(this)
        val pcm = File(directory.root, "pyano_recording_temp.pcm")
        assertTrue(pcm.delete())
        assertTrue(pcm.mkdir())
        try { recorder.finishTake(); fail("Expected final drain failure") } catch (_: java.io.IOException) { }
        assertTrue(recorder.hasPendingTake)
        recorder.discardTake()
        assertFalse(recorder.hasPendingTake)
    }

    @Test fun savedTakeReportsAutomaticQueueFailureAsVisibleWarningWithoutLosingRecording() = runBlocking {
        val recorder = capture(this)
        val status = mock(RecordingCloudStatus::class.java)
        val scheduler = RecordingUploadScheduler(mock(Context::class.java), status) { false }
        var opened: com.pyano.audio.RecordingInfo? = null
        val result = saveAndScheduleTake({ beforePublish -> recorder.saveTake("Queue failure", beforePublish) },
            status, scheduler, "owner-a", onSaved = { opened = it })
        assertTrue(File(result.path).isFile)
        val mastering = checkNotNull(opened)
        assertEquals("Queue failure", mastering.name)
        assertEquals(result.path, mastering.path)
        assertEquals(File(result.path).length(), mastering.sizeBytes)
        assertTrue(result.warning!!.contains("reconfigure Cloud"))
        assertFalse(recorder.hasPendingTake)
    }

    @Test fun deliberatelyLocalSaveDoesNotProduceQueueFailureWarning() = runBlocking {
        val recorder = capture(this)
        val status = mock(RecordingCloudStatus::class.java)
        val scheduler = RecordingUploadScheduler(mock(Context::class.java), status) { false }
        var opened: com.pyano.audio.RecordingInfo? = null
        val result = saveAndScheduleTake({ beforePublish -> recorder.saveTake("Local take", beforePublish) },
            status, scheduler, null, onSaved = { opened = it })
        assertTrue(File(result.path).isFile)
        val mastering = checkNotNull(opened)
        assertEquals("Local take", mastering.name)
        assertEquals(result.path, mastering.path)
        assertEquals(File(result.path).length(), mastering.sizeBytes)
        assertNull(result.warning)
    }

    @Test fun failedSaveDoesNotOpenMasteringAndFailedHandoffKeepsBothWarnings() = runBlocking<Unit> {
        var opened = false
        val status = mock(RecordingCloudStatus::class.java)
        val scheduler = RecordingUploadScheduler(mock(Context::class.java), status) { false }
        try {
            saveAndScheduleTake({ throw java.io.IOException("Save failed") }, status, scheduler, null, onSaved = { opened = true })
            fail("Expected save failure")
        } catch (_: java.io.IOException) { }
        assertFalse(opened)
        val result = saveAndScheduleTake({ directory.root.resolve("removed-after-save.wav").path },
            status, scheduler, "profile", onSaved = { opened = true })
        assertFalse(opened)
        assertTrue(result.warning!!.contains("cloud upload could not be queued"))
        assertTrue(result.warning!!.contains("mastering could not open"))
    }

}
