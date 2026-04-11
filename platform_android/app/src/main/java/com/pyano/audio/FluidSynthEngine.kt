package com.pyano.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

data class SfPreset(val bank: Int, val program: Int, val name: String)

class FluidSynthEngine {
    private var enginePtr: Long = 0
    var soundFontId: Int = -1
        private set

    companion object {
        private const val TAG = "Pyano"
        init {
            System.loadLibrary("pyano-native")
        }
    }

    fun create(sampleRate: Int = 48000, bufferSize: Int = 256): Boolean {
        enginePtr = nativeCreate(sampleRate, bufferSize)
        return enginePtr != 0L
    }

    fun destroy() {
        if (enginePtr != 0L) {
            nativeDestroy(enginePtr)
            enginePtr = 0
        }
    }

    fun startAudio(): Boolean {
        if (enginePtr == 0L) return false
        return nativeStartAudio(enginePtr)
    }

    fun stopAudio() {
        if (enginePtr != 0L) nativeStopAudio(enginePtr)
    }

    fun loadSoundFont(path: String): Int {
        if (enginePtr == 0L) return -1
        soundFontId = nativeLoadSoundFont(enginePtr, path)
        Log.i(TAG, "loadSoundFont: $path -> id=$soundFontId")
        return soundFontId
    }

    fun programSelect(channel: Int, sfId: Int, bank: Int, preset: Int) {
        if (enginePtr != 0L) nativeProgramSelect(enginePtr, channel, sfId, bank, preset)
    }

    fun noteOn(channel: Int, note: Int, velocity: Int) {
        if (enginePtr != 0L) nativeNoteOn(enginePtr, channel, note, velocity)
    }

    fun noteOff(channel: Int, note: Int) {
        if (enginePtr != 0L) nativeNoteOff(enginePtr, channel, note)
    }

    fun cc(channel: Int, ctrl: Int, value: Int) {
        if (enginePtr != 0L) nativeCC(enginePtr, channel, ctrl, value)
    }

    fun setGain(gain: Float) {
        if (enginePtr != 0L) nativeSetGain(enginePtr, gain)
    }

    fun setReverb(roomsize: Float, damping: Float, width: Float, level: Float) {
        if (enginePtr != 0L) nativeSetReverb(enginePtr, roomsize, damping, width, level)
    }

    fun setChorus(nr: Int, level: Float, speed: Float, depth: Float, type: Int) {
        if (enginePtr != 0L) nativeSetChorus(enginePtr, nr, level, speed, depth, type)
    }

    fun setReverbOn(on: Boolean) {
        if (enginePtr != 0L) nativeSetReverbOn(enginePtr, on)
    }

    fun setChorusOn(on: Boolean) {
        if (enginePtr != 0L) nativeSetChorusOn(enginePtr, on)
    }

    fun setBufferSize(bufferSize: Int): Boolean {
        if (enginePtr == 0L) return false
        return nativeSetBufferSize(enginePtr, bufferSize)
    }

    fun setAudioDevice(deviceId: Int): Boolean {
        if (enginePtr == 0L) return false
        return nativeSetAudioDevice(enginePtr, deviceId)
    }

    fun getPeakLevel(): Float {
        if (enginePtr == 0L) return 0f
        return nativeGetPeakLevel(enginePtr)
    }

    // --- Metronome ---

    fun startMetronome() {
        if (enginePtr != 0L) nativeStartMetronome(enginePtr)
    }

    fun stopMetronome() {
        if (enginePtr != 0L) nativeStopMetronome(enginePtr)
    }

    fun setMetronomeBpm(bpm: Int) {
        if (enginePtr != 0L) nativeSetMetronomeBpm(enginePtr, bpm)
    }

    fun setMetronomeTimeSig(beats: Int) {
        if (enginePtr != 0L) nativeSetMetronomeTimeSig(enginePtr, beats)
    }

    fun getMetronomeBeat(): Int {
        if (enginePtr == 0L) return 0
        return nativeGetMetronomeBeat(enginePtr)
    }

    fun setMetronomeClickNotes(downbeatNote: Int, subbeatNote: Int) {
        if (enginePtr != 0L) nativeSetMetronomeClickNotes(enginePtr, downbeatNote, subbeatNote)
    }

    fun setMetronomeVolume(volume: Float) {
        if (enginePtr != 0L) nativeSetMetronomeVolume(enginePtr, volume)
    }

    fun loadMetronomeDrumKit(path: String): Int {
        if (enginePtr == 0L) return -1
        return nativeLoadMetronomeDrumKit(enginePtr, path)
    }

    // --- Recording ---

