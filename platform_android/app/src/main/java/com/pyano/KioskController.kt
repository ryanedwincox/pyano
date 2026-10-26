// KioskController: Keeps the screen on while the keyboard is being played and wakes it on a key press.
// NOT concerned with: locking (the system lock screen does that), MIDI parsing, or which activity is shown.
package com.pyano

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Touches are the only thing Android counts as activity, so on its own the screen
 * turns off mid-performance and a key press doesn't wake it. Each played note
 * (re)acquires a screen wake lock that also wakes the device, held for
 * [AWAKE_AFTER_LAST_NOTE_MS]. When it expires the normal screen timeout and
 * lock screen take over. MainActivity shows over the lock screen, so the wake
 * lands on Pyano while everything else stays behind the PIN.
 *
 * Waking the screen from the background needs the TURN_SCREEN_ON app op
 * (`adb shell appops set com.pyano TURN_SCREEN_ON allow`); without it the lock
 * still keeps the screen on while playing but can't turn it on.
 */
class KioskController(context: Context) {

    @Suppress("DEPRECATION") // SCREEN_BRIGHT/ACQUIRE_CAUSES_WAKEUP: no non-activity replacement
    private val wakeLock = context.getSystemService(PowerManager::class.java).newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "Pyano:playing",
    ).apply { setReferenceCounted(false) }

    @Volatile private var lastRefreshMs = Long.MIN_VALUE / 2

    /** elapsedRealtime of the last played note (or of startup). */
    @Volatile var lastNoteMs = SystemClock.elapsedRealtime()
        private set

    /** Called from the MIDI thread on every note-on; cheap unless a refresh is due. */
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
        // Binder call per refresh; at most this often. A screen put to sleep with the
        // power button mid-phrase wakes on the next note after this.
        private const val REFRESH_INTERVAL_MS = 2_000L
    }
}
