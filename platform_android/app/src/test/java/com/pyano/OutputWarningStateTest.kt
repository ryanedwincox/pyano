// Concern: verifies output outage acknowledgements | Non-concern: Android audio devices | IO: (outage episodes) -> warning assertions
package com.pyano

import com.pyano.audio.OutputWarningState
import org.junit.Assert.*
import org.junit.Test

class OutputWarningStateTest {
    @Test fun dismissedOutageStaysHiddenUntilReconnect() {
        val state = OutputWarningState()
        assertEquals("USB missing", state.update("usb", "USB missing"))
        state.dismiss()
        assertNull(state.update("usb", "USB missing; new diagnostics"))
        assertNull(state.update("usb", null))
        assertEquals("USB missing again", state.update("usb", "USB missing again"))
    }

    @Test fun differentPreferredOutputCanWarnAfterDismissal() {
        val state = OutputWarningState()
        state.update("usb", "USB missing")
        state.dismiss()
        assertEquals("Headset missing", state.update("headset", "Headset missing"))
    }
}
