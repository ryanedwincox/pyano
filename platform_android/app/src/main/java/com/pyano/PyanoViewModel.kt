package com.pyano

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyano.audio.FluidSynthEngine
import com.pyano.audio.SF2Info
import com.pyano.audio.SF2MetadataReader
import com.pyano.audio.SfPreset
import com.pyano.midi.MidiDeviceManager
import com.pyano.midi.MidiEventHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioOutput(val id: Int, val name: String, val type: Int)

class PyanoViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "Pyano"
        private const val DEFAULT_SF = "FluidR3_GM.sf2"
    }

    val engine = FluidSynthEngine()
    private val midiManager = MidiDeviceManager(application)
    private var midiEventHandler: MidiEventHandler? = null
    private var midiDevice: MidiDevice? = null

    // UI State
    private val _soundFontName = MutableStateFlow(DEFAULT_SF)
    val soundFontName = _soundFontName.asStateFlow()

    private val _availableSoundFonts = MutableStateFlow<List<SF2Info>>(emptyList())
    val availableSoundFonts = _availableSoundFonts.asStateFlow()

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

    private val _gain = MutableStateFlow(3.0f)
    val gain = _gain.asStateFlow()

    private val _bufferSize = MutableStateFlow(256)
    val bufferSize = _bufferSize.asStateFlow()

    // Reverb
    private val _reverbOn = MutableStateFlow(true)
    val reverbOn = _reverbOn.asStateFlow()
    private val _reverbRoom = MutableStateFlow(0.2f)
    val reverbRoom = _reverbRoom.asStateFlow()
    private val _reverbDamp = MutableStateFlow(0.0f)
    val reverbDamp = _reverbDamp.asStateFlow()
    private val _reverbWidth = MutableStateFlow(0.5f)
    val reverbWidth = _reverbWidth.asStateFlow()
    private val _reverbLevel = MutableStateFlow(0.9f)
    val reverbLevel = _reverbLevel.asStateFlow()

    // Chorus
    private val _chorusOn = MutableStateFlow(true)
    val chorusOn = _chorusOn.asStateFlow()
    private val _chorusVoices = MutableStateFlow(3)
    val chorusVoices = _chorusVoices.asStateFlow()
    private val _chorusLevel = MutableStateFlow(2.0f)
    val chorusLevel = _chorusLevel.asStateFlow()
    private val _chorusSpeed = MutableStateFlow(0.3f)
    val chorusSpeed = _chorusSpeed.asStateFlow()
    private val _chorusDepth = MutableStateFlow(8.0f)
    val chorusDepth = _chorusDepth.asStateFlow()
    private val _chorusType = MutableStateFlow(0)
    val chorusType = _chorusType.asStateFlow()

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

        // Apply initial gain
        engine.setGain(_gain.value)

        // Copy and load bundled soundfont
        val context = getApplication<Application>()
        val sfPath = engine.copySoundFontFromAssets(context, DEFAULT_SF)
        if (sfPath != null) {
            val sfId = engine.loadSoundFont(sfPath)
            if (sfId >= 0) {
                engine.programSelect(0, sfId, 0, 0)
                refreshPresets(sfId)
                val info = SF2MetadataReader.readFromFile(java.io.File(sfPath))
                _soundFontName.value = info?.name ?: DEFAULT_SF
                Log.i(TAG, "Default soundfont loaded")
            }
        }

        _isLoading.value = false

        // Set up MIDI device monitoring
        midiEventHandler = MidiEventHandler(engine)
        refreshMidiDevices()
        midiManager.registerCallback(
            onDeviceAdded = { refreshMidiDevices() },
            onDeviceRemoved = { info ->
                if (_selectedMidiDevice.value?.id == info.id) {
                    disconnectMidi()
                }
                refreshMidiDevices()
            }
        )

        // Auto-connect first available device
        val devices = midiManager.getDevices()
        if (devices.isNotEmpty()) {
            connectMidiDevice(devices[0])
        }

        // Enumerate audio outputs and max volume if USB is default
        refreshAudioOutputs()
        val context2 = getApplication<Application>()
        val audioManager = context2.getSystemService(AudioManager::class.java)
        val currentDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasUsb = currentDevices.any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (hasUsb) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            Log.i(TAG, "USB audio detected at startup: volume maxed to $maxVol")
        }

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
        engine.setAudioDevice(output.id)
        Log.i(TAG, "Set audio output: ${output.name} (id=${output.id})")

        // Max out system volume for USB audio devices (volume controlled on interface)
        val isUsb = output.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    output.type == AudioDeviceInfo.TYPE_USB_HEADSET
        if (isUsb) {
            val context = getApplication<Application>()
            val audioManager = context.getSystemService(AudioManager::class.java)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            Log.i(TAG, "USB audio: volume maxed to $maxVol")
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
                _midiStatus.value = midiManager.getDeviceName(info)

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
        val preset = presetList[index]
        val sfId = engine.soundFontId
        if (sfId >= 0) {
            engine.programSelect(0, sfId, preset.bank, preset.program)
            Log.i(TAG, "Selected preset: ${preset.name} (bank=${preset.bank}, prog=${preset.program})")
        }
    }

    fun setGain(value: Float) {
        _gain.value = value
        engine.setGain(value)
    }

    fun setBufferSize(value: Int) {
        _bufferSize.value = value
        engine.setBufferSize(value)
    }

    fun setReverbOn(on: Boolean) {
        _reverbOn.value = on
        engine.setReverbOn(on)
    }

    fun setReverbRoom(value: Float) {
        _reverbRoom.value = value
        applyReverb()
    }

    fun setReverbDamp(value: Float) {
        _reverbDamp.value = value
        applyReverb()
    }

    fun setReverbWidth(value: Float) {
        _reverbWidth.value = value
        applyReverb()
    }

    fun setReverbLevel(value: Float) {
        _reverbLevel.value = value
        applyReverb()
    }

    private fun applyReverb() {
        engine.setReverb(_reverbRoom.value, _reverbDamp.value, _reverbWidth.value, _reverbLevel.value)
    }

    fun setChorusOn(on: Boolean) {
        _chorusOn.value = on
        engine.setChorusOn(on)
    }

    fun setChorusVoices(value: Int) {
        _chorusVoices.value = value
        applyChorus()
    }

    fun setChorusLevel(value: Float) {
        _chorusLevel.value = value
        applyChorus()
    }

    fun setChorusSpeed(value: Float) {
        _chorusSpeed.value = value
        applyChorus()
    }

    fun setChorusDepth(value: Float) {
        _chorusDepth.value = value
        applyChorus()
    }

    fun setChorusType(value: Int) {
        _chorusType.value = value
        applyChorus()
    }

    private fun applyChorus() {
        engine.setChorus(_chorusVoices.value, _chorusLevel.value, _chorusSpeed.value, _chorusDepth.value, _chorusType.value)
    }

    fun scanSoundFonts() {
        val context = getApplication<Application>()
        val sfDir = java.io.File(context.filesDir, "soundfonts")
        sfDir.mkdirs()

        val fonts = mutableListOf<SF2Info>()
        val seen = mutableSetOf<String>()

        // Scan internal storage (bundled + previously imported)
        sfDir.listFiles()?.filter {
            it.extension.equals("sf2", ignoreCase = true)
        }?.forEach { file ->
            val isBundled = file.name == DEFAULT_SF
            SF2MetadataReader.readFromFile(file, isBundled)?.let {
                fonts.add(it)
                seen.add(file.name)
            }
        }

        // Scan Downloads folder
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        if (downloadsDir.exists() && downloadsDir.canRead()) {
            downloadsDir.listFiles()?.filter {
                it.extension.equals("sf2", ignoreCase = true) && it.name !in seen
            }?.forEach { file ->
                SF2MetadataReader.readFromFile(file)?.let { fonts.add(it) }
            }
        }

        // Sort: bundled first, then alphabetical by name
        fonts.sortWith(compareByDescending<SF2Info> { it.isBundled }.thenBy { it.name.lowercase() })

        _availableSoundFonts.value = fonts
        Log.i(TAG, "Scanned ${fonts.size} soundfonts")
    }

    fun loadSoundFontByPath(path: String, fileName: String) {
        _isLoading.value = true
        Thread {
            val sfId = engine.loadSoundFont(path)
            if (sfId >= 0) {
                engine.programSelect(0, sfId, 0, 0)
                val info = SF2MetadataReader.readFromFile(java.io.File(path))
                _soundFontName.value = info?.name ?: fileName.removeSuffix(".sf2")
                refreshPresets(sfId)
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

    override fun onCleared() {
        super.onCleared()
        midiManager.destroy()
        engine.stopAudio()
        engine.destroy()
    }
}
