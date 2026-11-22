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
import com.pyano.ui.ServerSettingsDialog
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
            MaterialTheme { ServerSettingsDialog("https://music.example.com", true, "", "", false, { _, _, _, _, _ -> }, {}) }
        }
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

    @Test fun savedTokenIsMaskedRevealableAndPreservedOnSave() {
        var savedToken = ""
        compose.setContent {
            MaterialTheme {
                ServerSettingsDialog("https://music.example.com", true, "owner_test", "", false,
                    { _, _, token, _, complete -> savedToken = token; complete(true) }, {},
                    loadToken = { "stored-test-token" })
            }
        }
        val field = compose.onNodeWithText("Device token")
        field.assertTextContains("•".repeat("stored-test-token".length))
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("Show device token").performScrollTo().performClick()
        field.assertTextContains("stored-test-token")
        compose.onNodeWithContentDescription("Hide device token").performScrollTo().performClick()
        field.assertTextContains("•".repeat("stored-test-token".length))
        compose.onNodeWithText("Use server").performClick()
        compose.runOnIdle { assertEquals("stored-test-token", savedToken) }
    }

    @Test fun changingCloudIdentityClearsSavedToken() {
        compose.setContent {
            MaterialTheme {
                ServerSettingsDialog("https://music.example.com", true, "owner_test", "", false,
                    { _, _, _, _, _ -> }, {}, loadToken = { "stored-test-token" })
            }
        }
        compose.onNodeWithText("Device token").assertTextContains("•".repeat("stored-test-token".length))
        compose.onNodeWithText("Cloud address").performTextReplacement("https://other.example.com")
        compose.onNodeWithText("Use server").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Show device token").performScrollTo().performClick()
        compose.onNodeWithText("Device token").assertTextContains("")
        compose.onNodeWithText("stored-test-token", substring = true).assertDoesNotExist()
    }

    @Test fun tokenRemasksAndReloadsOnBackground() {
        compose.setContent {
            MaterialTheme {
                ServerSettingsDialog("https://music.example.com", true, "owner_test", "", false,
                    { _, _, _, _, _ -> }, {}, loadToken = { cloud -> if (cloud) "cloud-test-token" else "local-test-token" })
            }
        }
        compose.onNodeWithContentDescription("Show device token").performScrollTo().performClick()
        compose.onNodeWithText("Local prototype").assertDoesNotExist()
        compose.onNodeWithText("Cloud", useUnmergedTree = true).assertDoesNotExist()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithContentDescription("Show device token").assertExists().performScrollTo().performClick()
        compose.onNodeWithText("Device token").assertTextContains("cloud-test-token")
    }

    @Test fun legacyLocalSettingsRequireNewCloudCredentials() {
        var loadedCloud: Boolean? = null
        compose.setContent {
            MaterialTheme {
                ServerSettingsDialog("http://127.0.0.1:8765", false, "local-owner", "", false,
                    { _, _, _, _, _ -> }, {}, loadToken = { cloud -> loadedCloud = cloud; "" })
            }
        }
        compose.onNodeWithText("Local prototype").assertDoesNotExist()
        compose.onNodeWithText("Cloud", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("http://127.0.0.1:8765").assertDoesNotExist()
        compose.onNodeWithText("local-owner").assertDoesNotExist()
        compose.onNodeWithText("Saved tokens are masked.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cloud tokens are", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Use server").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(true, loadedCloud) }
    }

    @Test fun importedTokenRefreshesEvenForSameCloudIdentity() {
        var storedToken = "original-test-token"
        compose.setContent {
            MaterialTheme {
                ServerSettingsDialog("https://music.example.com", true, "owner_test", "", false,
                    { _, _, token, _, complete -> storedToken = token; complete(true) }, {}, loadToken = { storedToken })
            }
        }
        compose.onNodeWithContentDescription("Show device token").performScrollTo().performClick()
        compose.onNodeWithText("Paste JSON").performClick()
        compose.onNodeWithText("Provisioning JSON").performTextInput(json)
        compose.onNodeWithText("Review import").performClick()
        compose.onNodeWithText("Save Cloud settings").performClick()
        compose.onNodeWithContentDescription("Show device token").assertExists().performScrollTo().performClick()
        compose.onNodeWithText("Device token").assertTextContains("ui-test-only-token")
    }

}
