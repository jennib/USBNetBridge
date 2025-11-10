package com.jenniferbeidas.usbnetserver

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * A singleton object responsible for enabling or disabling the [BootReceiver]
 * based on user preference. This allows the app to control whether it starts on boot.
 */
object BootReceiverToggler {

    /**
     * Updates the enabled state of the [BootReceiver].
     *
     * @param context The application context, used to access the PackageManager.
     * @param isEnabled True to enable the receiver, false to disable it.
     */
    fun updateComponentState(context: Context, isEnabled: Boolean) {
        // A component name is a reference to a specific class in your app's manifest.
        val receiver = ComponentName(context, BootReceiver::class.java)
        val packageManager = context.packageManager

        // Determine the new state based on the 'isEnabled' flag.
        val newState = if (isEnabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        // Apply the new state to the component.
        // DONT_KILL_APP prevents the app from being killed after the state change.
        packageManager.setComponentEnabledSetting(
            receiver,
            newState,
            PackageManager.DONT_KILL_APP
        )
    }
}