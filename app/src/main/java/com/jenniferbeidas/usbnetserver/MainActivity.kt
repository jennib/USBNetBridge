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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private val tag = "MainActivity"
    private val actionUsbPermission = "com.jenniferbeidas.usbnetserver.USB_PERMISSION"

    private val statusText = mutableStateOf("Requesting camera permission...")
    private val networkStatusText = mutableStateOf("")
    private val cameraStatusText = mutableStateOf("")

    private var serialServerSocket: ServerSocket? = null
    private val serialServerPort = 8888

    private var cameraServerSocket: ServerSocket? = null
    private val cameraServerPort = 8889

    private val hasCameraPermission = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            statusText.value = "Camera permission granted."
            hasCameraPermission.value = true
            findAndConnectDevice()
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
                hasPermission = hasCameraPermission.value
            )
        }

        updateCameraPermission()
        handleIntent(intent)
    }

    private fun updateCameraPermission() {
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {
                hasCameraPermission.value = true
                findAndConnectDevice()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        serialServerSocket?.close()
        cameraServerSocket?.close()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            findAndConnectDevice()
        }
    }

    private fun findAndConnectDevice() {
        if (!hasCameraPermission.value) {
            statusText.value = "Waiting for camera permission..."
            return
        }

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            statusText.value = "No USB serial devices found. Please connect your usb serial device."
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            statusText.value = "Requesting USB permission..."
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, permissionIntent)
            return
        }

        setupSerialConnection(device)
    }

    private fun setupSerialConnection(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            statusText.value = "No driver found for the device."
            return
        }

        val port = driver.ports[0]
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            statusText.value = "Could not open device. Is permission granted?"
            return
        }

        try {
            port.open(connection)
            val sharedPreferences = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
            val baudRate = sharedPreferences.getInt("baud_rate", 115200)
            val dataBits = sharedPreferences.getInt("data_bits", 8)
            val stopBits = sharedPreferences.getInt("stop_bits", UsbSerialPort.STOPBITS_1)
            val parity = sharedPreferences.getInt("parity", UsbSerialPort.PARITY_NONE)

            port.setParameters(baudRate, dataBits, stopBits, parity)
            statusText.value = "Serial port connected."
            startSerialNetworkServer(port)
        } catch (e: IOException) {
            statusText.value = "Error setting up serial port."
            Log.e(tag, "Error setting up serial port", e)
        }
    }

    private fun startSerialNetworkServer(serialPort: UsbSerialPort) {
        val ipAddress = getLocalIpAddress()
        if (ipAddress == null) {
            networkStatusText.value = "Could not get IP address. Make sure you are connected to a Wi-Fi network."
            return
        }

        thread {
            try {
                serialServerSocket = ServerSocket(serialServerPort)
                runOnUiThread {
                    networkStatusText.value = "Serial: $ipAddress:$serialServerPort"
                }

                while (!Thread.currentThread().isInterrupted) {
                    val clientSocket = serialServerSocket!!.accept()
                    Log.d(tag, "Client connected to serial: ${clientSocket.inetAddress}")
                    bridgeStreams(clientSocket, serialPort)
                }
            } catch (e: IOException) {
                if (serialServerSocket?.isClosed == false) {
                    Log.e(tag, "Serial server error", e)
                }
            }
        }
    }

    private fun startCameraStreamServer(imageAnalysis: ImageAnalysis) {
        val ipAddress = getLocalIpAddress()
        if (ipAddress == null) {
            cameraStatusText.value = "Could not get IP address."
            return
        }

        thread {
            try {
                cameraServerSocket = ServerSocket(cameraServerPort)
                runOnUiThread {
                    cameraStatusText.value = "Camera: $ipAddress:$cameraServerPort"
                }

                while (!Thread.currentThread().isInterrupted) {
                    val clientSocket = cameraServerSocket!!.accept()
                    Log.d(tag, "Client connected to camera: ${clientSocket.inetAddress}")

                    val outputStream = clientSocket.getOutputStream()
                    outputStream.write(
                        ("HTTP/1.0 200 OK\r\n" +
                                "Connection: close\r\n" +
                                "Max-Age: 0\r\n" +
                                "Expires: 0\r\n" +
                                "Cache-Control: no-cache, private\r\n" +
                                "Pragma: no-cache\r\n" +
                                "Content-Type: multipart/x-mixed-replace; boundary=--boundary\r\n\r\n").toByteArray()
                    )

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val jpegBytes = imageProxy.toJpeg()
                        if (jpegBytes != null) {
                            try {
                                outputStream.write(
                                    ("--boundary\r\n" +
                                            "Content-Type: image/jpeg\r\n" +
                                            "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray()
                                )
                                outputStream.write(jpegBytes)
                                outputStream.write("\r\n".toByteArray())
                                outputStream.flush()
                            } catch (e: IOException) {
                                Log.d(tag, "Client disconnected from camera stream")
                                imageAnalysis.clearAnalyzer()
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

        //U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 80, out)
        return out.toByteArray()
    }

    private fun bridgeStreams(clientSocket: java.net.Socket, serialPort: UsbSerialPort) {
        thread {
            try {
                val buffer = ByteArray(4096)
                val socketIn = clientSocket.getInputStream()
                while (clientSocket.isConnected) {
                    val numBytesRead = socketIn.read(buffer)
                    if (numBytesRead == -1) break
                    serialPort.write(buffer.copyOf(numBytesRead), 200)
                }
            } catch (e: IOException) {
                Log.e(tag, "Error writing to serial from network", e)
            }
        }

        thread {
            try {
                val buffer = ByteArray(4096)
                val socketOut = clientSocket.getOutputStream()
                while (clientSocket.isConnected) {
                    val numBytesRead = serialPort.read(buffer, 200)
                    if (numBytesRead > 0) {
                        socketOut.write(buffer, 0, numBytesRead)
                    }
                }
            } catch (e: IOException) {
                Log.e(tag, "Error reading from serial to network", e)
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
        hasPermission: Boolean
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

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                        startCameraStreamServer(imageAnalysis)
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