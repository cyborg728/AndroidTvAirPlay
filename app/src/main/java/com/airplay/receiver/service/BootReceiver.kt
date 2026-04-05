package com.airplay.receiver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the AirPlay service automatically when the device boots,
 * if the user has enabled the auto-start option.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("airplay_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)

            if (autoStart) {
                Log.d(TAG, "Boot completed, starting AirPlay service")
                AirPlayService.start(context)
            }
        }
    }
}
