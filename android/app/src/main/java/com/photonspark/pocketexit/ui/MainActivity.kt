package com.photonspark.pocketexit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.photonspark.pocketexit.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single activity. It owns the preference store and the `pocketexit://`
 * intent, and hands both to [PocketExitApp]; every screen lives in its own file
 * beside this one.
 */
class MainActivity : ComponentActivity() {
    private lateinit var preferences: AppPreferences
    private val onboardingUri = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        onboardingUri.value = intent?.dataString
        setContent {
            val pendingOnboarding by onboardingUri.collectAsState()
            PocketExitTheme {
                NotificationPermission()
                PocketExitApp(
                    preferences = preferences,
                    onboardingUri = pendingOnboarding,
                    onOnboardingConsumed = {
                        onboardingUri.value = null
                        intent?.data = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onboardingUri.value = intent.dataString
    }

    override fun onDestroy() {
        preferences.close()
        super.onDestroy()
    }
}

@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
