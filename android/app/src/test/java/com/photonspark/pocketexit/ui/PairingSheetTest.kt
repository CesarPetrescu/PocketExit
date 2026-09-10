package com.photonspark.pocketexit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.OnboardingLink
import com.photonspark.pocketexit.data.Policy
import com.photonspark.pocketexit.network.PairingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The consent step. docs/pairing-protocol.md says nothing is written and no
 * request is made until the person confirms a sheet showing the origin, the
 * name and the pin — so a sheet that fires its callback without being pressed,
 * or that renders without those three facts, is a security defect and not a
 * cosmetic one. These tests are the check on that.
 */
@RunWith(AndroidJUnit4::class)
class PairingSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int, vararg formatArgs: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *formatArgs)

    private val pin = "K4nqR1s5dQ8hV0zXyT2bN7mLp9cJfE3gW6uA0iO4rY8"

    private val link = OnboardingLink.Pairing(
        serverUrl = "https://192.168.1.50:8443",
        pairingCode = "A1B2C3D4",
        pin = pin,
        serverName = "Cesar's laptop",
    )

    private var confirmed: OnboardingLink.Pairing? = null
    private var imported: AgentConfig? = null
    private var dismissals = 0

    private fun show(flow: PairingFlow) {
        compose.setContent {
            PocketExitTheme {
                PairingSheet(
                    flow = flow,
                    deviceName = "Pixel 8",
                    onConfirmPairing = { confirmed = it },
                    onConfirmImport = { imported = it },
                    onDismiss = { dismissals += 1 },
                )
            }
        }
    }

    @Test
    fun `idle shows nothing at all`() {
        show(PairingFlow.Idle)

        compose.onNodeWithText(string(R.string.pair_confirm_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.pair_working_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.pair_failed_title)).assertDoesNotExist()
    }

    @Test
    fun `the confirmation names the origin, the server and the pin`() {
        show(PairingFlow.Confirm(link))

        compose.onNodeWithText(string(R.string.pair_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText("192.168.1.50:8443").assertIsDisplayed()
        compose.onNodeWithText("Cesar's laptop").assertIsDisplayed()
        compose.onNodeWithText(shortPin(pin)).assertIsDisplayed()
        // The full pin is there too, so it can be read against the screen the
        // QR came from rather than trusted from its first characters.
        compose.onNodeWithText(string(R.string.pair_pin_full, groupedPin(pin))).assertIsDisplayed()
    }

    @Test
    fun `the confirmation says which phone is about to be handed over`() {
        show(PairingFlow.Confirm(link))

        compose
            .onNodeWithText(string(R.string.pair_consent, "Pixel 8", "192.168.1.50:8443"))
            .assertIsDisplayed()
    }

    @Test
    fun `nothing is claimed until the person confirms`() {
        show(PairingFlow.Confirm(link))

        assertNull(confirmed)

        compose.onNodeWithText(string(R.string.pair_confirm_action)).performClick()

        assertEquals(link, confirmed)
    }

    @Test
    fun `cancelling a confirmation claims nothing`() {
        show(PairingFlow.Confirm(link))

        compose.onNodeWithText(string(R.string.action_cancel)).performClick()

        assertNull(confirmed)
        assertEquals(1, dismissals)
    }

    // A link that names no pin falls back to the platform trust store, which on
    // a LAN address is a materially weaker promise. The sheet has to say so.
    @Test
    fun `a link with no pin warns instead of quietly using the platform store`() {
        show(PairingFlow.Confirm(link.copy(pin = "")))

        compose.onNodeWithText(string(R.string.pair_pin_none)).assertIsDisplayed()
    }

    @Test
    fun `a link with no name says so rather than showing an empty row`() {
        show(PairingFlow.Confirm(link.copy(serverName = "")))

        compose.onNodeWithText(string(R.string.pair_no_name)).assertIsDisplayed()
    }

    // The code is single use, so a half-finished claim must not be cancellable
    // into a state where nobody knows whether it was spent.
    @Test
    fun `a claim in flight offers no way out`() {
        show(PairingFlow.Working(link))

        compose.onNodeWithText(string(R.string.pair_working_title)).assertIsDisplayed()
        compose
            .onNodeWithContentDescription(string(R.string.pair_working_description))
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_cancel)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.pair_confirm_action)).assertDoesNotExist()
    }

    @Test
    fun `a rejected code says so and offers a retry`() {
        show(
            PairingFlow.Failed(
                link,
                PairingClient.Result.Failed(PairingClient.Failure.CODE_REJECTED, ""),
            ),
        )

        compose.onNodeWithText(string(R.string.pair_error_code_rejected)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.pair_retry)).performClick()

        assertEquals(link, confirmed)
    }

    // "That code was not accepted" and "that code expired four minutes ago"
    // lead to different next moves, so the server's own words are kept.
    @Test
    fun `the server's detail is kept alongside the mapped reason`() {
        show(
            PairingFlow.Failed(
                link,
                PairingClient.Result.Failed(
                    PairingClient.Failure.RATE_LIMITED,
                    "try again in 6 minutes",
                ),
            ),
        )

        compose
            .onNodeWithText(
                string(
                    R.string.pair_error_detail,
                    string(R.string.pair_error_rate_limited),
                    "try again in 6 minutes",
                ),
            )
            .assertIsDisplayed()
    }

    @Test
    fun `an unexpected status carries the number so it can be reported`() {
        show(
            PairingFlow.Failed(
                link,
                PairingClient.Result.Failed(PairingClient.Failure.UNEXPECTED_STATUS, "", 503),
            ),
        )

        compose
            .onNodeWithText(string(R.string.pair_error_unexpected_status, 503))
            .assertIsDisplayed()
    }

    @Test
    fun `a version one link is imported only after its own confirmation`() {
        val config = AgentConfig(
            serverUrl = "https://exit.example.com",
            nodeId = "s24u",
            deviceName = "Pixel 8",
            agentToken = "a-token",
            controlPolicy = Policy.AUTO,
            exitPolicy = Policy.AUTO,
            enabled = false,
            autoStart = false,
        )
        show(PairingFlow.Import(config))

        compose.onNodeWithText("exit.example.com").assertIsDisplayed()
        compose.onNodeWithText("s24u").assertIsDisplayed()
        assertNull(imported)

        compose.onNodeWithText(string(R.string.pair_import_action)).performClick()

        assertEquals(config, imported)
    }

    @Test
    fun `an unparsable link shows the parser's own reason and only closes`() {
        show(PairingFlow.Rejected("Unknown field in onboarding link"))

        compose.onNodeWithText("Unknown field in onboarding link").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_close)).performClick()

        assertNull(confirmed)
        assertNull(imported)
        assertEquals(1, dismissals)
    }
}
