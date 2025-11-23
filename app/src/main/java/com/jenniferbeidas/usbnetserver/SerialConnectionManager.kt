package com.jenniferbeidas.usbnetserver

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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

        // The UsbSerialDevice takes ownership of the connection.
        // We try probing first, as it's the most reliable for composite devices.
        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)

        // If both failed, the device is not supported.
        if (serialDevice == null) {
            onStatusUpdate("Device not supported. Please use a standard serial device.")
            connection.close() // We must close the connection manually here.
            return false
        }

        // Now, try to open the created device.
        if (serialDevice!!.open()) {
            configureAndListen(device)
            return true
        } else {
            onStatusUpdate("Failed to open serial port. Is the device supported?")
            // The UsbSerialDevice's close() method also closes the underlying connection.
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
                onDataReceived(data)
            }
            onStatusUpdate("Connected to ${device.deviceName}")
        }
    }

    fun write(data: ByteArray) {
        if (serialDevice != null) {
            serialDevice?.write(data)
        } else {
            onStatusUpdate("Cannot send data: not connected.")
        }
    }

    fun close() {
        serialDevice?.close()
        serialDevice = null
    }

    fun isSerialDevice(device: UsbDevice): Boolean {
        return UsbSerialDevice.isSupported(device)
    }
}
