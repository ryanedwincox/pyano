// Concern: captures performance MIDI on a monotonic timeline | Non-concern: loop recording | IO: (MIDI events) -> SMF bytes
package com.pyano.audio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class MidiCapture(private val clock: () -> Long = System::nanoTime) {
    private data class Event(val tick: Long, val bytes: ByteArray)
    private val events = mutableListOf<Event>()
    private val controllers = linkedMapOf<Pair<Int, Int>, Int>()
    private val programs = linkedMapOf<Int, Int>()
    private var startNs: Long? = null

    @Synchronized
    fun start() {
        check(startNs == null) { "MIDI capture already started" }
        events.clear()
        startNs = clock()
        controllers.forEach { (key, value) -> add(0xB0 or key.first, key.second, value) }
        programs.forEach { (channel, value) -> add(0xC0 or channel, value) }
    }

    @Synchronized
    fun record(status: Int, first: Int, second: Int? = null) {
        require(status in 0x80..0xEF && first in 0..127 && (second == null || second in 0..127))
        when (status and 0xF0) {
            0xB0 -> controllers[Pair(status and 15, first)] = requireNotNull(second)
            0xC0 -> programs[status and 15] = first
        }
        if (startNs != null) add(status, first, second)
    }

    private fun add(status: Int, first: Int, second: Int? = null) {
        val tick = ((clock() - requireNotNull(startNs)) / 1_000_000L).coerceAtLeast(0)
        val bytes = if (second == null) byteArrayOf(status.toByte(), first.toByte())
                    else byteArrayOf(status.toByte(), first.toByte(), second.toByte())
        events.add(Event(tick.coerceAtLeast(events.lastOrNull()?.tick ?: 0), bytes))
    }

    @Synchronized
    fun finish(): ByteArray? {
        val start = startNs ?: return null
        val endTick = ((clock() - start) / 1_000_000L).coerceAtLeast(events.lastOrNull()?.tick ?: 0)
        val channels = events.map { it.bytes[0].toInt() and 15 }.toSet()
        channels.forEach { channel ->
            events.add(Event(endTick, byteArrayOf((0xB0 or channel).toByte(), 64, 0)))
            events.add(Event(endTick, byteArrayOf((0xB0 or channel).toByte(), 123, 0)))
        }
        startNs = null
        val track = ByteArrayOutputStream()
        track.write(byteArrayOf(0, 0xFF.toByte(), 0x51, 3, 7, 0xA1.toByte(), 0x20))
        var previous = 0L
        events.forEach { event ->
            track.write(variableLength(event.tick - previous))
            track.write(event.bytes)
            previous = event.tick
        }
        track.write(variableLength(endTick - previous))
        track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        events.clear()
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { out ->
            out.writeBytes("MThd"); out.writeInt(6)
            out.writeShort(0); out.writeShort(1); out.writeShort(500)
            out.writeBytes("MTrk"); out.writeInt(track.size()); track.writeTo(out)
        }
        return result.toByteArray()
    }

    @Synchronized
    fun cancel() {
        startNs = null
        events.clear()
    }

    private fun variableLength(value: Long): ByteArray {
        require(value in 0..0x0FFFFFFF) { "MIDI delta exceeds SMF limits" }
        var remaining = value
        val bytes = mutableListOf((remaining and 127).toByte())
        remaining = remaining shr 7
        while (remaining > 0) {
            bytes.add(0, ((remaining and 127) or 128).toByte())
            remaining = remaining shr 7
        }
        return bytes.toByteArray()
    }
}
