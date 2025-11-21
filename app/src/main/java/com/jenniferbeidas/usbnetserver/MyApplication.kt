package com.jenniferbeidas.usbnetserver

import android.app.Application
import org.webrtc.PeerConnectionFactory

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
    }
}
