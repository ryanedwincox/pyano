// DeviceDiagnostics: Records USB, audio-output, MIDI, charging and screen events to a persistent log and explains why an output is missing.
// NOT concerned with: choosing or binding outputs (PyanoViewModel), or fixing connections (Android exposes no USB reset).
package com.pyano

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One line of the connection history. [kind] drives the colour in the UI. */
data class DiagEvent(val timeMs: Long, val kind: Kind, val text: String) {
    enum class Kind { Connected, Disconnected, Power, Info }
}

/** A device on the USB bus right now, as the kernel enumerated it. */
data class UsbDeviceStatus(
    val name: String,
    val vidPid: String,
    /** /dev/bus/usb/BBB/DDD: DDD counts up on every (re)enumeration since boot. */
    val path: String,
    val power: String,
    val functions: String,
)

/**
 * Answers "is it the cable, the hub, Android or Pyano?" without replugging.
 * A USB audio interface goes through three layers before Pyano can play to it:
 * the kernel enumerates it on the bus (USB attach), Android's audio service
 * opens it (audio output added), then Pyano binds the stream. Recording each
 * layer's events separately shows which one failed, and the device number in
 * the bus path shows how many times things reconnected since boot. Charging
 * and screen changes are recorded too: USB-C hubs with power pass-through
 * often reset their ports when the charger renegotiates.
 *
 * The log is a file so it survives the restarts and reboots it is meant to explain.
 */
class DeviceDiagnostics(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val logFile = File(context.filesDir, "device-events.log")

    private val _events = MutableStateFlow(loadEvents())
    val events = _events.asStateFlow()

    private val _usbDevices = MutableStateFlow<List<UsbDeviceStatus>>(emptyList())
    val usbDevices = _usbDevices.asStateFlow()

    /** Bumped on every USB change so derived state (the output verdict) recomputes. */
    private val _usbGeneration = MutableStateFlow(0)
    val usbGeneration = _usbGeneration.asStateFlow()

    private var lastPlugged = -1
    private var lastStatus = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val attached = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val text = device?.let { describe(status(it)) } ?: "unknown device"
                    record(
                        if (attached) DiagEvent.Kind.Connected else DiagEvent.Kind.Disconnected,
                        (if (attached) "USB attached: " else "USB detached: ") + text,
                    )
                    refreshUsb()
                }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                Intent.ACTION_SCREEN_ON -> record(DiagEvent.Kind.Info, "Screen on")
                Intent.ACTION_SCREEN_OFF -> record(DiagEvent.Kind.Info, "Screen off")
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshUsb()
        val present = _usbDevices.value.joinToString("; ") { describe(it) }.ifEmpty { "none" }
        record(DiagEvent.Kind.Info, "Pyano started. USB devices: $present")
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // never registered
        }
    }

    fun record(kind: DiagEvent.Kind, text: String) {
        val event = DiagEvent(System.currentTimeMillis(), kind, text)
        Log.i(TAG, "Diag: $text")
        synchronized(this) {
            _events.value = (_events.value + event).takeLast(MAX_EVENTS)
            try {
                logFile.appendText("${event.timeMs}\t${kind.name}\t${text.replace('\n', ' ')}\n")
            } catch (e: Exception) {
                Log.w(TAG, "Diag log write failed", e)
            }
        }
    }

    fun clear() {
        synchronized(this) {
            _events.value = emptyList()
            logFile.delete()
        }
    }

    fun refreshUsb() {
        _usbDevices.value = usbManager?.deviceList?.values
            ?.map { status(it) }
            ?.sortedBy { it.path }
            ?: emptyList()
        _usbGeneration.value++
    }

    /** USB attaches of [productName] within [windowMs], to flag a connection that keeps bouncing. */
    fun recentAttaches(productName: String, windowMs: Long = FLAP_WINDOW_MS): Int {
        val since = System.currentTimeMillis() - windowMs
        return _events.value.count {
            it.timeMs >= since && it.kind == DiagEvent.Kind.Connected &&
                it.text.startsWith("USB attached: $productName ")
        }
    }

    /** When [productName] last left the USB bus, if it did since the log began. */
    fun lastDetachMs(productName: String): Long? =
        _events.value.lastOrNull {
            it.kind == DiagEvent.Kind.Disconnected && it.text.startsWith("USB detached: $productName ")
        }?.timeMs

    private fun onBattery(intent: Intent) {
        // Sticky and sent every ~30 s; only transitions are interesting.
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (plugged == lastPlugged && status == lastStatus) return
        val first = lastPlugged == -1
        lastPlugged = plugged
        lastStatus = status
        if (first) return  // the startup line already covers the initial state
        val source = when (plugged) {
            0 -> "unplugged"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "source $plugged"
        }
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            else -> "status $status"
        }
        record(DiagEvent.Kind.Power, "Power: $source, $state, $level%")
    }

    private fun status(device: UsbDevice): UsbDeviceStatus {
        val config = if (device.configurationCount > 0) device.getConfiguration(0) else null
        val power = when {
            config == null -> "power unknown"
            config.isSelfPowered -> "own power"
            else -> "bus powered ${config.maxPower} mA"
        }
        val classes = (0 until device.interfaceCount).map { device.getInterface(it) }
            .mapNotNull {
                when {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 2 -> "audio"
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 3 -> "MIDI"
                    it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE -> "storage"
                    it.interfaceClass == UsbConstants.USB_CLASS_HUB -> "hub"
                    it.interfaceClass == 0x11 -> "billboard"
                    else -> null
                }
            }.distinct()
        return UsbDeviceStatus(
            name = device.productName?.trim()?.ifEmpty { null } ?: device.deviceName,
            vidPid = "%04x:%04x".format(device.vendorId, device.productId),
            path = device.deviceName,
            power = power,
            functions = classes.joinToString("+").ifEmpty { "other" },
        )
    }

    private fun describe(s: UsbDeviceStatus) =
        "${s.name} (${s.functions}, ${s.vidPid}, #${s.path.substringAfterLast('/')}, ${s.power})"

    private fun loadEvents(): List<DiagEvent> = try {
        if (!logFile.exists()) emptyList() else {
            val lines = logFile.readLines()
            if (lines.size > MAX_FILE_LINES) logFile.writeText(lines.takeLast(MAX_EVENTS).joinToString("\n", postfix = "\n"))
            lines.takeLast(MAX_EVENTS).mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val kind = DiagEvent.Kind.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return@mapNotNull null
                DiagEvent(time, kind, parts.getOrElse(2) { "" })
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Diag log read failed", e)
        emptyList()
    }

    companion object {
        private const val TAG = "Pyano"
        private const val MAX_EVENTS = 500
        private const val MAX_FILE_LINES = 5000
        const val FLAP_WINDOW_MS = 10 * 60 * 1000L
    }
}
