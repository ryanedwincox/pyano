package com.pyano

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyano.audio.AudioRecorder
import com.pyano.audio.FluidSynthEngine
import com.pyano.audio.LoopEngine
import com.pyano.audio.RecordingInfo
import com.pyano.audio.MidiEventType
import com.pyano.audio.SF2Info
import com.pyano.audio.SF2MetadataReader
import com.pyano.audio.SfPreset
import com.pyano.midi.MidiDeviceManager
import com.pyano.midi.MidiEventHandler
import com.pyano.midi.MidiRecordingListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioOutput(val id: Int, val name: String, val type: Int) {
    /**
     * Stable identifier used to remember a chosen output across reconnects and reboots.
     * AudioDeviceInfo.id is reassigned on every enumeration, so we key by type+name instead.
     * The synthetic "Default" entry (id=0) gets a fixed key so it's also stable.
     */
    val preferredKey: String
        get() = if (id == 0) "default" else "$type|$name"
}

class PyanoViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "Pyano"
        private const val DEFAULT_SF = "FluidR3_GM.sf2"
        private const val PREFS_NAME = "pyano_settings"
        // Per-channel effect sends forced after each program change so reverb/chorus
        // behaves consistently across instruments (see applyChannelSendDefaults).
        private const val REVERB_SEND_DEFAULT = 96
        private const val CHORUS_SEND_DEFAULT = 64
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, 0)
    val engine = FluidSynthEngine()
    private val midiManager = MidiDeviceManager(application)
    private var midiEventHandler: MidiEventHandler? = null
    private var midiDevice: MidiDevice? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    // UI State
    private val _soundFontName = MutableStateFlow(DEFAULT_SF)
    val soundFontName = _soundFontName.asStateFlow()

    private val _availableSoundFonts = MutableStateFlow<List<SF2Info>>(emptyList())
    val availableSoundFonts = _availableSoundFonts.asStateFlow()

    private val _favoriteSoundFonts = MutableStateFlow<List<SF2Info>>(emptyList())
    val favoriteSoundFonts = _favoriteSoundFonts.asStateFlow()

    private val _midiDevices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    val midiDevices = _midiDevices.asStateFlow()

    private val _selectedMidiDevice = MutableStateFlow<MidiDeviceInfo?>(null)
    val selectedMidiDevice = _selectedMidiDevice.asStateFlow()

    private val _midiStatus = MutableStateFlow("No MIDI device")
    val midiStatus = _midiStatus.asStateFlow()

    // Dynamic presets from loaded soundfont
    private val _presets = MutableStateFlow<List<SfPreset>>(emptyList())
    val presets = _presets.asStateFlow()

    private val _selectedPresetIndex = MutableStateFlow(0)
    val selectedPresetIndex = _selectedPresetIndex.asStateFlow()

    // Audio output devices
    private val _audioOutputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    val audioOutputs = _audioOutputs.asStateFlow()

    private val _selectedAudioOutput = MutableStateFlow<AudioOutput?>(null)
    val selectedAudioOutput = _selectedAudioOutput.asStateFlow()

    // Activity monitors
    private val _midiInputLevel = MutableStateFlow(false) // true = activity
    val midiInputLevel = _midiInputLevel.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel = _outputLevel.asStateFlow()

    private val _gain = MutableStateFlow(prefs.getFloat("gain", 3.0f))
    val gain = _gain.asStateFlow()

    private val _bufferSize = MutableStateFlow(prefs.getInt("bufferSize", 256))
    val bufferSize = _bufferSize.asStateFlow()

    // Reverb
    private val _reverbOn = MutableStateFlow(prefs.getBoolean("reverbOn", true))
    val reverbOn = _reverbOn.asStateFlow()
    private val _reverbRoom = MutableStateFlow(prefs.getFloat("reverbRoom", 0.2f))
    val reverbRoom = _reverbRoom.asStateFlow()
    private val _reverbDamp = MutableStateFlow(prefs.getFloat("reverbDamp", 0.0f))
    val reverbDamp = _reverbDamp.asStateFlow()
    private val _reverbWidth = MutableStateFlow(prefs.getFloat("reverbWidth", 0.5f))
    val reverbWidth = _reverbWidth.asStateFlow()
    private val _reverbLevel = MutableStateFlow(prefs.getFloat("reverbLevel", 0.9f))
    val reverbLevel = _reverbLevel.asStateFlow()

    // Chorus
    private val _chorusOn = MutableStateFlow(prefs.getBoolean("chorusOn", true))
    val chorusOn = _chorusOn.asStateFlow()
    private val _chorusVoices = MutableStateFlow(prefs.getInt("chorusVoices", 3))
    val chorusVoices = _chorusVoices.asStateFlow()
    private val _chorusLevel = MutableStateFlow(prefs.getFloat("chorusLevel", 2.0f))
    val chorusLevel = _chorusLevel.asStateFlow()
    private val _chorusSpeed = MutableStateFlow(prefs.getFloat("chorusSpeed", 0.3f))
    val chorusSpeed = _chorusSpeed.asStateFlow()
    private val _chorusDepth = MutableStateFlow(prefs.getFloat("chorusDepth", 8.0f))
    val chorusDepth = _chorusDepth.asStateFlow()
    private val _chorusType = MutableStateFlow(prefs.getInt("chorusType", 0))
    val chorusType = _chorusType.asStateFlow()

    // Metronome
    private val _metronomeBpm = MutableStateFlow(prefs.getInt("metronomeBpm", 120))
    val metronomeBpm = _metronomeBpm.asStateFlow()

    private val _metronomeTimeSig = MutableStateFlow(prefs.getInt("metronomeTimeSig", 4))
    val metronomeTimeSig = _metronomeTimeSig.asStateFlow()

    private val _metronomeRunning = MutableStateFlow(false)
    val metronomeRunning = _metronomeRunning.asStateFlow()

    private val _metronomeBeat = MutableStateFlow(0)
    val metronomeBeat = _metronomeBeat.asStateFlow()

    private val _metronomeClickType = MutableStateFlow(prefs.getInt("metronomeClickType", 0))
    val metronomeClickType = _metronomeClickType.asStateFlow()

    private val _metronomeVolume = MutableStateFlow(prefs.getFloat("metronomeVolume", 1.0f))
    val metronomeVolume = _metronomeVolume.asStateFlow()

    // GM percussion note pairs: downbeat / subbeat. Index into this list is
    // persisted as metronomeClickType so the dropdown stays stable across updates.
    data class MetronomeSound(val name: String, val downbeatNote: Int, val subbeatNote: Int)
    val metronomeSounds = listOf(
        MetronomeSound("Metronome", 34, 33),   // Metronome Bell / Click
        MetronomeSound("Wood Block", 76, 77),  // Hi / Low Wood Block
        MetronomeSound("Claves", 75, 75),
        MetronomeSound("Side Stick", 37, 37),
        MetronomeSound("Cowbell", 56, 56),
        MetronomeSound("Closed Hi-Hat", 42, 42),
        MetronomeSound("Sticks", 31, 31),
    )

    // --- Loop Station ---
    private val loopEngine = LoopEngine(engine)

    private val _loopRecording = MutableStateFlow(false)
    val loopRecording = _loopRecording.asStateFlow()

    private val _loopPlaying = MutableStateFlow(false)
    val loopPlaying = _loopPlaying.asStateFlow()

    private val _loopBpm = MutableStateFlow(prefs.getInt("loopBpm", 120))
    val loopBpm = _loopBpm.asStateFlow()

    private val _loopLengthBars = MutableStateFlow(prefs.getInt("loopLengthBars", 4))
    val loopLengthBars = _loopLengthBars.asStateFlow()

    private val _loopLayerCounts = MutableStateFlow(List(LoopEngine.MAX_LAYERS) { 0 })
    val loopLayerCounts = _loopLayerCounts.asStateFlow()

    private val _loopPosition = MutableStateFlow(0f)
    val loopPosition = _loopPosition.asStateFlow()

    private val _loopCountIn = MutableStateFlow(prefs.getBoolean("loopCountIn", false))
    val loopCountIn = _loopCountIn.asStateFlow()

    private val _loopSyncBpm = MutableStateFlow(prefs.getBoolean("loopSyncBpm", true))
    val loopSyncBpm = _loopSyncBpm.asStateFlow()

    private val _loopRecordingLayerIndex = MutableStateFlow(-1)
    val loopRecordingLayerIndex = _loopRecordingLayerIndex.asStateFlow()

    private val _loopHasAnyEvents = MutableStateFlow(false)
    val loopHasAnyEvents = _loopHasAnyEvents.asStateFlow()

    // --- Audio Recorder ---
    val audioRecorder = AudioRecorder(engine, application)

    private val _isAudioRecording = MutableStateFlow(false)
    val isAudioRecording = _isAudioRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec = _recordingDurationSec.asStateFlow()

    private val _savedRecordings = MutableStateFlow<List<RecordingInfo>>(emptyList())
    val savedRecordings = _savedRecordings.asStateFlow()

    // Saved soundfont path
    private val savedSfPath = prefs.getString("sfPath", null)
    private val savedSfFileName = prefs.getString("sfFileName", null)
    private val savedPresetIndex = prefs.getInt("presetIndex", 0)
    // Stable name+type key (preferred). Falls back to legacy id-based pref for one migration cycle.
    private val savedAudioOutputKey = prefs.getString("audioOutputKey", null)
    private val savedMidiDeviceName = prefs.getString("midiDeviceName", null)

    private fun save(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    private fun save(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun save(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    private fun save(key: String, value: String) = prefs.edit().putString(key, value).apply()

    // Tab navigation
    private val _selectedTab = MutableStateFlow(prefs.getInt("selectedTab", 0))
    val selectedTab = _selectedTab.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    fun initialize() {
        if (!engine.create(48000, _bufferSize.value)) {
            Log.e(TAG, "Failed to create engine")
            return
        }

        if (!engine.startAudio()) {
            Log.e(TAG, "Failed to start audio")
            return
        }

        // Load the dedicated metronome drum kit (FluidR3_GM.sf2 bank 128 preset 0)
        // BEFORE any piano soundfont so channel 9 routing is stable. Bundled in
        // assets; idempotent copy.
        val drumPath = engine.copySoundFontFromAssets(getApplication(), DEFAULT_SF)
        if (drumPath != null) {
            engine.loadMetronomeDrumKit(drumPath)
        } else {
            Log.e(TAG, "Failed to stage metronome drum soundfont")
        }

        // Push saved metronome sound + volume to engine
        applyMetronomeSound()
        engine.setMetronomeVolume(_metronomeVolume.value)

        // Apply saved settings
        engine.setGain(_gain.value)
        if (_reverbOn.value) {
            engine.setReverb(_reverbRoom.value, _reverbDamp.value, _reverbWidth.value, _reverbLevel.value)
        } else {
            engine.setReverbOn(false)
        }
        if (_chorusOn.value) {
            engine.setChorus(_chorusVoices.value, _chorusLevel.value, _chorusSpeed.value, _chorusDepth.value, _chorusType.value)
        } else {
            engine.setChorusOn(false)
        }

        // Load saved soundfont or fallback to bundled
        val context = getApplication<Application>()
        var loaded = false
        if (savedSfPath != null && java.io.File(savedSfPath).exists()) {
            val sfId = engine.loadSoundFont(savedSfPath)
            if (sfId >= 0) {
                val info = SF2MetadataReader.readFromFile(java.io.File(savedSfPath))
                _soundFontName.value = info?.name ?: savedSfFileName ?: DEFAULT_SF
                refreshPresets(sfId)
                // Restore saved preset
                if (savedPresetIndex < _presets.value.size) {
                    selectPreset(savedPresetIndex)
                } else {
                    engine.programSelect(0, sfId, 0, 0)
                    applyChannelSendDefaults(0)
                }
                loaded = true
                Log.i(TAG, "Restored saved soundfont: $savedSfPath")
            }
        }
        if (!loaded) {
            val bundledPath = engine.copySoundFontFromAssets(context, DEFAULT_SF)
            if (bundledPath != null) {
                val sfId = engine.loadSoundFont(bundledPath)
                if (sfId >= 0) {
                    engine.programSelect(0, sfId, 0, 0)
                    applyChannelSendDefaults(0)
                    refreshPresets(sfId)
                    val info = SF2MetadataReader.readFromFile(java.io.File(bundledPath))
                    _soundFontName.value = info?.name ?: DEFAULT_SF
                    Log.i(TAG, "Default soundfont loaded")
                }
            }
        }

        _isLoading.value = false

        // Set up MIDI device monitoring
        midiEventHandler = MidiEventHandler(engine)
        refreshMidiDevices()
        midiManager.registerCallback(
            onDeviceAdded = { info ->
                refreshMidiDevices()
                // Sticky reconnect: if nothing is currently bound, and either the new
                // device matches our remembered one or we have no remembered device,
                // bind it. Never yank an already-active connection.
                if (_selectedMidiDevice.value == null) {
                    val name = midiManager.getDeviceName(info)
                    if (savedMidiDeviceName == null || savedMidiDeviceName == name) {
                        connectMidiDevice(info)
                    }
                }
            },
            onDeviceRemoved = { info ->
                if (_selectedMidiDevice.value?.id == info.id) {
                    disconnectMidi()
                }
                refreshMidiDevices()
            }
        )

        // Auto-connect: prefer the saved device by name, else first available.
        val devices = midiManager.getDevices()
        val preferredMidi = savedMidiDeviceName?.let { saved ->
            devices.firstOrNull { midiManager.getDeviceName(it) == saved }
        } ?: devices.firstOrNull()
        if (preferredMidi != null) {
            connectMidiDevice(preferredMidi)
        }

        // Enumerate audio outputs and restore saved selection by stable key.
        refreshAudioOutputs()
        tryRebindPreferredAudio()
        registerAudioDeviceCallback()
        maxSystemMediaVolume("startup")

        // Initialize loop engine with saved/current settings
        loopEngine.setBpm(_loopBpm.value)
        loopEngine.setLoopLengthBars(_loopLengthBars.value)
        loopEngine.setTimeSigBeats(_metronomeTimeSig.value)

        // Start activity monitor polling
        viewModelScope.launch {
            while (true) {
                delay(50) // 20Hz update rate
                // MIDI input activity: true if event in last 200ms
                val handler = midiEventHandler
                _midiInputLevel.value = handler != null &&
                    (System.currentTimeMillis() - handler.lastEventTimeMs) < 200

                // Audio output level
                _outputLevel.value = engine.getPeakLevel()

                // Metronome beat (poll native engine for current beat index)
                if (_metronomeRunning.value) {
                    _metronomeBeat.value = engine.getMetronomeBeat()
                }

                // Recording duration
                if (_isAudioRecording.value) {
                    _recordingDurationSec.value = (audioRecorder.recordingDurationMs / 1000).toInt()
                }

                // Loop station: position + layer counts + auto-stop
                if (loopEngine.isPlaying || loopEngine.isRecording) {
                    val duration = loopEngine.loopDurationNs
                    if (loopEngine.isPlaying) {
                        _loopPosition.value = if (duration > 0) {
                            (loopEngine.currentPositionNs.toFloat() / duration).coerceIn(0f, 1f)
                        } else 0f
                    } else if (loopEngine.isRecording) {
                        // Recording-only mode: compute position from recording elapsed time
                        _loopPosition.value = if (duration > 0) {
                            ((loopEngine.getRecordingPositionNs() % duration).toFloat() / duration).coerceIn(0f, 1f)
                        } else 0f
                    }
                    _loopPlaying.value = loopEngine.isPlaying
                    _loopRecording.value = loopEngine.isRecording

                    // Auto-stop recording at loop boundary when overdubbing
                    if (loopEngine.checkAutoStopRecording()) {
                        _loopRecording.value = false
                        _loopRecordingLayerIndex.value = -1
                        refreshLoopLayerCounts()
                    }
                }
            }
        }
    }

    fun refreshAudioOutputs() {
        val context = getApplication<Application>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val outputs = mutableListOf(AudioOutput(0, "Default", 0))
        for (device in devices) {
            val typeName = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                else -> "Audio Device"
            }
            val name = if (device.productName.isNotEmpty()) {
                "$typeName (${device.productName})"
            } else {
                typeName
            }
            outputs.add(AudioOutput(device.id, name, device.type))
        }
        _audioOutputs.value = outputs
        Log.i(TAG, "Found ${outputs.size} audio outputs")
    }

    fun setAudioOutput(output: AudioOutput) {
        _selectedAudioOutput.value = output
        save("audioOutputKey", output.preferredKey)
        engine.setAudioDevice(output.id)
        Log.i(TAG, "Set audio output: ${output.name} (id=${output.id}, key=${output.preferredKey})")

        // Always max out system media volume on output change. Pyano expects to
        // drive the device at unity and have the user attenuate at the speaker /
        // interface / headphone amp. The built-in speaker is the one case where
        // we leave it alone (so we don't blast the user).
        if (output.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            maxSystemMediaVolume("output=${output.name}")
        }
    }

    /**
     * If the user has a remembered audio output, find it in the current device list
     * (by stable name+type key) and bind to it. Falls back to the synthetic Default
     * if the remembered device isn't currently present.
     */
    private fun tryRebindPreferredAudio() {
        val key = savedAudioOutputKey ?: _selectedAudioOutput.value?.preferredKey ?: return
        val match = _audioOutputs.value.firstOrNull { it.preferredKey == key }
        if (match != null && match.preferredKey != _selectedAudioOutput.value?.preferredKey) {
            setAudioOutput(match)
        }
    }

    private fun registerAudioDeviceCallback() {
        val audioManager = getApplication<Application>()
            .getSystemService(AudioManager::class.java)
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshAudioOutputs()
                tryRebindPreferredAudio()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshAudioOutputs()
                // If the active output went away, fall back to the Default entry.
                val current = _selectedAudioOutput.value ?: return
                if (_audioOutputs.value.none { it.preferredKey == current.preferredKey }) {
                    _audioOutputs.value.firstOrNull()?.let { fallback ->
                        _selectedAudioOutput.value = fallback
                        engine.setAudioDevice(fallback.id)
                        Log.i(TAG, "Active audio output removed, falling back to ${fallback.name}")
                    }
                }
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun midiDeviceName(info: MidiDeviceInfo): String = midiManager.getDeviceName(info)

    private fun maxSystemMediaVolume(reason: String) {
        try {
            val audioManager = getApplication<Application>()
                .getSystemService(AudioManager::class.java)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.i(TAG, "Maxed media volume to $maxVol (actual=$actual) [$reason]")
            if (actual < maxVol) {
                Log.w(TAG, "Volume did not reach max — likely DnD/zen blocking setStreamVolume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set media volume [$reason]", e)
        }
    }

    private fun refreshPresets(sfId: Int) {
        val presetList = engine.getPresets(sfId)
        _presets.value = presetList
        _selectedPresetIndex.value = 0
        Log.i(TAG, "Found ${presetList.size} presets in soundfont")
    }

    fun refreshMidiDevices() {
        _midiDevices.value = midiManager.getDevices()
    }

    fun connectMidiDevice(info: MidiDeviceInfo) {
        val numOutputPorts = info.outputPortCount
        val numInputPorts = info.inputPortCount
        Log.i(TAG, "MIDI device: ${midiManager.getDeviceName(info)}, outputPorts=$numOutputPorts, inputPorts=$numInputPorts")

        midiManager.openDevice(info) { device ->
            if (device != null) {
                midiDevice = device
                _selectedMidiDevice.value = info
                val name = midiManager.getDeviceName(info)
                _midiStatus.value = name
                save("midiDeviceName", name)

                // Connect to all output ports (keyboard data comes from output ports)
                var connected = false
                for (i in 0 until numOutputPorts) {
                    val outputPort = device.openOutputPort(i)
                    if (outputPort != null) {
                        outputPort.connect(midiEventHandler)
                        Log.i(TAG, "Connected to MIDI output port $i")
                        connected = true
                    } else {
                        Log.e(TAG, "Failed to open MIDI output port $i")
                    }
                }
                if (!connected) {
                    Log.e(TAG, "No MIDI output ports could be opened")
                    _midiStatus.value = "MIDI: no output ports"
                }
            } else {
                Log.e(TAG, "Failed to open MIDI device")
                _midiStatus.value = "Failed to open device"
            }
        }
    }

    fun disconnectMidi() {
        midiDevice?.close()
        midiDevice = null
        _selectedMidiDevice.value = null
        _midiStatus.value = "No MIDI device"
    }

    fun selectPreset(index: Int) {
        val presetList = _presets.value
        if (index < 0 || index >= presetList.size) return
        _selectedPresetIndex.value = index
        save("presetIndex", index)
        val preset = presetList[index]
        val sfId = engine.soundFontId
        if (sfId >= 0) {
            engine.programSelect(0, sfId, preset.bank, preset.program)
            applyChannelSendDefaults(0)
            Log.i(TAG, "Selected preset: ${preset.name} (bank=${preset.bank}, prog=${preset.program})")
        }
    }

    /**
     * Force a consistent reverb/chorus send level on a channel after a program change.
     * SF2 presets reset CC91/CC93 to the preset's default modulators on program_select,
     * which is often 0 for non-piano instruments — making the global reverb inaudible
     * even though the reverb engine is on. Override here so reverb behaves the same
     * across every instrument.
     */
    private fun applyChannelSendDefaults(channel: Int) {
        engine.cc(channel, 91, REVERB_SEND_DEFAULT)
        engine.cc(channel, 93, CHORUS_SEND_DEFAULT)
    }

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
        save("selectedTab", index)
        // Refresh recordings list when switching to Recorder tab (index 3)
        if (index == 3) refreshRecordings()
    }

    fun setGain(value: Float) {
        _gain.value = value
        save("gain", value)
        engine.setGain(value)
    }

    fun setBufferSize(value: Int) {
        _bufferSize.value = value
        save("bufferSize", value)
        engine.setBufferSize(value)
    }

    fun setReverbOn(on: Boolean) {
        _reverbOn.value = on
        save("reverbOn", on)
        engine.setReverbOn(on)
    }

    fun setReverbRoom(value: Float) {
        _reverbRoom.value = value
        save("reverbRoom", value)
        applyReverb()
    }

    fun setReverbDamp(value: Float) {
        _reverbDamp.value = value
        save("reverbDamp", value)
        applyReverb()
    }

    fun setReverbWidth(value: Float) {
        _reverbWidth.value = value
        save("reverbWidth", value)
        applyReverb()
    }

    fun setReverbLevel(value: Float) {
        _reverbLevel.value = value
        save("reverbLevel", value)
        applyReverb()
    }

    private fun applyReverb() {
        engine.setReverb(_reverbRoom.value, _reverbDamp.value, _reverbWidth.value, _reverbLevel.value)
    }

    fun setChorusOn(on: Boolean) {
        _chorusOn.value = on
        save("chorusOn", on)
        engine.setChorusOn(on)
    }

    fun setChorusVoices(value: Int) {
        _chorusVoices.value = value
        save("chorusVoices", value)
        applyChorus()
    }

    fun setChorusLevel(value: Float) {
        _chorusLevel.value = value
        save("chorusLevel", value)
        applyChorus()
    }

    fun setChorusSpeed(value: Float) {
        _chorusSpeed.value = value
        save("chorusSpeed", value)
        applyChorus()
    }

    fun setChorusDepth(value: Float) {
        _chorusDepth.value = value
        save("chorusDepth", value)
        applyChorus()
    }

    fun setChorusType(value: Int) {
        _chorusType.value = value
        save("chorusType", value)
        applyChorus()
    }

    private fun applyChorus() {
        engine.setChorus(_chorusVoices.value, _chorusLevel.value, _chorusSpeed.value, _chorusDepth.value, _chorusType.value)
    }

    // --- Metronome ---

    fun setMetronomeBpm(bpm: Int) {
        val clamped = bpm.coerceIn(LoopEngine.BPM_MIN, LoopEngine.BPM_MAX)
        _metronomeBpm.value = clamped
        save("metronomeBpm", clamped)
        engine.setMetronomeBpm(clamped)
        // Sync to loop engine if BPM sync is enabled
        if (_loopSyncBpm.value && _loopBpm.value != clamped) {
            _loopBpm.value = clamped
            save("loopBpm", clamped)
            loopEngine.setBpm(clamped)
        }
    }

    fun setMetronomeTimeSig(beats: Int) {
        val clamped = beats.coerceIn(1, 12)
        _metronomeTimeSig.value = clamped
        save("metronomeTimeSig", clamped)
        engine.setMetronomeTimeSig(clamped)
        loopEngine.setTimeSigBeats(clamped)
    }

    private val tapTimestamps = mutableListOf<Long>()

    /**
     * Record a tap tempo event. Averages the last 4 tap intervals to compute BPM.
     * Discards taps with >2s gaps (user paused).
     */
    fun tapTempo() {
        val now = System.currentTimeMillis()
        tapTimestamps.add(now)
        if (tapTimestamps.size > 4) tapTimestamps.removeAt(0)
        if (tapTimestamps.size >= 2) {
            val intervals = tapTimestamps.zipWithNext { a, b -> b - a }
            if (intervals.all { it in 150..2000 }) {
                val avgMs = intervals.average()
                val tapBpm = (60000.0 / avgMs).toInt().coerceIn(LoopEngine.BPM_MIN, LoopEngine.BPM_MAX)
                setMetronomeBpm(tapBpm)
            }
        }
    }

    fun toggleMetronome() {
        val newState = !_metronomeRunning.value
        _metronomeRunning.value = newState
        if (newState) {
            // Push current settings to engine before starting
            engine.setMetronomeBpm(_metronomeBpm.value)
            engine.setMetronomeTimeSig(_metronomeTimeSig.value)
            applyMetronomeSound()
            engine.setMetronomeVolume(_metronomeVolume.value)
            engine.startMetronome()
        } else {
            engine.stopMetronome()
            _metronomeBeat.value = 0
        }
    }

    private fun applyMetronomeSound() {
        val idx = _metronomeClickType.value.coerceIn(0, metronomeSounds.lastIndex)
        val sound = metronomeSounds[idx]
        engine.setMetronomeClickNotes(sound.downbeatNote, sound.subbeatNote)
    }

    fun setMetronomeClickType(type: Int) {
        val clamped = type.coerceIn(0, metronomeSounds.lastIndex)
        _metronomeClickType.value = clamped
        save("metronomeClickType", clamped)
        applyMetronomeSound()
    }

    fun setMetronomeVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 2f)
        _metronomeVolume.value = clamped
        save("metronomeVolume", clamped)
        engine.setMetronomeVolume(clamped)
    }

    // --- Loop Station ---

    fun startLoopRecording() {
        if (loopEngine.isRecording) return

        // Wire up recording listener
        midiEventHandler?.recordingListener = object : MidiRecordingListener {
            override fun onMidiEvent(channel: Int, type: MidiEventType, note: Int, velocity: Int) {
                loopEngine.recordEvent(channel, type, note, velocity)
            }
        }

        if (_loopCountIn.value && !loopEngine.isPlaying) {
            // Count-in: start metronome for 1 bar, then begin recording
            val metronomeWasRunning = _metronomeRunning.value
            if (!metronomeWasRunning) {
                // Push loop BPM to metronome for count-in
                engine.setMetronomeBpm(_loopBpm.value)
                engine.setMetronomeTimeSig(loopEngine.timeSigBeats)
                engine.startMetronome()
                _metronomeRunning.value = true
            }
            val countInDurationMs = (loopEngine.timeSigBeats.toLong() * 60_000L) / _loopBpm.value
            viewModelScope.launch {
                delay(countInDurationMs)
                // Only stop metronome if we started it for count-in
                if (!metronomeWasRunning && _metronomeRunning.value) {
                    engine.stopMetronome()
                    _metronomeRunning.value = false
                    _metronomeBeat.value = 0
                }
                doStartRecording()
            }
        } else {
            doStartRecording()
        }
    }

    private fun doStartRecording() {
        val idx = loopEngine.startRecording()
        if (idx >= 0) {
            _loopRecording.value = true
            _loopRecordingLayerIndex.value = idx
            refreshLoopLayerCounts()
        }
    }

    fun stopLoopRecording() {
        loopEngine.stopRecording()
        midiEventHandler?.recordingListener = null
        _loopRecording.value = false
        _loopRecordingLayerIndex.value = -1
        refreshLoopLayerCounts()
    }

    fun toggleLoopPlayback() {
        if (loopEngine.isPlaying) {
            loopEngine.stopPlayback()
            _loopPlaying.value = false
            _loopPosition.value = 0f
        } else {
            loopEngine.startPlayback(viewModelScope)
            _loopPlaying.value = true
        }
    }

    fun clearLoopLayer(index: Int) {
        loopEngine.clearLayer(index)
        refreshLoopLayerCounts()
    }

    fun clearAllLoops() {
        val wasPlaying = loopEngine.isPlaying
        val wasRecording = loopEngine.isRecording
        if (wasRecording) stopLoopRecording()
        if (wasPlaying) {
            loopEngine.stopPlayback()
            _loopPlaying.value = false
            _loopPosition.value = 0f
        }
        loopEngine.clearAll()
        refreshLoopLayerCounts()
    }

    fun setLoopBpm(bpm: Int) {
        val clamped = bpm.coerceIn(LoopEngine.BPM_MIN, LoopEngine.BPM_MAX)
        _loopBpm.value = clamped
        save("loopBpm", clamped)
        loopEngine.setBpm(clamped)
        // Sync to metronome if enabled
        if (_loopSyncBpm.value) {
            setMetronomeBpm(clamped)
        }
    }

    fun setLoopLengthBars(bars: Int) {
        _loopLengthBars.value = bars
        save("loopLengthBars", bars)
        loopEngine.setLoopLengthBars(bars)
    }

    fun toggleLoopCountIn() {
        val newVal = !_loopCountIn.value
        _loopCountIn.value = newVal
        save("loopCountIn", newVal)
    }

    fun toggleLoopSyncBpm() {
        val newVal = !_loopSyncBpm.value
        _loopSyncBpm.value = newVal
        save("loopSyncBpm", newVal)
        if (newVal) {
            // Sync loop BPM from metronome
            setLoopBpm(_metronomeBpm.value)
        }
    }

    private fun refreshLoopLayerCounts() {
        val counts = List(LoopEngine.MAX_LAYERS) { loopEngine.getLayerEventCount(it) }
        _loopLayerCounts.value = counts
        _loopHasAnyEvents.value = counts.any { it > 0 }
        _loopRecordingLayerIndex.value = loopEngine.recordingLayerIndex
    }

    // --- Audio Recorder ---

    fun startAudioRecording() {
        if (audioRecorder.isRecording) return
        audioRecorder.startRecording(viewModelScope)
        _isAudioRecording.value = true
        _recordingDurationSec.value = 0
    }

    fun stopAudioRecording() {
        if (!audioRecorder.isRecording) return
        viewModelScope.launch {
            audioRecorder.stopRecording()
            _isAudioRecording.value = false
            _recordingDurationSec.value = 0
            refreshRecordings()
        }
    }

    fun refreshRecordings() {
        _savedRecordings.value = audioRecorder.getSavedRecordings()
    }

    fun deleteRecording(path: String) {
        audioRecorder.deleteRecording(path)
        refreshRecordings()
    }

    fun scanSoundFonts() {
        val fonts = mutableListOf<SF2Info>()

        // Bundled soundfont from internal storage
        val context = getApplication<Application>()
        val bundledFile = java.io.File(context.filesDir, "soundfonts/$DEFAULT_SF")
        if (bundledFile.exists()) {
            SF2MetadataReader.readFromFile(bundledFile, isBundled = true)?.let { fonts.add(it) }
        }

        // Scan Downloads folder
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        Log.i(TAG, "Scanning Downloads: ${downloadsDir.absolutePath}, exists=${downloadsDir.exists()}, canRead=${downloadsDir.canRead()}")
        if (downloadsDir.exists() && downloadsDir.canRead()) {
            downloadsDir.listFiles()?.filter {
                it.extension.equals("sf2", ignoreCase = true)
            }?.forEach { file ->
                SF2MetadataReader.readFromFile(file)?.let { fonts.add(it) }
            }
        }

        // Sort: bundled first, then alphabetical by name
        fonts.sortWith(compareByDescending<SF2Info> { it.isBundled }.thenBy { it.name.lowercase() })

        _availableSoundFonts.value = fonts
        refreshFavorites()
        Log.i(TAG, "Scanned ${fonts.size} soundfonts")
    }

    fun loadSoundFontByPath(path: String, fileName: String) {
        _isLoading.value = true
        Thread {
            val sfId = engine.loadSoundFont(path)
            if (sfId >= 0) {
                engine.programSelect(0, sfId, 0, 0)
                applyChannelSendDefaults(0)
                val info = SF2MetadataReader.readFromFile(java.io.File(path))
                _soundFontName.value = info?.name ?: fileName.removeSuffix(".sf2")
                refreshPresets(sfId)
                save("sfPath", path)
                save("sfFileName", fileName)
                save("presetIndex", 0)
            }
            _isLoading.value = false
        }.start()
    }

    fun importSoundFont(uri: Uri) {
        val context = getApplication<Application>()
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.sf2"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return

        _isLoading.value = true
        Thread {
            val path = engine.copySoundFontFromUri(context, inputStream, fileName)
            if (path != null) {
                val sfId = engine.loadSoundFont(path)
                if (sfId >= 0) {
                    engine.programSelect(0, sfId, 0, 0)
                    applyChannelSendDefaults(0)
                    val info = SF2MetadataReader.readFromFile(java.io.File(path))
                    _soundFontName.value = info?.name ?: fileName.removeSuffix(".sf2")
                    refreshPresets(sfId)
                }
            }
            _isLoading.value = false
            scanSoundFonts()
        }.start()
    }

    fun deleteSoundFont(info: SF2Info) {
        if (info.isBundled) return
        val file = java.io.File(info.path)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Deleted soundfont: ${info.path}")
        }
        scanSoundFonts()
    }

    fun toggleFavoriteSoundFont(sf: SF2Info) {
        val paths = getFavoritePaths().toMutableList()
        if (sf.path in paths) {
            paths.remove(sf.path)
        } else {
            if (paths.size >= 4) return
            paths.add(sf.path)
        }
        save("favoriteSfPaths", paths.joinToString("\n"))
        refreshFavorites()
    }

    fun isFavoriteSoundFont(path: String): Boolean = path in getFavoritePaths()

    private fun getFavoritePaths(): List<String> {
        val raw = prefs.getString("favoriteSfPaths", null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    private fun refreshFavorites() {
        val paths = getFavoritePaths()
        val available = _availableSoundFonts.value.associateBy { it.path }
        _favoriteSoundFonts.value = paths.mapNotNull { available[it] }
    }

    override fun onCleared() {
        super.onCleared()
        if (audioRecorder.isRecording) {
            audioRecorder.cancelRecording()
            engine.stopRecording()
        }
        loopEngine.stopPlayback()
        midiEventHandler?.recordingListener = null
        engine.stopMetronome()
        midiManager.destroy()
        audioDeviceCallback?.let { cb ->
            getApplication<Application>()
                .getSystemService(AudioManager::class.java)
                .unregisterAudioDeviceCallback(cb)
        }
        audioDeviceCallback = null
        engine.stopAudio()
        engine.destroy()
    }
}
