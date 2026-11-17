// Concern: owns the shared process ViewModel | Non-concern: foreground notification | IO: (activity lifecycle) -> shared state
package com.pyano

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Home and launcher tasks can coexist despite singleTask; share one ViewModel to prevent duplicate synths and MIDI input. */
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
