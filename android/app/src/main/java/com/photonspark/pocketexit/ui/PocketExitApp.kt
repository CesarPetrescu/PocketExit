package com.photonspark.pocketexit.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.photonspark.pocketexit.R
import com.photonspark.pocketexit.data.AgentConfig
import com.photonspark.pocketexit.data.AppPreferences
import com.photonspark.pocketexit.data.OnboardingLink
import com.photonspark.pocketexit.data.RuntimeStore
import com.photonspark.pocketexit.data.parseOnboardingUri
import com.photonspark.pocketexit.network.NetworkMonitor
import com.photonspark.pocketexit.network.PairingClient
import com.photonspark.pocketexit.service.ExitNodeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private enum class Screen { WELCOME, HOME, SETTINGS }

/**
 * The whole app in one state machine. Which screen the person sees is derived
 * from whether a usable configuration exists rather than remembered, so a fresh
 * install opens on pairing and a successful claim moves to the home screen
 * without anyone navigating.
 */
@Composable
internal fun PocketExitApp(
    preferences: AppPreferences,
    onboardingUri: String?,
    onOnboardingConsumed: () -> Unit,
) {
    val context = LocalContext.current
    // The claim outlives this composition, so it is handed the application
    // context rather than the activity it was started from.
    val appContext = remember(context) { context.applicationContext }
    val savedConfig by preferences.state.collectAsState()
    val runtime by RuntimeStore.state.collectAsState()

    var form by remember { mutableStateOf(savedConfig) }
    var message by remember { mutableStateOf("") }
    var requestedScreen by rememberSaveable { mutableStateOf("") }
    var pairedNotice by rememberSaveable { mutableStateOf(false) }

    // The proxy hint the claim answered with. Display only: it carries no
    // password, and nothing on this phone connects to it.
    var socksHost by rememberSaveable { mutableStateOf("") }
    var socksPort by rememberSaveable { mutableStateOf(0) }
    var socksUsername by rememberSaveable { mutableStateOf("") }

    // The link is kept as the raw URI rather than a parsed object so the
    // confirmation survives a rotation; nothing about it is written anywhere.
    var pendingUri by rememberSaveable { mutableStateOf("") }
    var claiming by rememberSaveable { mutableStateOf(false) }
    // Every confirmation is its own attempt, so retrying after a failure starts
    // a fresh claim instead of re-reading the one that already failed.
    var attempt by rememberSaveable { mutableStateOf(0) }
    // Not saved: the claim itself is the record of what happened, and a
    // recreated composition is told again by awaiting it.
    var failure by remember { mutableStateOf<PairingClient.Result.Failed?>(null) }

    val configured = savedConfig.validationError() == null
    val paired = savedConfig.agentToken.isNotBlank()
    val screen = when {
        requestedScreen.isNotEmpty() -> Screen.valueOf(requestedScreen)
        configured -> Screen.HOME
        else -> Screen.WELCOME
    }

    val parsed = remember(pendingUri) {
        pendingUri.takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { savedConfig.parseOnboardingUri(raw) }
        }
    }
    val unreadable = stringResource(R.string.pair_link_unreadable)

    // Resolved in composition rather than through LocalContext inside the
    // handlers below. A string read from the context does not recompose when
    // the configuration changes, so after a system language switch the banner
    // would keep showing the previous locale's text.
    val messageStopping = stringResource(R.string.message_stopping)
    val messageStarting = stringResource(R.string.message_starting)
    val messageReconnecting = stringResource(R.string.message_reconnecting)
    val messageNoNetworkSettings = stringResource(R.string.message_no_network_settings)
    val settingsSaved = stringResource(R.string.settings_saved)
    val settingsSavedRestart = stringResource(R.string.settings_saved_restart)
    val settingsUnpaired = stringResource(R.string.settings_unpaired)
    val link = parsed?.getOrNull()
    val currentFailure = failure
    val flow: PairingFlow = when {
        parsed == null -> PairingFlow.Idle
        // parsed is non-null here: the branch above returned when it was not.
        link == null -> PairingFlow.Rejected(parsed.exceptionOrNull()?.message ?: unreadable)
        link is OnboardingLink.Configured -> PairingFlow.Import(link.config)
        link is OnboardingLink.Pairing && claiming -> PairingFlow.Working(link)
        link is OnboardingLink.Pairing && currentFailure != null ->
            PairingFlow.Failed(link, currentFailure)
        link is OnboardingLink.Pairing -> PairingFlow.Confirm(link)
        else -> PairingFlow.Idle
    }
    val socks = if (socksHost.isNotEmpty() && socksPort > 0 && socksUsername.isNotEmpty()) {
        SocksHint(socksHost, socksPort, socksUsername)
    } else {
        null
    }

    LaunchedEffect(savedConfig) { form = savedConfig }
    LaunchedEffect(onboardingUri) {
        if (onboardingUri != null) {
            pendingUri = onboardingUri
            claiming = false
            failure = null
            onOnboardingConsumed()
        }
    }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            delay(3_500)
            message = ""
        }
    }
    // The claim is owned by PairingClaims rather than by this effect, so an
    // activity recreation mid-request re-attaches to the running job instead of
    // spending the single-use code a second time.
    LaunchedEffect(pendingUri, claiming, attempt) {
        if (!claiming) return@LaunchedEffect
        val pairing = link as? OnboardingLink.Pairing
        if (pairing == null) {
            claiming = false
            return@LaunchedEffect
        }
        val key = "${pairing.serverUrl}|${pairing.pairingCode}|$attempt"
        val onLan = AgentConfig.hasPrivateHost(pairing.serverUrl)
        val pendingClaim = PairingClaims.claim(key) {
            // A laptop on the LAN answers over Wi-Fi, not over whatever the
            // phone calls its default route, and it answers there whether or
            // not that Wi-Fi has an Internet path behind it.
            val network = NetworkMonitor.awaitWifi(appContext, requireValidated = !onLan)
            PairingClient(pairing.serverUrl, pairing.pin, network)
                .claim(pairing.pairingCode, Build.MODEL)
        }
        val result = try {
            pendingClaim.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            PairingClient.Result.Failed(
                PairingClient.Failure.UNREACHABLE,
                error.message.orEmpty(),
            )
        }
        when (result) {
            is PairingClient.Result.Paired -> {
                if (runtime.running) ExitNodeService.stop(context)
                // The pin stored is the one shown on the sheet and verified
                // against the computer's screen, never anything the response
                // claimed for itself.
                preferences.save(
                    savedConfig.copy(
                        serverUrl = result.serverUrl,
                        nodeId = result.nodeId,
                        agentToken = result.agentToken,
                        pin = pairing.pin,
                        enabled = false,
                    ),
                    // The server assigned this id; it is stored exactly as it
                    // arrived so the phone keeps matching the registry.
                    sanitizeNodeId = false,
                )
                socksHost = result.socksHost
                socksPort = result.socksPort
                socksUsername = result.socksUsername
                claiming = false
                failure = null
                pendingUri = ""
                pairedNotice = true
                requestedScreen = ""
                PairingClaims.forget(key)
            }
            is PairingClient.Result.Failed -> {
                failure = result
                claiming = false
            }
        }
    }

    when (screen) {
        Screen.WELCOME -> PairingScreen(
            message = message,
            onManualSetup = { requestedScreen = Screen.SETTINGS.name },
        )

        Screen.HOME -> HomeScreen(
            config = savedConfig,
            runtime = runtime,
            message = message,
            pairedNotice = pairedNotice,
            socks = socks,
            onToggle = {
                if (runtime.running) {
                    val disabled = savedConfig.copy(enabled = false)
                    preferences.save(disabled)
                    form = disabled
                    ExitNodeService.stop(context)
                    message = messageStopping
                } else {
                    val enabled = savedConfig.copy(enabled = true)
                    val error = enabled.validationError()
                    if (error == null) {
                        preferences.save(enabled)
                        form = enabled
                        ExitNodeService.start(context, restart = false)
                        pairedNotice = false
                        message = messageStarting
                    } else {
                        message = error
                        requestedScreen = Screen.SETTINGS.name
                    }
                }
            },
            onReconnect = {
                ExitNodeService.start(context, restart = true)
                message = messageReconnecting
            },
            onOpenSettings = { requestedScreen = Screen.SETTINGS.name },
            onOpenNetworkSettings = {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isFailure) {
                    message = messageNoNetworkSettings
                }
            },
            onDismissNotice = { pairedNotice = false },
        )

        Screen.SETTINGS -> SettingsScreen(
            form = form,
            paired = paired,
            running = runtime.running,
            message = message,
            onFormChanged = { form = it },
            onSave = {
                val updated = form.copy(enabled = runtime.running)
                val error = updated.validationError()
                if (error == null) {
                    preferences.save(updated)
                    form = updated
                    if (runtime.running) ExitNodeService.start(context, restart = true)
                    message = if (runtime.running) settingsSavedRestart else settingsSaved
                } else {
                    message = error
                }
            },
            onUnpair = {
                if (runtime.running) ExitNodeService.stop(context)
                val cleared = savedConfig.copy(
                    agentToken = "",
                    pin = "",
                    enabled = false,
                    autoStart = false,
                )
                preferences.save(cleared)
                form = cleared
                pairedNotice = false
                socksHost = ""
                socksPort = 0
                socksUsername = ""
                requestedScreen = ""
                message = settingsUnpaired
            },
            onBack = { requestedScreen = "" },
        )
    }

    PairingSheet(
        flow = flow,
        deviceName = Build.MODEL,
        onConfirmPairing = {
            failure = null
            attempt += 1
            claiming = true
        },
        onConfirmImport = { candidate: AgentConfig ->
            if (runtime.running) ExitNodeService.stop(context)
            // The link's own fields are applied to the configuration as it
            // stands now. The parse ran against whatever was stored when the
            // sheet opened, so writing that copy back would undo anything the
            // server changed while the person was reading it.
            val imported = savedConfig.copy(
                serverUrl = candidate.serverUrl,
                nodeId = candidate.nodeId,
                agentToken = candidate.agentToken,
                pin = candidate.pin,
                enabled = candidate.enabled,
            )
            preferences.save(imported)
            form = imported
            socksHost = ""
            socksPort = 0
            socksUsername = ""
            pendingUri = ""
            failure = null
            pairedNotice = true
            requestedScreen = ""
        },
        onDismiss = {
            pendingUri = ""
            claiming = false
            failure = null
        },
    )
}
