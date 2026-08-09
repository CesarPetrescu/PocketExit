package com.photonspark.pocketexit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photonspark.pocketexit.data.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val preferences = AppPreferences(context)
        try {
            val config = preferences.current
            if (config.enabled && config.autoStart && config.validationError() == null) {
                runCatching { ExitNodeService.start(context) }
            }
        } finally {
            preferences.close()
        }
    }
}
