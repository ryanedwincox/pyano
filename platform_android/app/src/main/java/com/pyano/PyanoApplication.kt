// PyanoApplication: Owns the one PyanoViewModel every MainActivity instance shares, and clears it when the last one is closed.
// NOT concerned with: what the ViewModel does, or starting the foreground service (MainActivity).
package com.pyano

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Android can run two MainActivity instances at once despite singleTask: one in
 * the home task (Pyano is the home app) and one in a standard task (opened from
 * the launcher or a notification). Per-activity ViewModels gave each its own
 * synth, audio stream and MIDI connection, so every note played twice. The
 * ViewModel lives here instead, one per process.
 */
class PyanoApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    private var liveActivities = 0

    val viewModel: PyanoViewModel
        get() = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(this),
        )[PyanoViewModel::class.java]

    fun activityCreated() {
        liveActivities++
    }

    /** Returns true when the user closed the last activity, so the app should shut down. */
    fun activityDestroyed(finishing: Boolean): Boolean {
        liveActivities--
        if (liveActivities > 0 || !finishing) return false
        viewModelStore.clear()
        return true
    }
}
