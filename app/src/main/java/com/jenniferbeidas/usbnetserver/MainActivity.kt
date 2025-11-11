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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val tag = "USBNetServer"
    private lateinit var usbManager: UsbManager

    private var serialDevice: UsbSerialDevice? = null
    private var serialServerSocket8888: ServerSocket? = null
    private var serialServerSocket23: ServerSocket? = null
    private var cameraServerSocket: ServerSocket? = null
    private var httpControlSocket: ServerSocket? = null

    @Volatile
    private var latestJpeg: ByteArray? = null

    private val serialServerPort8888 = 8888
    private val serialServerPort23 = 23
    private val cameraServerPort = 8889
    private val httpControlPort = 8080

    private var statusText by mutableStateOf("Initializing...")
    private var networkStatusText by mutableStateOf("")
    private var cameraStatusText by mutableStateOf("")
    private var httpStatusText by mutableStateOf("")
    private var hasCameraPermission by mutableStateOf(false)

    private val actionUsbPermission = "com.jenniferbeidas.usbnetserver.USB_PERMISSION"

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            hasCameraPermission = isGranted
            if (!isGranted) {
                statusText = "Camera permission is required to stream video."
            }
        }

        setContent {
            MainContent(
                statusMessage = statusText,
                networkStatus = networkStatusText,
                cameraStatus = cameraStatusText,
                httpStatus = httpStatusText,
                hasPermission = hasCameraPermission
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        val permissionFilter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, permissionFilter)
        }

        val deviceFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        registerReceiver(usbDeviceReceiver, deviceFilter)

        statusText = "Please connect a USB serial device."

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val device: UsbDevice? = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        device?.let { findAndConnectDevice(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbDeviceReceiver)
        serialDevice?.close()
        serialServerSocket8888?.close()
        serialServerSocket23?.close()
        cameraServerSocket?.close()
        httpControlSocket?.close()
    }

    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            device?.let { findAndConnectDevice(it) }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionUsbPermission) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    setupSerialConnection(device)
                } else {
                    statusText = "USB permission denied."
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
        serialDevice?.close()
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            statusText = "Failed to open USB device."
            return
        }

        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)
        if (serialDevice?.open() == true) {
            val prefs = getSharedPreferences("serial_settings", MODE_PRIVATE)
            serialDevice?.setBaudRate(prefs.getInt("baud_rate", 115200))
            serialDevice?.setDataBits(prefs.getInt("data_bits", 8))
            serialDevice?.setStopBits(prefs.getInt("stop_bits", UsbSerialInterface.STOP_BITS_1))
            serialDevice?.setParity(prefs.getInt("parity", UsbSerialInterface.PARITY_NONE))
            statusText = "Connected to ${device.deviceName}"
            startSerialNetworkServers(serialDevice!!)
            startHttpControlServer(serialDevice!!)
        } else {
            statusText = "Failed to open serial port."
        }
    }

    private fun startCameraStreamServer() {
        if (cameraServerSocket != null && !cameraServerSocket!!.isClosed) return
        thread {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    runOnUiThread { cameraStatusText = "Camera: Could not get IP address." }
                    return@thread
                }

                cameraServerSocket = ServerSocket(cameraServerPort)
                runOnUiThread { cameraStatusText = "Camera: $ipAddress:$cameraServerPort" }

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

    private fun startSerialNetworkServers(serialPort: UsbSerialDevice) {
        val ipAddress = getLocalIpAddress() ?: return
        networkStatusText = "Serial: $ipAddress:"
        startServerOnPort(serialPort, serialServerPort8888) { port -> runOnUiThread { networkStatusText += "$port " } }
        startServerOnPort(serialPort, serialServerPort23) { port -> runOnUiThread { networkStatusText += "$port " } }
    }

    private fun startServerOnPort(serialPort: UsbSerialDevice, port: Int, onStarted: (Int) -> Unit) {
        thread {
            try {
                val serverSocket = ServerSocket(port)
                if (port == serialServerPort8888) serialServerSocket8888 = serverSocket else serialServerSocket23 = serverSocket
                onStarted(port)
                while (!Thread.currentThread().isInterrupted) {
                    val clientSocket = serverSocket.accept()
                    Log.d(tag, "Client connected to serial on port $port: ${clientSocket.inetAddress}")
                    bridgeStreams(clientSocket, serialPort)
                }
            } catch (e: IOException) {
                Log.e(tag, "Serial server error on port $port", e)
            }
        }
    }

    private fun startHttpControlServer(serialPort: UsbSerialDevice) {
        thread {
            try {
                val ipAddress = getLocalIpAddress()
                httpControlSocket = ServerSocket(httpControlPort)
                runOnUiThread {
                    httpStatusText = "HTTP Control: $ipAddress:$httpControlPort"
                }
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

    private fun bridgeStreams(clientSocket: java.net.Socket, serialPort: UsbSerialDevice) {
        serialPort.read { data ->
            try {
                clientSocket.getOutputStream().write(data)
            } catch (e: IOException) {
                Log.e(tag, "Error writing to network from serial", e)
            }
        }
        thread {
            try {
                val buffer = ByteArray(1024)
                val socketIn = clientSocket.getInputStream()
                while (clientSocket.isConnected) {
                    val numBytesRead = socketIn.read(buffer)
                    if (numBytesRead == -1) break
                    serialPort.write(buffer.copyOf(numBytesRead))
                }
            } catch (e: IOException) {
                Log.e(tag, "Error writing to serial from network", e)
            }
        }
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
    fun MainContent(
        statusMessage: String,
        networkStatus: String,
        cameraStatus: String,
        httpStatus: String,
        hasPermission: Boolean
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasPermission) {
                CameraPreview(onCameraReady = { startCameraStreamServer() })
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = statusMessage, color = Color.White)
                if (networkStatus.isNotBlank()) Text(text = networkStatus, color = Color.White)
                if (cameraStatus.isNotBlank()) Text(text = cameraStatus, color = Color.White)
                if (httpStatus.isNotBlank()) Text(text = httpStatus, color = Color.White)
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
}
