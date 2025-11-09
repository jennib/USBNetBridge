package com.jenniferbeidas.usbnetserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // We only want to act on the boot completed event
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val shouldStartOnBoot = prefs.getBoolean("start_on_boot", true)

            if (shouldStartOnBoot) {
                // Create an Intent to launch the main activity of the app
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    // This flag is required to start an Activity from a non-Activity context
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(activityIntent)
            }
        }
    }
}
