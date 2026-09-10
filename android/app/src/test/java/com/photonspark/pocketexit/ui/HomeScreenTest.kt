package com.photonspark.pocketexit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.AgentRuntime
import com.photonspark.pocketexit.data.NetworkKind
import com.photonspark.pocketexit.data.NetworkSnapshot
import com.photonspark.pocketexit.data.Policy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home screen turns three service flags into the five states a person can
 * act on, and offers a different way out of each. connectionState() is covered
 * on its own in ConnectionStateTest; these run the screen itself, because a
 * state that renders the wrong card or wires a button to nothing is invisible
 * to a test of the function alone.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun string(id: Int, vararg formatArgs: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *formatArgs)

    private val config = AgentConfig(
        serverUrl = "https://192.168.1.50:8443",
        nodeId = "pixel-8-a1b2c3d4",
        deviceName = "Pixel 8",
        agentToken = "a-token",
        controlPolicy = Policy.AUTO,
        exitPolicy = Policy.CELLULAR_PREFERRED,
        enabled = true,
        autoStart = false,
        pin = "K4nqR1s5dQ8hV0zXyT2bN7mLp9cJfE3gW6uA0iO4rY8",
    )

    private val usable = NetworkSnapshot(available = true, validated = true)

    private fun runtime(
        running: Boolean = true,
        registered: Boolean = true,
        heartbeatAgeMs: Long = 2_000,
        wifi: NetworkSnapshot = usable,
        cellular: NetworkSnapshot = usable,
        lastError: String = "",
    ) = AgentRuntime(
        running = running,
        registered = registered,
        activeControlNetwork = NetworkKind.WIFI,
        wifi = wifi,
        cellular = cellular,
        lastHeartbeatEpochMs = if (running) System.currentTimeMillis() - heartbeatAgeMs else 0,
        lastError = lastError,
    )

    private fun show(
        runtime: AgentRuntime,
        message: String = "",
        pairedNotice: Boolean = false,
        socks: SocksHint? = null,
        onToggle: () -> Unit = {},
        onReconnect: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenNetworkSettings: () -> Unit = {},
        onDismissNotice: () -> Unit = {},
    ) {
        compose.setContent {
            PocketExitTheme {
                HomeScreen(
                    config = config,
                    runtime = runtime,
                    message = message,
                    pairedNotice = pairedNotice,
                    socks = socks,
                    onToggle = onToggle,
                    onReconnect = onReconnect,
                    onOpenSettings = onOpenSettings,
                    onOpenNetworkSettings = onOpenNetworkSettings,
                    onDismissNotice = onDismissNotice,
                )
            }
        }
    }

    @Test
    fun `a stopped agent offers to start and nothing else`() {
        show(runtime(running = false))

        compose.onNodeWithText(string(R.string.state_stopped_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_start)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_stop)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.home_reconnect)).assertDoesNotExist()
    }

    @Test
    fun `a registered agent with a fresh heartbeat is online`() {
        show(runtime())

        compose.onNodeWithText(string(R.string.state_online_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_stop)).assertIsDisplayed()
    }

    @Test
    fun `a running agent with no usable radio says so instead of claiming to be online`() {
        show(runtime(wifi = NetworkSnapshot(), cellular = NetworkSnapshot()))

        compose.onNodeWithText(string(R.string.state_no_network_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_network_settings)).assertIsDisplayed()
    }

    @Test
    fun `a stale heartbeat is degraded and shows the last error`() {
        show(runtime(heartbeatAgeMs = 90_000, lastError = "server closed the control channel"))

        compose.onNodeWithText(string(R.string.state_degraded_title)).assertIsDisplayed()
        compose
            .onNodeWithText(string(R.string.home_last_error, "server closed the control channel"))
            .assertIsDisplayed()
        compose.onNodeWithText(string(R.string.home_reconnect)).assertIsDisplayed()
    }

    @Test
    fun `the toggle reports which way it was pressed`() {
        var toggles = 0
        show(runtime(running = false), onToggle = { toggles += 1 })

        compose.onNodeWithText(string(R.string.home_start)).performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun `reconnect and settings are separate ways out of a degraded link`() {
        var reconnects = 0
        var settings = 0
        show(
            runtime(heartbeatAgeMs = 90_000),
            onReconnect = { reconnects += 1 },
            onOpenSettings = { settings += 1 },
        )

        compose.onNodeWithText(string(R.string.home_reconnect)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.home_open_settings)).performScrollTo().performClick()

        assertEquals(1, reconnects)
        assertEquals(1, settings)
    }

    @Test
    fun `no network offers the system network settings`() {
        var opened = 0
        show(
            runtime(wifi = NetworkSnapshot(), cellular = NetworkSnapshot()),
            onOpenNetworkSettings = { opened += 1 },
        )

        compose.onNodeWithText(string(R.string.home_network_settings)).performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the paired card names the server and the proxy the computer should use`() {
        show(
            runtime(running = false),
            pairedNotice = true,
            socks = SocksHint("127.0.0.1", 1080, "proxy"),
        )

        compose.onNodeWithText(string(R.string.home_paired_title, "192.168.1.50:8443")).assertIsDisplayed()
        compose
            .onNodeWithText(string(R.string.home_paired_socks, "127.0.0.1", 1080, "proxy"))
            .assertIsDisplayed()
    }

    @Test
    fun `the paired card is dismissible`() {
        var dismissed = 0
        show(runtime(running = false), pairedNotice = true, onDismissNotice = { dismissed += 1 })

        compose.onNodeWithText(string(R.string.home_paired_dismiss)).performScrollTo().performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun `the paired card stays away until there is something to say`() {
        show(runtime(running = false))

        compose.onNodeWithText(string(R.string.home_paired_dismiss)).assertDoesNotExist()
    }

    @Test
    fun `a message is shown only while there is one`() {
        show(runtime(running = false), message = "Starting")

        compose.onNodeWithText("Starting").assertIsDisplayed()
    }
}
