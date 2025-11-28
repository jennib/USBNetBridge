package com.jenniferbeidas.usbnetserver

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface

class SerialConnectionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {
    private var serialDevice: UsbSerialDevice? = null
    private val tag = "SerialConnectionManager"

    fun open(device: UsbDevice): Boolean {
        close() // Close existing connection first.
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            onStatusUpdate("Failed to open USB device.")
            return false
        }

        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection, -1)

        if (serialDevice == null) {
            onStatusUpdate("Device not supported. Please use a standard serial device.")
            connection.close() 
            return false
        }

        if (serialDevice!!.open()) {
            configureAndListen(device)
            return true
        } else {
            onStatusUpdate("Failed to open serial port. Is the device supported?")
            serialDevice!!.close()
            serialDevice = null
            return false
        }
    }

    private fun configureAndListen(device: UsbDevice) {
        serialDevice?.let {
            val prefs = context.getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
            it.setBaudRate(prefs.getInt("baud_rate", 115200))
            it.setDataBits(prefs.getInt("data_bits", 8))
            it.setStopBits(prefs.getInt("stop_bits", UsbSerialInterface.STOP_BITS_1))
            it.setParity(prefs.getInt("parity", UsbSerialInterface.PARITY_NONE))
            it.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF)
            it.read { data ->
                try {
                    onDataReceived(data)
                } catch (e: Exception) {
                    Log.e(tag, "Error processing received data", e)
                }
            }
            onStatusUpdate("Connected to ${device.deviceName}")
        }
    }

    fun write(data: ByteArray) {
        try {
            serialDevice?.write(data)
        } catch (e: Exception) {
            Log.e(tag, "Error writing to serial port", e)
            onStatusUpdate("Error writing to serial port: ${e.message}")
        }
    }

    fun close() {
        try {
            serialDevice?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing serial port", e)
        }
        serialDevice = null
    }

    fun isSerialDevice(device: UsbDevice): Boolean {
        return UsbSerialDevice.isSupported(device)
    }
}
