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

    fun open(device: UsbDevice): Boolean {
        close() // Close existing connection first.
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            onStatusUpdate("Failed to open USB device.")
            return false
        }

        var newSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection, -1)
        if (newSerialDevice == null) {
            onStatusUpdate("Probing for serial interface failed, falling back to default.")
            newSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)
        }

        serialDevice = newSerialDevice

        if (serialDevice == null) {
            onStatusUpdate("Device not supported. Please use a standard serial device.")
            return false
        }

        if (serialDevice?.open() == true) {
            val prefs = context.getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
            serialDevice?.setBaudRate(prefs.getInt("baud_rate", 115200))
            serialDevice?.setDataBits(prefs.getInt("data_bits", 8))
            serialDevice?.setStopBits(prefs.getInt("stop_bits", UsbSerialInterface.STOP_BITS_1))
            serialDevice?.setParity(prefs.getInt("parity", UsbSerialInterface.PARITY_NONE))
            onStatusUpdate("Connected to ${device.deviceName}")

            serialDevice!!.read { data ->
                onDataReceived(data)
            }
            return true
        } else {
            onStatusUpdate("Failed to open serial port. Is the device supported?")
            serialDevice = null
            return false
        }
    }

    fun write(data: ByteArray) {
        if (serialDevice != null) {
            serialDevice?.write(data)
            Log.d("SerialConnectionManager", "Data written: ${data.size} bytes")
        } else {
            Log.w("SerialConnectionManager", "Serial device is null, not writing data.")
            onStatusUpdate("Cannot send data: not connected.")
        }
    }

    fun close() {
        serialDevice?.close()
        serialDevice = null
    }

    // Helper to check if a device is potentially a serial device without fully connecting
    fun isSerialDevice(device: UsbDevice): Boolean {
        val connection = usbManager.openDevice(device)
        if (connection != null) {
            var tempSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection, -1)
            if (tempSerialDevice == null) {
                tempSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)
            }
            connection.close()
            return tempSerialDevice != null
        }
        return false
    }
}