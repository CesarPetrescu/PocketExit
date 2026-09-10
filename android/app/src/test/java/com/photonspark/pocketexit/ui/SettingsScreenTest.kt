package com.photonspark.pocketexit.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The manual form. It edits a configuration it does not own — every change is
 * handed back through onFormChanged — and it guards the one destructive action
 * in the app behind a confirmation, so both of those are what is asserted.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int, vararg formatArgs: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *formatArgs)

    private val paired = AgentConfig(
        serverUrl = "https://192.168.1.50:8443",
        nodeId = "pixel-8-a1b2c3d4",
        deviceName = "Pixel 8",
        agentToken = "a-token",
        controlPolicy = Policy.AUTO,
        exitPolicy = Policy.CELLULAR_PREFERRED,
        enabled = false,
        autoStart = false,
        pin = "K4nqR1s5dQ8hV0zXyT2bN7mLp9cJfE3gW6uA0iO4rY8",
    )

    private var edited: AgentConfig? = null

    /** Matches on the text a field actually draws, not the value behind it. */
    private fun renders(value: String) = SemanticsMatcher("the field renders '$value'") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text == value
    }

    private fun show(
        form: AgentConfig = paired,
        isPaired: Boolean = true,
        running: Boolean = false,
        message: String = "",
        onSave: () -> Unit = {},
        onUnpair: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            PocketExitTheme {
                SettingsScreen(
                    form = form,
                    paired = isPaired,
                    running = running,
                    message = message,
                    onFormChanged = { edited = it },
                    onSave = onSave,
                    onUnpair = onUnpair,
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun `shows the stored configuration`() {
        show()

        compose.onNodeWithText("https://192.168.1.50:8443").assertIsDisplayed()
        compose.onNodeWithText("pixel-8-a1b2c3d4").assertIsDisplayed()
        compose.onNodeWithText("Pixel 8").assertIsDisplayed()
    }

    // The token is a secret and the pin is not, so they are presented
    // differently: one masked behind a reveal, the other shown in full. The
    // assertion is on what the field renders rather than on the value it
    // holds, because onNodeWithText also matches the unmasked InputText and
    // would pass on a field that shows the token in the clear.
    @Test
    fun `masks the agent token until it is revealed`() {
        show()
        // PasswordVisualTransformation replaces each character with a bullet.
        val masked = "\u2022".repeat(paired.agentToken.length)

        compose.onNode(renders(masked)).assertExists()
        compose.onNode(renders(paired.agentToken)).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.settings_token_show)).performScrollTo().performClick()

        compose.onNode(renders(paired.agentToken)).assertExists()
        compose.onNodeWithText(string(R.string.settings_token_hide)).assertIsDisplayed()
    }

    @Test
    fun `shows the pin grouped, and names the platform store when there is none`() {
        show()

        compose.onNodeWithText(groupedPin(paired.pin)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a server-mode configuration says the platform store decides`() {
        show(form = paired.copy(pin = ""))

        compose.onNodeWithText(string(R.string.settings_pin_platform)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every edit is handed back rather than kept`() {
        show()

        compose
            .onAllNodes(hasSetTextAction())[0]
            .performScrollTo()
            .performTextReplacement("https://10.0.0.4:8443")

        assertEquals("https://10.0.0.4:8443", edited?.serverUrl)
        // The rest of the configuration rides along unchanged.
        assertEquals(paired.nodeId, edited?.nodeId)
        assertEquals(paired.pin, edited?.pin)
    }

    @Test
    fun `the routing pickers offer every policy and report the one chosen`() {
        show()

        compose
            .onNodeWithContentDescription(
                string(R.string.settings_policy_menu, string(R.string.settings_exit_policy), Policy.CELLULAR_PREFERRED.label),
            )
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(Policy.WIFI_ONLY.label).performClick()

        assertEquals(Policy.WIFI_ONLY, edited?.exitPolicy)
        assertEquals(paired.controlPolicy, edited?.controlPolicy)
    }

    @Test
    fun `the start-on-boot switch reports the new position`() {
        show()

        compose.onNodeWithText(string(R.string.settings_auto_start)).performScrollTo()
        compose.onNode(isToggleable()).assertIsOff().performClick()

        assertEquals(true, edited?.autoStart)
    }

    @Test
    fun `the save button says whether saving will restart the agent`() {
        show(running = true)

        compose.onNodeWithText(string(R.string.settings_save_restart)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_save)).assertDoesNotExist()
    }

    // Unpairing throws away the token and cannot be undone from here, so it is
    // the one action that asks first.
    @Test
    fun `unpairing asks before it happens`() {
        var unpaired = 0
        show(onUnpair = { unpaired += 1 })

        compose.onNodeWithText(string(R.string.settings_unpair)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.settings_unpair_confirm_title)).assertIsDisplayed()
        assertEquals(0, unpaired)

        compose.onNodeWithText(string(R.string.action_cancel)).performClick()
        compose.onNodeWithText(string(R.string.settings_unpair_confirm_title)).assertDoesNotExist()
        assertEquals(0, unpaired)
    }

    @Test
    fun `confirming the unpair reports it once`() {
        var unpaired = 0
        show(onUnpair = { unpaired += 1 })

        compose.onNodeWithText(string(R.string.settings_unpair)).performScrollTo().performClick()
        compose.onAllNodesWithText(string(R.string.settings_unpair)).onLast().performClick()

        assertEquals(1, unpaired)
    }

    @Test
    fun `an unpaired phone is offered the manual explanation and no unpair button`() {
        show(form = paired.copy(agentToken = "", pin = ""), isPaired = false)

        compose.onNodeWithText(string(R.string.settings_manual_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.settings_unpair)).assertDoesNotExist()
    }

    @Test
    fun `the back arrow leaves without saving`() {
        var back = 0
        show(onBack = { back += 1 })

        compose.onNodeWithContentDescription(string(R.string.settings_back)).performClick()

        assertEquals(1, back)
        assertNull(edited)
    }
}
