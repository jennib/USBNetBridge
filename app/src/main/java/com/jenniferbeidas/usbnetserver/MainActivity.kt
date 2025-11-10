package com.jenniferbeidas.usbnetserver

import android.Manifest
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
import android.view.WindowManager
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
import androidx.compose.runtime.mutableStateOf
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
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private val tag = "MainActivity"
    private val actionUsbPermission = "com.jenniferbeidas.usbnetserver.USB_PERMISSION"

    private val statusText = mutableStateOf("Requesting camera permission...")
    private val networkStatusText = mutableStateOf("")
    private val cameraStatusText = mutableStateOf("")

    private var serialDevice: UsbSerialDevice? = null
    private var serialServerSocket8888: ServerSocket? = null
    private var serialServerSocket23: ServerSocket? = null
    private val serialServerPort8888 = 8888
    private val serialServerPort23 = 23

    private var cameraServerSocket: ServerSocket? = null
    private val cameraServerPort = 8889

    private var imageAnalysis: ImageAnalysis? = null

    private val hasCameraPermission = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            statusText.value = "Camera permission granted."
            hasCameraPermission.value = true
        } else {
            statusText.value = "Camera permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val filter = IntentFilter(actionUsbPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }

        setContent {
            MainContent(
                statusMessage = statusText.value,
                networkStatus = networkStatusText.value,
                cameraStatus = cameraStatusText.value,
                hasPermission = hasCameraPermission.value,
                onSoftReset = { sendSoftReset() },
                onUnlock = { sendUnlock() }
            )
        }

        updateCameraPermission()
    }

    private fun sendSoftReset() {
        serialDevice?.write(byteArrayOf(0x18))
    }

    private fun sendUnlock() {
        serialDevice?.write("\$X\\n".toByteArray())
    }

    private fun updateCameraPermission() {
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {
                statusText.value = "Waiting for USB device..."
                hasCameraPermission.value = true
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleIntent(intent)
        updateKeepScreenOn()
    }

    private fun updateKeepScreenOn() {
        val sharedPreferences = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        val keepScreenOn = sharedPreferences.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        serialServerSocket8888?.close()
        serialServerSocket23?.close()
        cameraServerSocket?.close()
        serialDevice?.close()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let {
                findAndConnectDevice(it)
            }
        }
    }

    private fun findAndConnectDevice(device: UsbDevice) {
        if (!hasCameraPermission.value) {
            statusText.value = "Waiting for camera permission..."
            return
        }

        if (!usbManager.hasPermission(device)) {
            statusText.value = "Requesting USB permission for ${device.deviceName}"
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, permissionIntent)
            return
        }

        setupSerialConnection(device)
    }

    private fun setupSerialConnection(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            statusText.value = "Could not open device. Is permission granted?"
            return
        }

        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection)
        if (serialDevice != null && serialDevice!!.open()) {
            val sharedPreferences = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
            val baudRate = sharedPreferences.getInt("baud_rate", 115200)
            val dataBits = sharedPreferences.getInt("data_bits", 8)
            val stopBits = sharedPreferences.getInt("stop_bits", UsbSerialInterface.STOP_BITS_1)
            val parity = sharedPreferences.getInt("parity", UsbSerialInterface.PARITY_NONE)

            serialDevice?.setBaudRate(baudRate)
            serialDevice?.setDataBits(dataBits)
            serialDevice?.setStopBits(stopBits)
            serialDevice?.setParity(parity)

            statusText.value = "Connected to ${device.deviceName}"
            startSerialNetworkServers(serialDevice!!)
            startCameraStreamServer()
        } else {
            statusText.value = "Error setting up serial port."
        }
    }

    private fun startSerialNetworkServers(serialPort: UsbSerialDevice) {
        val ipAddress = getLocalIpAddress() ?: return
        networkStatusText.value = "Serial: $ipAddress:"
        startServerOnPort(serialPort, serialServerPort8888) { port ->
            networkStatusText.value += "$port "
        }
        startServerOnPort(serialPort, serialServerPort23) { port ->
            networkStatusText.value += "$port "
        }
    }

    private fun startServerOnPort(serialPort: UsbSerialDevice, port: Int, onStarted: (Int) -> Unit) {
        thread {
            try {
                val serverSocket = ServerSocket(port)
                if (port == serialServerPort8888) serialServerSocket8888 = serverSocket else serialServerSocket23 = serverSocket

                runOnUiThread {
                    onStarted(port)
                }

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

    private fun startCameraStreamServer() {
        val analysis = imageAnalysis ?: return
        thread {
            try {
                Thread.sleep(2000) // Wait for network to be ready on boot
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    runOnUiThread {
                        cameraStatusText.value = "Could not get IP address."
                    }
                    return@thread
                }

                cameraServerSocket = ServerSocket(cameraServerPort)
                runOnUiThread {
                    cameraStatusText.value = "Camera: $ipAddress:$cameraServerPort"
                }

                while (!Thread.currentThread().isInterrupted) {
                    val clientSocket = cameraServerSocket!!.accept()
                    Log.d(tag, "Client connected to camera: ${clientSocket.inetAddress}")

                    val outputStream = clientSocket.getOutputStream()
                    outputStream.write(
                        ("HTTP/1.0 200 OK\\r\\n" +
                                "Connection: close\\r\\n" +
                                "Max-Age: 0\\r\\n" +
                                "Expires: 0\\r\\n" +
                                "Cache-Control: no-cache, private\\r\\n" +
                                "Pragma: no-cache\\r\\n" +
                                "Content-Type: multipart/x-mixed-replace; boundary=--boundary\\r\\n\\r\\n").toByteArray()
                    )

                    analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val jpegBytes = imageProxy.toJpeg()
                        if (jpegBytes != null) {
                            try {
                                outputStream.write(
                                    ("--boundary\\r\\n" +
                                            "Content-Type: image/jpeg\\r\\n" +
                                            "Content-Length: ${jpegBytes.size}\\r\\n\\r\\n").toByteArray()
                                )
                                outputStream.write(jpegBytes)
                                outputStream.write("\\r\\n".toByteArray())
                                outputStream.flush()
                            } catch (e: IOException) {
                                Log.d(tag, "Client disconnected from camera stream")
                                analysis.clearAnalyzer()
                                clientSocket.close()
                            }
                        }
                        imageProxy.close()
                    }
                }
            } catch (e: IOException) {
                if (cameraServerSocket?.isClosed == false) {
                    Log.e(tag, "Camera server error", e)
                }
            }
        }
    }

    private fun ImageProxy.toJpeg(): ByteArray? {
        if (format != ImageFormat.YUV_420_888) {
            Log.e(tag, "Unsupported image format: $format")
            return null
        }

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

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 80, out)
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
                val buffer = ByteArray(4096)
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
            val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork) ?: return null
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

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            statusText.value = "USB permission granted. Setting up..."
                            setupSerialConnection(it)
                        }
                    } else {
                        statusText.value = "USB permission denied."
                    }
                }
            }
        }
    }

    @Composable
    fun MainContent(
        statusMessage: String,
        networkStatus: String,
        cameraStatus: String,
        hasPermission: Boolean,
        onSoftReset: () -> Unit,
        onUnlock: () -> Unit
    ) {
        val context = LocalContext.current
        Box(modifier = Modifier.fillMaxSize()) {
            if (hasPermission) {
                CameraPreview()
            }

            IconButton(
                onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = statusMessage, color = Color.White)
                if (networkStatus.isNotBlank()) {
                    Text(text = networkStatus, color = Color.White)
                }
                if (cameraStatus.isNotBlank()) {
                    Text(text = cameraStatus, color = Color.White)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = onSoftReset) { Text("Soft Reset") }
                    Button(onClick = onUnlock) { Text("Unlock") }
                }
            }
        }
    }

    @Composable
    fun CameraPreview() {
        val lifecycleOwner = LocalLifecycleOwner.current

        AndroidView(
            factory = {
                val previewView = PreviewView(it)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(it)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { preview ->
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    this@MainActivity.imageAnalysis = imageAnalysis // Store the instance

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                        startCameraStreamServer() // Initial start
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "Use case binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(it))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}