    fun startRecording() {
        if (enginePtr != 0L) nativeStartRecording(enginePtr)
    }

    fun stopRecording() {
        if (enginePtr != 0L) nativeStopRecording(enginePtr)
    }

    fun readRecordingBuffer(buffer: FloatArray, maxFloats: Int): Int {
        if (enginePtr == 0L) return 0
        return nativeReadRecordingBuffer(enginePtr, buffer, maxFloats)
    }

    fun getRecordingSampleRate(): Int {
        if (enginePtr == 0L) return 48000
        return nativeGetRecordingSampleRate(enginePtr)
    }

    fun getRecordingOverflow(): Boolean {
        if (enginePtr == 0L) return false
        return nativeGetRecordingOverflow(enginePtr)
    }

    fun getPresets(sfId: Int): List<SfPreset> {
        if (enginePtr == 0L) return emptyList()
        val raw = nativeGetPresets(enginePtr, sfId)
        return raw.mapNotNull { entry ->
            val parts = entry.split(":", limit = 3)
            if (parts.size == 3) {
                SfPreset(parts[0].toInt(), parts[1].toInt(), parts[2])
            } else null
        }.sortedWith(compareBy({ it.bank }, { it.program }))
    }

    // Copy bundled soundfont from assets to internal storage
    fun copySoundFontFromAssets(context: Context, assetName: String): String? {
        val outDir = File(context.filesDir, "soundfonts")
        outDir.mkdirs()
        val outFile = File(outDir, assetName)

        if (outFile.exists()) {
            Log.i(TAG, "SoundFont already cached: ${outFile.absolutePath}")
            return outFile.absolutePath
        }

        return try {
            Log.i(TAG, "Copying soundfont from assets: $assetName")
            context.assets.open("soundfonts/$assetName").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "SoundFont copied to: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy soundfont: $e")
            null
        }
    }

    // Copy soundfont from content URI to internal storage
    fun copySoundFontFromUri(context: Context, inputStream: InputStream, fileName: String): String? {
        val outDir = File(context.filesDir, "soundfonts")
        outDir.mkdirs()
        val outFile = File(outDir, fileName)

        return try {
            inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "SoundFont imported to: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import soundfont: $e")
            null
        }
    }

    // Native methods
    private external fun nativeCreate(sampleRate: Int, bufferSize: Int): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeStartAudio(ptr: Long): Boolean
    private external fun nativeStopAudio(ptr: Long)
    private external fun nativeLoadSoundFont(ptr: Long, path: String): Int
    private external fun nativeProgramSelect(ptr: Long, channel: Int, sfId: Int, bank: Int, preset: Int)
    private external fun nativeNoteOn(ptr: Long, channel: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(ptr: Long, channel: Int, note: Int)
    private external fun nativeCC(ptr: Long, channel: Int, ctrl: Int, value: Int)
    private external fun nativeSetGain(ptr: Long, gain: Float)
    private external fun nativeSetReverb(ptr: Long, roomsize: Float, damping: Float, width: Float, level: Float)
    private external fun nativeSetChorus(ptr: Long, nr: Int, level: Float, speed: Float, depth: Float, type: Int)
    private external fun nativeSetReverbOn(ptr: Long, on: Boolean)
    private external fun nativeSetChorusOn(ptr: Long, on: Boolean)
    private external fun nativeSetBufferSize(ptr: Long, bufferSize: Int): Boolean
    private external fun nativeSetAudioDevice(ptr: Long, deviceId: Int): Boolean
    private external fun nativeGetPeakLevel(ptr: Long): Float
    private external fun nativeGetPresets(ptr: Long, sfId: Int): Array<String>
    private external fun nativeStartMetronome(ptr: Long)
    private external fun nativeStopMetronome(ptr: Long)
    private external fun nativeSetMetronomeBpm(ptr: Long, bpm: Int)
    private external fun nativeSetMetronomeTimeSig(ptr: Long, beats: Int)
    private external fun nativeGetMetronomeBeat(ptr: Long): Int
    private external fun nativeSetMetronomeClickNotes(ptr: Long, downbeatNote: Int, subbeatNote: Int)
    private external fun nativeSetMetronomeVolume(ptr: Long, volume: Float)
    private external fun nativeLoadMetronomeDrumKit(ptr: Long, path: String): Int
    private external fun nativeStartRecording(ptr: Long)
    private external fun nativeStopRecording(ptr: Long)
    private external fun nativeReadRecordingBuffer(ptr: Long, buffer: FloatArray, maxFloats: Int): Int
    private external fun nativeGetRecordingSampleRate(ptr: Long): Int
    private external fun nativeGetRecordingOverflow(ptr: Long): Boolean
}
