// Concern: keeps the display awake while playing | Non-concern: screen locking | IO: (note activity) -> wake lock
package com.pyano

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** Refresh a timed wake lock on notes; TURN_SCREEN_ON app-op is required to wake the screen, not merely keep it awake. */
class KioskController(context: Context) {

    @Suppress("DEPRECATION") // SCREEN_BRIGHT/ACQUIRE_CAUSES_WAKEUP: no non-activity replacement
    private val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "Pyano:playing",
    ).apply { setReferenceCounted(false) }

    @Volatile private var lastRefreshMs = Long.MIN_VALUE / 2

    /** Last note time, initially startup time. */
    @Volatile var lastNoteMs = SystemClock.elapsedRealtime()
        private set

    /** Refresh from the MIDI thread on note-on. */
    fun onNotePlayed() {
        val now = SystemClock.elapsedRealtime()
        lastNoteMs = now
        if (now - lastRefreshMs < REFRESH_INTERVAL_MS) return
        lastRefreshMs = now
        try {
            // Non-reference-counted: re-acquiring restarts the timeout (and wakes if off).
            wakeLock.acquire(AWAKE_AFTER_LAST_NOTE_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "Screen wake lock refused", e)
        }
    }

    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        private const val TAG = "Pyano"
        const val AWAKE_AFTER_LAST_NOTE_MS = 5 * 60 * 1000L
        // Limit Binder calls; power-button sleep wakes on the next note after this interval.
        private const val REFRESH_INTERVAL_MS = 2_000L
    }
}
