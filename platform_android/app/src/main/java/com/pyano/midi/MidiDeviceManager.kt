// MidiDeviceManager: Discovers, connects, and disconnects Android MIDI input devices, routing them to a receiver.
// NOT concerned with: parsing MIDI bytes, audio synthesis, or UI state.
package com.pyano.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class MidiDeviceManager(context: Context) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())
    private var openDevice: MidiDevice? = null
    private var deviceCallback: MidiManager.DeviceCallback? = null

    companion object {
        private const val TAG = "Pyano"
    }

    fun getDevices(): List<MidiDeviceInfo> {
        return midiManager?.devices?.filter {
            it.type == MidiDeviceInfo.TYPE_USB
        } ?: emptyList()
    }

    fun getDeviceName(info: MidiDeviceInfo): String {
        val props = info.properties
        val name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Unknown MIDI Device"
        return name
    }

    fun openDevice(info: MidiDeviceInfo, onOpened: (MidiDevice?) -> Unit) {
        closeDevice()
        midiManager?.openDevice(info, { device ->
            openDevice = device
            if (device != null) {
                Log.i(TAG, "MIDI device opened: ${getDeviceName(info)}")
            } else {
                Log.e(TAG, "Failed to open MIDI device: ${getDeviceName(info)}")
            }
            onOpened(device)
        }, handler)
    }

    fun closeDevice() {
        openDevice?.close()
        openDevice = null
    }

    fun registerCallback(
        onDeviceAdded: (MidiDeviceInfo) -> Unit,
        onDeviceRemoved: (MidiDeviceInfo) -> Unit
    ) {
        if (midiManager == null) return
        deviceCallback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                Log.i(TAG, "MIDI device connected: ${getDeviceName(device)}")
                onDeviceAdded(device)
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                Log.i(TAG, "MIDI device disconnected: ${getDeviceName(device)}")
                onDeviceRemoved(device)
            }
        }
        midiManager.registerDeviceCallback(deviceCallback, handler)
    }

    fun unregisterCallback() {
        deviceCallback?.let { midiManager?.unregisterDeviceCallback(it) }
        deviceCallback = null
    }

    fun destroy() {
        unregisterCallback()
        closeDevice()
    }
}
