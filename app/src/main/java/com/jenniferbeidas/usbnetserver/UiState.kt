package com.jenniferbeidas.usbnetserver

import android.hardware.usb.UsbDevice

data class UiState(
    val statusMessage: String = "Initializing...",
    val networkStatus: String = "",
    val cameraStatus: String = "",
    val httpStatus: String = "",
    val tcpProxyStatus: String = "",
    val serialLog: String = "",
    val hasCameraPermission: Boolean = false,
    val isCameraReady: Boolean = false,
    val macros: List<Macro> = emptyList(),
    val discoveredDevice: UsbDevice? = null,
    val isConnected: Boolean = false
)
