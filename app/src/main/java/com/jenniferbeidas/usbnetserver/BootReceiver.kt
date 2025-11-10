package com.jenniferbeidas.usbnetserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPreferences = context.getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
            val startOnBoot = sharedPreferences.getBoolean("start_on_boot", false)
            if (startOnBoot) {
                val i = Intent(context, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
        }
    }
}