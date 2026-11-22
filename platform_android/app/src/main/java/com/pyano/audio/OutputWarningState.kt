// Concern: acknowledges one output outage | Non-concern: audio routing | IO: (preferred output, availability) -> visible warning
package com.pyano.audio

class OutputWarningState {
    private var currentKey: String? = null
    private var dismissed = false

    fun update(key: String?, problem: String?): String? {
        if (problem == null || key != currentKey) dismissed = false
        currentKey = key
        return problem.takeUnless { dismissed }
    }

    fun dismiss() {
        dismissed = true
    }
}
