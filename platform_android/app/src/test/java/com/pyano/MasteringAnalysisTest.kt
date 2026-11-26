// Concern: verifies loudness matching, linked dynamics and safe offline exports | Non-concern: hardware playback | IO: (synthetic WAVs) -> assertions
package com.pyano

import com.pyano.audio.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MasteringAnalysisTest {
    @get:Rule val directory = TemporaryFolder()
    private fun wave(name: String, seconds: Int = 3, rate: Int = 48000, sample: (Int) -> Pair<Float, Float>): File {
        val frames = seconds * rate
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray()).putInt(16)
        bytes.putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { index ->
            val (left, right) = sample(index)
            bytes.putShort((left * 32767).toInt().toShort()).putShort((right * 32767).toInt().toShort())
        }
        return directory.newFile(name).apply { writeBytes(bytes.array()) }
    }
    private fun tone(name: String, amplitude: Float, rate: Int = 48000) = wave(name, rate = rate) {
        val value = (sin(2 * PI * 1000 * it / rate) * amplitude).toFloat()
        value to value
    }

    @Test fun gatedLoudnessMatchesStereoCalibrationAndRejectsSilence() = runBlocking<Unit> {
        for (rate in listOf(44100, 48000)) {
            val measured = measureMastering(tone("tone-$rate.wav", .1f, rate), null)
            assertEquals(-20f, measured.lufs!!, .15f)
        }
        assertNull(measureMastering(wave("silence.wav") { 0f to 0f }, null).lufs)
        val meter = LoudnessMeter(48000)
        repeat(48000 * 8) { index ->
            val sample = if (index < 48000 * 4) (.1 * sin(2 * PI * 1000 * index / 48000)).toFloat() else 0f
            meter.add(sample, sample)
        }
        assertEquals(-20f, meter.integrated()!!, .3f)
    }

    @Test fun defaultMatchesQuietAndLoudRecordingsAndAllPresetsStayLimited() = runBlocking<Unit> {
        for ((index, amplitude) in listOf(.02f, .65f).withIndex()) {
            val file = tone("level-$index.wav", amplitude)
            val original = file.readBytes()
            val plan = analyzeMastering(file, MasteringSettings())
            assertEquals(-20f, plan.after.lufs!!, .3f)
            assertTrue(plan.after.peakDb <= -3f + .01f)
            assertTrue(plan.after.points.size <= 1000)
            assertArrayEquals(original, file.readBytes())
        }
        val dynamic = wave("dynamic.wav") { i ->
            val value = ((if (i % 24000 < 2400) .85 else .08) * sin(2 * PI * 440 * i / 48000)).toFloat()
            value to value * .25f
        }
        for (preset in CompressionPreset.entries) {
            val plan = analyzeMastering(dynamic, MasteringSettings(compression = preset.compression))
            assertEquals(-20f, plan.after.lufs!!, .5f)
            assertTrue(plan.after.peakDb <= -2.99f)
            if (preset != CompressionPreset.GENTLE) assertTrue(plan.after.points.any { it.compressorDb > .05f })
        }
    }

    @Test fun eachPresetReducesCompressionComparedWithItsPreviousSettings() {
        val previous = listOf(MasteringCompression(-20f, 1.5f, 30f, 250f),
            MasteringCompression(-24f, 2f, 20f, 200f), MasteringCompression(-28f, 2.5f, 15f, 250f))
        fun reduction(settings: MasteringCompression): Float {
            val compressor = Compressor(settings.threshold, settings.ratio, settings.attack, settings.release, sampleRate = 48000)
            var total = 0f
            repeat(48000) { frame ->
                compressor.process(if (frame % 12000 < 2400) .6f else .03f)
                total += compressor.currentGainReductionDb
            }
            return total / 48000
        }
        val current = CompressionPreset.entries.map { reduction(it.compression) }
        current.forEachIndexed { index, value -> assertTrue(value < reduction(previous[index])) }
        assertTrue(current[0] < current[1] && current[1] < current[2])
    }

    @Test fun linkedCompressorAndLimiterKeepStereoBalanceAndContainIsolatedPeaks() {
        val chain = MasteringSettings().chain(48000, 12f, 12f)
        repeat(48000) { i ->
            val left = if (i % 500 == 0) .99f else .02f
            chain.process(left, left * .25f)
            assertEquals(chain.outputLeft * .25f, chain.outputRight, .00001f)
            assertTrue(abs(chain.outputLeft) <= dbToLinear(-3f) + .00001f)
        }
        val limiter = Limiter(-1f)
        repeat(1000) { i -> assertTrue(abs(limiter.process(if (i == 0) 2f else 0f)) <= dbToLinear(-1f) + .00001f) }
    }

    @Test fun exportPreservesDurationTailOriginalsAndExistingCopies() = runBlocking<Unit> {
        val file = wave("tail.wav", seconds = 1) { i -> if (i == 47999) .2f to .1f else 0f to 0f }
        val original = file.readBytes()
        val existing = directory.newFile("tail_final.wav").apply { writeText("Keep existing export") }
        val settings = MasteringSettings(normalize = false, compression = MasteringCompression(ratio = 1f))
        val path = exportMaster(file.path, settings.chain(48000)) {}
        assertTrue(path.endsWith("tail_final1.wav"))
        val output = File(path)
        assertEquals(48000 * 4L, readWavHeader(output).dataSize)
        val bytes = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(.2f, bytes.getShort(44 + 47999 * 4) / 32768f, .0001f)
        assertEquals(.1f, bytes.getShort(44 + 47999 * 4 + 2) / 32768f, .0001f)
        assertArrayEquals(original, file.readBytes())
        assertEquals("Keep existing export", existing.readText())
    }

    @Test fun exportedCopyMatchesTheAnalysisAndCancellationLeavesNoPartialFile() = runBlocking<Unit> {
        val file = tone("source.wav", .4f)
        val plan = analyzeMastering(file, MasteringSettings())
        val output = File(exportMaster(file.path, plan.chain()) {})
        val measured = measureMastering(output, null)
        assertEquals(plan.after.lufs!!, measured.lufs!!, .02f)
        assertTrue(measured.peakDb <= -2.99f)
        val before = directory.root.list()!!.toSet()
        try {
            exportMaster(file.path, plan.chain()) { throw CancellationException("Cancel export") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(before, directory.root.list()!!.toSet())
        val broken = directory.newFile("broken.wav").apply { writeBytes(file.readBytes().dropLast(4).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { masteringHeader(broken) }
    }
    @Test fun exportedMidiIsAnExactCopyAndFilenameSelectionReservesBothAssets() = runBlocking<Unit> {
        val file = tone("Practice.wav", .1f)
        val midi = RecordingBundleFiles.midi(file)
        val originalMidi = byteArrayOf(77, 84, 104, 100, 0, 1, 2, 3)
        midi.writeBytes(originalMidi)
        val collision = directory.newFile("Practice_final.mid").apply { writeText("Keep existing MIDI") }
        val chain = MasteringSettings().chain(48000)
        val output = File(exportMaster(file.path, chain) {})
        assertEquals("Practice_final1.wav", output.name)
        assertArrayEquals(originalMidi, RecordingBundleFiles.midi(output).readBytes())
        assertArrayEquals(originalMidi, midi.readBytes())
        assertEquals("Keep existing MIDI", collision.readText())
        assertEquals(readWavHeader(file).dataSize, readWavHeader(output).dataSize)
        val again = File(exportMaster(output.path, chain) {})
        assertEquals("Practice_final2.wav", again.name)
        assertArrayEquals(originalMidi, RecordingBundleFiles.midi(again).readBytes())
    }

    @Test fun changingMidiDuringExportDoesNotPublishAPartialPair() = runBlocking<Unit> {
        val file = tone("changed-midi.wav", .1f)
        val midi = RecordingBundleFiles.midi(file).apply { writeText("original MIDI") }
        try {
            exportMaster(file.path, MasteringSettings().chain(48000)) { midi.writeText("changed MIDI") }
            fail("Changed MIDI accepted")
        } catch (_: IllegalStateException) { }
        assertFalse(directory.root.list()!!.any { it.contains("_final") || it.endsWith(".part") })
        assertTrue(file.exists())
    }

    @Test fun rejectsChangedSourcesAndInvalidSampleRatesBeforeRendering() = runBlocking<Unit> {
        val file = tone("replaced.wav", .2f)
        val plan = analyzeMastering(file, MasteringSettings())
        file.setLastModified(file.lastModified() + 2000)
        try { exportMaster(file.path, plan.chain(), plan.sourceIdentity) {}; fail("Changed source accepted") }
        catch (_: IllegalStateException) { }
        assertFalse(directory.root.list()!!.any { it.contains("_final") || it.endsWith(".part") })
        val invalid = file.readBytes()
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(24, -48000)
        file.writeBytes(invalid)
        assertThrows(IllegalArgumentException::class.java) { MasteringPlayer().load(file.path) }
    }

    @Test fun cancellationWhileReturningPublishedExportRemovesItsCopy() = runBlocking<Unit> {
        val file = tone("cancel-after-move.wav", .1f)
        RecordingBundleFiles.midi(file).writeText("captured MIDI")
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val task = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            exportMaster(file.path, MasteringSettings().chain(48000)) {}
        }
        withTimeout(5000) { while (queue.isEmpty()) delay(5) }
        val output = File(directory.root, "cancel-after-move_final.wav")
        assertTrue(output.exists())
        task.cancel()
        queue.take().run()
        task.join()
        assertFalse(output.exists())
        assertFalse(RecordingBundleFiles.midi(output).exists())
        assertTrue(RecordingBundleFiles.midi(file).exists())
        assertFalse(directory.root.list()!!.any { it.endsWith(".part") })
        assertTrue(file.exists())
    }

    @Test fun previewSeekFlushesQueuedAudioAndResetsLimiterHistory() = runBlocking<Unit> {
        val file = wave("seek.wav", seconds = 4) { i -> if (i < 96000) .9f to .9f else .05f to .05f }
        val writes = java.util.concurrent.LinkedBlockingQueue<Float>()
        val permits = java.util.concurrent.Semaphore(0)
        val flushed = java.util.concurrent.atomic.AtomicInteger()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        val output = object : MasteringOutput {
            override fun play() { }
            override fun pause() { }
            override fun flush() { flushed.incrementAndGet(); writes.clear() }
            override fun stop() { }
            override fun release() { released.set(true) }
            override fun write(samples: ShortArray, count: Int) {
                writes.add((0 until count).maxOf { abs(samples[it].toInt()) / 32768f })
                permits.acquire()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = MasteringPlayer { output }
        try {
            player.load(file.path)
            player.setProcessing(MasteringSettings(normalize = false, ceiling = -12f,
                compression = MasteringCompression(ratio = 1f)).chain(48000))
            player.play(scope)
            withTimeout(5000) { while (writes.isEmpty()) delay(5) }
            assertEquals(dbToLinear(-12f), writes.take(), .001f)
            player.seekTo(.8f)
            permits.release()
            withTimeout(5000) { while (flushed.get() == 0 || writes.isEmpty()) delay(5) }
            assertEquals(.05f, writes.take(), .001f)
            assertNull(player.playbackError.value)
        } finally {
            player.release(); permits.release(10); scope.cancel()
            withTimeout(5000) { while (!released.get()) delay(5) }
        }
    }

}
