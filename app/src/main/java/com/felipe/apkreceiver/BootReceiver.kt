package com.felipe.apkreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        if (!Prefs.autostart(context)) return

        try {
            HttpServerService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not start service at boot", e)
        }
    }
}
