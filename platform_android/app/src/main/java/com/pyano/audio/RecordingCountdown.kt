// Concern: delays recording with cancelable visible state | Non-concern: audio capture | IO: (start, cancel, time) -> countdown and callback
package com.pyano.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecordingCountdown(private val scope: CoroutineScope, private val waitSecond: suspend () -> Unit = { delay(1000) }) {
    private val remaining = MutableStateFlow(0)
    val seconds = remaining.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var owner: Any? = null

    fun start(requester: Any? = null, onReady: () -> Unit) {
        if (remaining.value != 0) return
        owner = requester
        val token = ++generation
        remaining.value = 5
        job = scope.launch {
            try {
                for (second in 5 downTo 1) {
                    remaining.value = second
                    waitSecond()
                    ensureActive()
                }
                remaining.value = 0
                onReady()
            } finally { if (token == generation) remaining.value = 0 }
        }
    }

    fun cancel(requester: Any? = null) {
        if (requester != null && owner !== requester) return
        generation++
        job?.cancel()
        job = null
        remaining.value = 0
    }
}
