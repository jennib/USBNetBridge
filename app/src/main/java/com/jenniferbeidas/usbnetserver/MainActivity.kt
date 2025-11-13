// version 1.0.0
package com.jenniferbeidas.usbnetserver

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class MainActivity : ComponentActivity() {

    private val tag = "USBNetServer"
    private lateinit var usbManager: UsbManager

    private var serialDevice: UsbSerialDevice? = null
    private var unifiedServer: ServerSocket? = null
    private var tcpProxyServer: ServerSocket? = null
    private var cameraServerSocket: ServerSocket? = null
    private val webSocketClients = mutableListOf<Socket>()
    private val tcpProxyClients = mutableListOf<Socket>()

    @Volatile
    private var latestJpeg: ByteArray? = null

    private val unifiedPort = 8887
    private val tcpProxyPort = 9999
    private val cameraServerPort = 8889

    private var uiState by mutableStateOf(UiState())

    private val actionUsbPermission = "com.jenniferbeidas.usbnetserver.USB_PERMISSION"

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            updateState { it.copy(hasCameraPermission = isGranted) }
            if (!isGranted) {
                updateState { it.copy(statusMessage = "Camera permission is required for preview.") }
            }
        }

        setContent {
            MainContent(uiState)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            updateState { it.copy(hasCameraPermission = true) }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        val permissionFilter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, permissionFilter)
        }

        val deviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbDeviceReceiver, deviceFilter)

        updateState { it.copy(statusMessage = "Please connect a USB serial device.") }

        handleIntent(intent)
        checkForExistingDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { findAndConnectDevice(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbDeviceReceiver)
        stopServers()
    }

    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { findAndConnectDevice(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    updateState { it.copy(statusMessage = "USB device detached. Please connect a device.", networkStatus = "", httpStatus = "", tcpProxyStatus = "") }
                    stopServers()
                }
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionUsbPermission) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    setupSerialConnection(device)
                } else {
                    updateState { it.copy(statusMessage = "USB permission denied.") }
                }
            }
        }
    }

    private fun checkForExistingDevice() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            val connection = usbManager.openDevice(device)
            if (connection != null) {
                // Probe to see if this device is supported by the UsbSerial library.
                var tempSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection, -1)
                if (tempSerialDevice == null) {
                    // If probing fails, try the default creation method.
                    tempSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)
                }
                connection.close() // Immediately close the temporary connection.

                if (tempSerialDevice != null) {
                    // This device is a potential serial device.
                    // Proceed with the full connection logic which handles permissions.
                    findAndConnectDevice(device)
                    // Stop searching and connecting to other devices.
                    return
                }
            }
        }
    }

    private fun findAndConnectDevice(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission), flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            setupSerialConnection(device)
        }
    }

    private fun setupSerialConnection(device: UsbDevice) {
        stopServers()
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            updateState { it.copy(statusMessage = "Failed to open USB device.") }
            return
        }

        // First, try to find the serial interface automatically. This may fail for some devices.
        var newSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection, -1)

        // If probing fails, fall back to the default interface which is often correct.
        if (newSerialDevice == null) {
            updateState { it.copy(statusMessage = "Probing for serial interface failed, falling back to default.") }
            newSerialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)
        }
        
        serialDevice = newSerialDevice

        if (serialDevice == null) {
            updateState { it.copy(statusMessage = "Device not supported. Please use a standard serial device.") }
            return
        }

        if (serialDevice?.open() == true) {
            val prefs = getSharedPreferences("serial_settings", MODE_PRIVATE)
            serialDevice?.setBaudRate(prefs.getInt("baud_rate", 115200))
            serialDevice?.setDataBits(prefs.getInt("data_bits", 8))
            serialDevice?.setStopBits(prefs.getInt("stop_bits", UsbSerialInterface.STOP_BITS_1))
            serialDevice?.setParity(prefs.getInt("parity", UsbSerialInterface.PARITY_NONE))
            updateState { it.copy(statusMessage = "Connected to ${device.deviceName}") }

            serialDevice!!.read { data ->
                runOnUiThread {
                    updateState {
                        val newLog = it.serialLog + data.toHexString()
                        it.copy(serialLog = newLog.takeLast(4000))
                    }
                }
                synchronized(webSocketClients) {
                    val clientsToRemove = mutableListOf<Socket>()
                    webSocketClients.forEach { client ->
                        try {
                            writeWsFrame(client.getOutputStream(), data, 2)
                        } catch (e: IOException) {
                            clientsToRemove.add(client)
                        }
                    }
                    webSocketClients.removeAll(clientsToRemove)
                }
                synchronized(tcpProxyClients) {
                    val clientsToRemove = mutableListOf<Socket>()
                    tcpProxyClients.forEach { client ->
                        try {
                            client.getOutputStream().write(data)
                        } catch (e: IOException) {
                            clientsToRemove.add(client)
                        }
                    }
                    tcpProxyClients.removeAll(clientsToRemove)
                }
            }

            startUnifiedServer(serialDevice!!)
            startTcpProxyServer(serialDevice!!)
        } else {
            updateState { it.copy(statusMessage = "Failed to open serial port. Is the device supported?") }
            serialDevice = null
        }
    }

    private fun startCameraStreamServer() {
        if (cameraServerSocket != null && !cameraServerSocket!!.isClosed) return
        thread {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    updateState { it.copy(cameraStatus = "Camera: Could not get IP address.") }
                    return@thread
                }

                cameraServerSocket = ServerSocket(cameraServerPort)
                updateState { it.copy(cameraStatus = "Camera: $ipAddress:$cameraServerPort") }

                while (!Thread.currentThread().isInterrupted) {
                    val clientSocket = cameraServerSocket!!.accept()
                    Log.d(tag, "Client connected to camera: ${clientSocket.inetAddress}")
                    thread {
                        try {
                            val outputStream = clientSocket.getOutputStream()
                            outputStream.write(
                                ("HTTP/1.0 200 OK\r\n" +
                                        "Connection: keep-alive\r\n" +
                                        "Max-Age: 0\r\n" +
                                        "Expires: 0\r\n" +
                                        "Cache-Control: no-cache, private\r\n" +
                                        "Pragma: no-cache\r\n" +
                                        "Content-Type: multipart/x-mixed-replace; boundary=boundary\r\n\r\n").toByteArray()
                            )

                            while (clientSocket.isConnected) {
                                val jpegBytes = latestJpeg
                                if (jpegBytes != null) {
                                    outputStream.write(
                                        ("--boundary\r\n" +
                                                "Content-Type: image/jpeg\r\n" +
                                                "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray()
                                    )
                                    outputStream.write(jpegBytes)
                                    outputStream.write("\r\n".toByteArray())
                                    outputStream.flush()
                                }
                                Thread.sleep(100) // Frame rate limiter
                            }
                        } catch (e: IOException) {
                            Log.d(tag, "Client disconnected from camera stream: ${e.message}")
                        } finally {
                            try {
                                clientSocket.close()
                            } catch (e: IOException) {
                                Log.e(tag, "Error closing client socket", e)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (cameraServerSocket?.isClosed == false) {
                    Log.e(tag, "Camera server error", e)
                }
            }
        }
    }

    private fun startUnifiedServer(serialPort: UsbSerialDevice) {
        thread {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    runOnUiThread { updateState { it.copy(networkStatus = "Server: Could not get IP.") } }
                    return@thread
                }

                val sslContext = getOrCreateSSLContext()
                val serverSocketFactory = sslContext.serverSocketFactory
                unifiedServer = serverSocketFactory.createServerSocket(unifiedPort)

                runOnUiThread {
                    updateState {
                        it.copy(
                            networkStatus = "1. Visit: https://$ipAddress:$unifiedPort",
                            httpStatus = "2. JS to: wss://$ipAddress:$unifiedPort"
                        )
                    }
                }

                while (!Thread.currentThread().isInterrupted) {
                    val client = unifiedServer!!.accept()
                    Log.d(tag, "Client connected: ${client.inetAddress}")
                    thread { handleClient(client, serialPort) }
                }
            } catch (e: Exception) {
                Log.e(tag, "Unified server error", e)
                runOnUiThread { updateState { it.copy(networkStatus = "Server: Error starting server") } }
            }
        }
    }

    private fun startTcpProxyServer(serialPort: UsbSerialDevice) {
        thread {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    runOnUiThread { updateState { it.copy(tcpProxyStatus = "TCP: Could not get IP.") } }
                    return@thread
                }

                tcpProxyServer = ServerSocket(tcpProxyPort)
                runOnUiThread { updateState { it.copy(tcpProxyStatus = "TCP Proxy: $ipAddress:$tcpProxyPort") } }

                while (!Thread.currentThread().isInterrupted) {
                    val client = tcpProxyServer!!.accept()
                    Log.d(tag, "TCP client connected: ${client.inetAddress}")
                    synchronized(tcpProxyClients) { tcpProxyClients.add(client) }
                    thread {
                        try {
                            val buffer = ByteArray(1024)
                            while (client.isConnected) {
                                val bytesRead = client.getInputStream().read(buffer)
                                if (bytesRead == -1) break
                                val data = buffer.copyOf(bytesRead)
                                runOnUiThread {
                                    updateState {
                                        val newLog = it.serialLog + data.toHexString()
                                        it.copy(serialLog = newLog.takeLast(4000))
                                    }
                                }
                                serialPort.write(data)
                            }
                        } catch (e: IOException) {
                            // Client disconnected
                        } finally {
                            synchronized(tcpProxyClients) { tcpProxyClients.remove(client) }
                            try { client.close() } catch (e: IOException) { /* ignore */ }
                            Log.d(tag, "TCP client disconnected")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "TCP proxy server error", e)
                runOnUiThread { updateState { it.copy(tcpProxyStatus = "TCP: Error starting server") } }
            }
        }
    }

    private fun handleClient(client: Socket, serialPort: UsbSerialDevice) {
        try {
            val `in` = client.getInputStream()
            val out = client.getOutputStream()

            val headerBytes = ByteArrayOutputStream()
            val endOfHeaders = "\r\n\r\n".toByteArray()
            var matchIndex = 0
            while (true) {
                val b = `in`.read()
                if (b == -1) break
                headerBytes.write(b)
                if (b == endOfHeaders[matchIndex].toInt()) {
                    matchIndex++
                    if (matchIndex == endOfHeaders.size) break
                } else {
                    matchIndex = 0
                }
            }

            val headers = String(headerBytes.toByteArray()).split("\r\n").associate { 
                val parts = it.split(": ", limit = 2)
                if (parts.size == 2) parts[0].lowercase() to parts[1] else "" to ""
            }

            if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                val key = headers["sec-websocket-key"]
                if (key == null) {
                    client.close()
                    return
                }

                val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Sec-WebSocket-Accept: " +
                        Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), Base64.NO_WRAP) +
                        "\r\n\r\n"
                out.write(response.toByteArray())
                out.flush()

                synchronized(webSocketClients) { webSocketClients.add(client) }

                while (client.isConnected) {
                    val payload = readWsFrame(`in`)
                    if (payload == null) break
                    runOnUiThread {
                        updateState {
                            val newLog = it.serialLog + payload.toHexString()
                            it.copy(serialLog = newLog.takeLast(4000))
                        }
                    }
                    serialPort.write(payload)
                }

            } else {
                 try {
                    val htmlContent = assets.open("index.html").bufferedReader().use { it.readText() }
                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: ${htmlContent.length}\r\n" +
                            "Connection: close\r\n\r\n" + htmlContent
                    out.write(response.toByteArray())
                    out.flush()
                } catch (e: IOException) {
                    val errorResponse = "HTTP/1.1 500 Internal Server Error\r\n\r\nCould not load page."
                    out.write(errorResponse.toByteArray())
                    out.flush()
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Error handling client connection", e)
        } finally {
            synchronized(webSocketClients) { webSocketClients.remove(client) }
            try { client.close() } catch (e: IOException) { /* ignore */ }
            Log.d(tag, "Client disconnected")
        }
    }

    @Throws(IOException::class)
    private fun readWsFrame(stream: InputStream): ByteArray? {
        val b1 = stream.read()
        if (b1 == -1) return null

        val opCode = b1 and 0x0F
        if (opCode == 8) return null
        if (opCode != 1 && opCode != 2) return null

        val b2 = stream.read()
        if (b2 == -1) return null

        val isMasked = (b2 and 0x80) != 0
        if (!isMasked) return null

        var payloadLength = (b2 and 0x7F).toLong()
        if (payloadLength == 126L) {
            payloadLength = ((stream.read() and 0xFF).toLong() shl 8) or (stream.read() and 0xFF).toLong()
        } else if (payloadLength == 127L) {
            payloadLength = ((stream.read() and 0xFF).toLong() shl 56) or
                    ((stream.read() and 0xFF).toLong() shl 48) or
                    ((stream.read() and 0xFF).toLong() shl 40) or
                    ((stream.read() and 0xFF).toLong() shl 32) or
                    ((stream.read() and 0xFF).toLong() shl 24) or
                    ((stream.read() and 0xFF).toLong() shl 16) or
                    ((stream.read() and 0xFF).toLong() shl 8) or
                    (stream.read() and 0xFF).toLong()
        }

        val mask = ByteArray(4)
        var totalRead = 0
        while (totalRead < 4) {
            val read = stream.read(mask, totalRead, 4 - totalRead)
            if (read == -1) return null
            totalRead += read
        }

        val payload = ByteArray(payloadLength.toInt())
        totalRead = 0
        while (totalRead < payloadLength) {
            val read = stream.read(payload, totalRead, payloadLength.toInt() - totalRead)
            if (read == -1) return null
            totalRead += read
        }

        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return payload
    }

    @Throws(IOException::class)
    private fun writeWsFrame(stream: OutputStream, payload: ByteArray, opCode: Int) {
        stream.write(0x80 or opCode)
        if (payload.size <= 125) {
            stream.write(payload.size)
        } else if (payload.size <= 65535) {
            stream.write(126)
            stream.write((payload.size shr 8) and 0xFF)
            stream.write(payload.size and 0xFF)
        } else {
            stream.write(127)
            stream.write((payload.size shr 56) and 0xFF)
            stream.write((payload.size shr 48) and 0xFF)
            stream.write((payload.size shr 40) and 0xFF)
            stream.write((payload.size shr 32) and 0xFF)
            stream.write((payload.size shr 24) and 0xFF)
            stream.write((payload.size shr 16) and 0xFF)
            stream.write((payload.size shr 8) and 0xFF)
            stream.write(payload.size and 0xFF)
        }
        stream.write(payload)
        stream.flush()
    }

    private fun getOrCreateSSLContext(): SSLContext {
        try {
            val keystoreFile = File(filesDir, "keystore.p12")
            Log.d(tag, "Checking for keystore at: ${keystoreFile.absolutePath}")
            val keystorePassword = "password".toCharArray()
            val keyAlias = "usbnetserver"

            val keyStore = KeyStore.getInstance("PKCS12")

            if (keystoreFile.exists()) {
                Log.d(tag, "Keystore found, loading it.")
                FileInputStream(keystoreFile).use {
                    keyStore.load(it, keystorePassword)
                }
            } else {
                Log.d(tag, "Keystore not found, creating a new one.")
                keyStore.load(null, keystorePassword)

                val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
                keyPairGenerator.initialize(2048)
                val keyPair = keyPairGenerator.generateKeyPair()
                Log.d(tag, "Key pair generated.")

                val certificate = generateCertificate(keyPair)
                Log.d(tag, "Certificate generated.")

                keyStore.setKeyEntry(keyAlias, keyPair.private, keystorePassword, arrayOf(certificate))

                FileOutputStream(keystoreFile).use {
                    keyStore.store(it, keystorePassword)
                }
                Log.d(tag, "Keystore saved.")
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, keystorePassword)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            Log.d(tag, "SSLContext created successfully.")
            return sslContext
        } catch (e: Exception) {
            Log.e(tag, "Error in getOrCreateSSLContext", e)
            throw e
        }
    }

    private fun generateCertificate(keyPair: KeyPair): X509Certificate {
        val now = Date()
        val notAfter = Date(now.time + 3650 * 24 * 60 * 60 * 1000L) // 10 years

        val subject = X500Name("CN=com.jenniferbeidas.usbnetserver")

        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.time),
            now,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = builder.build(signer)

        return JcaX509CertificateConverter().getCertificate(certHolder)
    }

    private fun stopServers() {
        serialDevice?.close()
        unifiedServer?.close()
        tcpProxyServer?.close()
        cameraServerSocket?.close()
        synchronized(webSocketClients) { webSocketClients.forEach { try { it.close() } catch (e: IOException) {} } }
        webSocketClients.clear()
        synchronized(tcpProxyClients) { tcpProxyClients.forEach { try { it.close() } catch (e: IOException) {} } }
        tcpProxyClients.clear()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return null
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
            for (linkAddress in linkProperties.linkAddresses) {
                val address = linkAddress.address
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to get IP address", e)
        }
        return null
    }

    private fun ImageProxy.toJpeg(): ByteArray? {
        if (format != ImageFormat.YUV_420_888) return null
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null).compressToJpeg(Rect(0, 0, width, height), 80, out)
        return out.toByteArray()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = " ") { "%02X".format(it) }

    @Composable
    fun MainContent(uiState: UiState) {
        val context = LocalContext.current
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.hasCameraPermission) {
                CameraPreview()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = uiState.statusMessage, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Serial Log", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Color.White)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(Color.DarkGray)
                        .padding(4.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text = uiState.serialLog, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (uiState.networkStatus.isNotBlank()) Text(text = uiState.networkStatus, color = Color.White)
                if (uiState.cameraStatus.isNotBlank()) Text(text = uiState.cameraStatus, color = Color.White)
                if (uiState.httpStatus.isNotBlank()) Text(text = uiState.httpStatus, color = Color.White)
                if (uiState.tcpProxyStatus.isNotBlank()) Text(text = uiState.tcpProxyStatus, color = Color.White)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { sendSoftReset() }) { Text("Soft Reset") }
                    Button(onClick = { sendUnlock() }) { Text("Unlock") }
                }
            }
            IconButton(
                onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }
    }

    @Composable
    fun CameraPreview() {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        AndroidView(
            factory = { ctx ->
                val previewView = androidx.camera.view.PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        latestJpeg = imageProxy.toJpeg()
                        imageProxy.close()
                    }
                    
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                        startCameraStreamServer()
                    } catch (e: Exception) {
                        Log.e(tag, "Use case binding failed", e)
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    @Synchronized
    private fun updateState(reducer: (UiState) -> UiState) {
        uiState = reducer(uiState)
    }

    private fun sendUnlock() {
        val data = "\$X\n".toByteArray()
        serialDevice?.write(data)
    }

    private fun sendSoftReset() {
        val data = byteArrayOf(0x18)
        serialDevice?.write(data)
    }
}

data class UiState(
    val statusMessage: String = "Initializing...",
    val networkStatus: String = "",
    val cameraStatus: String = "",
    val httpStatus: String = "",
    val tcpProxyStatus: String = "",
    val serialLog: String = "",
    val hasCameraPermission: Boolean = false,
    val isCameraReady: Boolean = false
)
