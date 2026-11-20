// Concern: verifies private import confirmation UI | Non-concern: live camera decoding | IO: (paste actions) -> assertions
package com.pyano

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.pyano.ui.LocalSyncPanel
import com.pyano.ui.CloudProvisioningImport
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CloudProvisioningUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val json = """{"version":1,"endpoint":"https://music.example.com","ownerId":"owner_test","token":"ui-test-only-token"}"""

    @Before fun showSyntheticUiAboveKeyguard() {
        compose.activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    @Test fun pasteConfirmsNonsecretIdentityAndCancelDoesNotSave() {
        var saves = 0
        compose.setContent { MaterialTheme { CloudProvisioningImport(false) { _, _, _, _, _ -> saves++ } } }
        compose.onNodeWithText("Paste JSON").performClick()
        compose.onNodeWithText("Provisioning JSON").performTextInput(json)
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText("Review import").performClick()
        compose.onNodeWithText("Cloud: https://music.example.com\nOwner: owner_test").assertIsDisplayed()
        compose.onNodeWithText("ui-test-only-token", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun saveFailureClearsPendingSecretAndShowsRetryGuidance() {
        var saves = 0
        compose.setContent {
            MaterialTheme { CloudProvisioningImport(false) { _, _, _, _, complete -> saves++; complete(false) } }
        }
        compose.onNodeWithText("Paste JSON").performClick()
        compose.onNodeWithText("Provisioning JSON").performTextInput(json)
        compose.onNodeWithText("Review import").performClick()
        compose.onNodeWithText("Save Cloud settings").performClick()
        compose.onNodeWithText("Could not save Cloud settings. Try importing again.").assertIsDisplayed()
        compose.onNodeWithText("Confirm Cloud settings").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun hostRestorationKeepsImportControlsButDiscardsPendingCredentials() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { LocalSyncPanel("https://music.example.com", false, "", "", false) { _, _, _, _, _ -> } }
        }
        compose.onNodeWithText("Server settings").performClick()
        compose.onNodeWithText("Cloud").performClick()
        compose.onNodeWithText("Paste JSON").performClick()
        compose.onNodeWithText("Provisioning JSON").performTextInput(json)
        compose.onNodeWithText("Review import").performClick()
        compose.onNodeWithText("Confirm Cloud settings").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Scan QR").assertExists()
        compose.onNodeWithText("Paste JSON").assertExists()
        compose.onNodeWithText("Confirm Cloud settings").assertDoesNotExist()
        compose.onNodeWithText("ui-test-only-token", substring = true).assertDoesNotExist()
    }

}
