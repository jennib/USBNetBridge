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
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.felhr.usbserial.UsbSerialDevice
import com.felhr.usbserial.UsbSerialInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Scanner
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val tag = "USBNetServer"
    private lateinit var usbManager: UsbManager

    private var serialDevice: UsbSerialDevice? = null
    private var cameraServerSocket: ServerSocket? = null
    private var httpControlSocket: ServerSocket? = null
    private var webSocketServer: ServerSocket? = null
    private val webSocketClients = mutableListOf<Socket>()

    @Volatile
    private var latestJpeg: ByteArray? = null

    private val cameraServerPort = 8889
    private val httpControlPort = 8080
    private val webSocketPort = 8887

    private var uiState by mutableStateOf(UiState())

    private val actionUsbPermission = "com.jenniferbeidas.usbnetserver.USB_PERMISSION"

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            updateState { it.copy(hasCameraPermission = isGranted) }
            if (!isGranted) {
                updateState { it.copy(statusMessage = "Camera permission is required to stream video.") }
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
                    updateState { it.copy(statusMessage = "USB device detached. Please connect a device.", networkStatus = "", httpStatus = "") }
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

        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)
        if (serialDevice?.open() == true) {
            val prefs = getSharedPreferences("serial_settings", MODE_PRIVATE)
            serialDevice?.setBaudRate(prefs.getInt("baud_rate", 115200))
            serialDevice?.setDataBits(prefs.getInt("data_bits", 8))
            serialDevice?.setStopBits(prefs.getInt("stop_bits", UsbSerialInterface.STOP_BITS_1))
            serialDevice?.setParity(prefs.getInt("parity", UsbSerialInterface.PARITY_NONE))
            updateState { it.copy(statusMessage = "Connected to ${device.deviceName}") }
            startWebSocketSerialServer(serialDevice!!)
            startHttpControlServer(serialDevice!!)
        } else {
            updateState { it.copy(statusMessage = "Failed to open serial port.") }
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
                                        "Content-Type: multipart/x-mixed-replace; boundary=--boundary\r\n\r\n").toByteArray()
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

    private fun startWebSocketSerialServer(serialPort: UsbSerialDevice) {
        thread {
            try {
                val ipAddress = getLocalIpAddress()
                webSocketServer = ServerSocket(webSocketPort)
                updateState { it.copy(networkStatus = "WebSocket: $ipAddress:$webSocketPort") }

                serialPort.read { data ->
                    synchronized(webSocketClients) {
                        webSocketClients.forEach { client ->
                            try {
                                val out = client.getOutputStream()
                                out.write(0x81) // TEXT frame
                                out.write(data.size)
                                out.write(data)
                                out.flush()
                            } catch (e: IOException) {
                                Log.e(tag, "Error sending to websocket client", e)
                            }
                        }
                    }
                }

                while (!Thread.currentThread().isInterrupted) {
                    val client = webSocketServer!!.accept()
                    Log.d(tag, "WebSocket client connected: ${client.inetAddress}")

                    thread {
                        try {
                            val `in` = client.getInputStream()
                            val out = client.getOutputStream()
                            val s = Scanner(`in`, "UTF-8")
                            val data = s.useDelimiter("\\r\\n\\r\\n").next()
                            val match = Regex("Sec-WebSocket-Key: (.*)").find(data)
                            val key = match!!.groups[1]!!.value.trim()
                            val response = ("HTTP/1.1 101 Switching Protocols\r\n" +
                                    "Connection: Upgrade\r\n" +
                                    "Upgrade: websocket\r\n" +
                                    "Sec-WebSocket-Accept: " +
                                    Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()), Base64.NO_WRAP) +
                                    "\r\n\r\n").toByteArray()
                            out.write(response, 0, response.size)

                            synchronized(webSocketClients) {
                                webSocketClients.add(client)
                            }

                            while (client.isConnected) {
                                val opcode = `in`.read() and 0x0F
                                val len = `in`.read() and 0x7F
                                val keyBytes = ByteArray(4)
                                `in`.read(keyBytes, 0, 4)
                                val payload = ByteArray(len)
                                `in`.read(payload, 0, len)
                                for (i in 0 until len) {
                                    payload[i] = (payload[i].toInt() xor keyBytes[i % 4].toInt()).toByte()
                                }
                                serialPort.write(payload)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Error with websocket client", e)
                        } finally {
                            synchronized(webSocketClients) {
                                webSocketClients.remove(client)
                            }
                            client.close()
                            Log.d(tag, "WebSocket client disconnected")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(tag, "WebSocket server error", e)
            }
        }
    }

    private fun startHttpControlServer(serialPort: UsbSerialDevice) {
        thread {
            try {
                val ipAddress = getLocalIpAddress()
                httpControlSocket = ServerSocket(httpControlPort)
                updateState { it.copy(httpStatus = "HTTP Control: $ipAddress:$httpControlPort") }

                while (!Thread.currentThread().isInterrupted) {
                    val client = httpControlSocket!!.accept()
                    thread {
                        try {
                            val reader = client.getInputStream().bufferedReader()
                            val requestLine = reader.readLine()
                            Log.d(tag, "HTTP Request: $requestLine")

                            val response: String
                            if (requestLine != null && requestLine.startsWith("GET")) {
                                val command = requestLine.substringAfter("?cmd=", "").substringBefore(" ")
                                if (command.isNotBlank()) {
                                    val decodedCommand = URLDecoder.decode(command, "UTF-8")
                                    serialPort.write(decodedCommand.toByteArray())
                                    serialPort.write("\n".toByteArray())
                                    response = "HTTP/1.1 200 OK\r\n\r\nCommand Sent: $decodedCommand"
                                } else {
                                    response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                                            "<html><body><h1>Serial Control</h1>" +
                                            "<form action=/ method=get><input type=text name=cmd><input type=submit value=Send></form>" +
                                            "</body></html>"
                                }
                            } else {
                                response = "HTTP/1.1 400 Bad Request\r\n\r\nOnly GET requests are supported."
                            }
                            client.getOutputStream().write(response.toByteArray())
                        } catch (e: Exception) {
                            Log.e(tag, "Error handling HTTP control client", e)
                        } finally {
                            client.close()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(tag, "HTTP control server error", e)
            }
        }
    }

    private fun stopServers() {
        serialDevice?.close()
        webSocketServer?.close()
        httpControlSocket?.close()
        synchronized(webSocketClients) {
            webSocketClients.forEach { it.close() }
            webSocketClients.clear()
        }
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

    @Composable
    fun MainContent(uiState: UiState) {
        val context = LocalContext.current
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.hasCameraPermission) {
                CameraPreview(onCameraReady = { startCameraStreamServer() })
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = uiState.statusMessage, color = Color.White)
                if (uiState.networkStatus.isNotBlank()) Text(text = uiState.networkStatus, color = Color.White)
                if (uiState.cameraStatus.isNotBlank()) Text(text = uiState.cameraStatus, color = Color.White)
                if (uiState.httpStatus.isNotBlank()) Text(text = uiState.httpStatus, color = Color.White)
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
    fun CameraPreview(onCameraReady: () -> Unit) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        latestJpeg = imageProxy.toJpeg()
                        imageProxy.close()
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                        onCameraReady()
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
        serialDevice?.write("\$X\n".toByteArray())
    }

    private fun sendSoftReset() {
        serialDevice?.write(byteArrayOf(0x18))
    }
}

data class UiState(
    val statusMessage: String = "Initializing...",
    val networkStatus: String = "",
    val cameraStatus: String = "",
    val httpStatus: String = "",
    val hasCameraPermission: Boolean = false,
    val isCameraReady: Boolean = false
)
