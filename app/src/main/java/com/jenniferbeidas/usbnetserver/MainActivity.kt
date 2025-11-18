// version 1.0.0
package com.jenniferbeidas.usbnetserver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val tag = "USBNetServer"
    private val viewModel: MainViewModel by viewModels()

    private val actionUsbPermission = "com.jenniferbeidas.usbnetserver.USB_PERMISSION"

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            viewModel.setCameraPermission(isGranted)
        }
        
        val macroEditorResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.loadMacros()
            }
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            MainContent(uiState, onLaunchMacroEditor = {
                macroEditorResultLauncher.launch(Intent(this, MacroEditorActivity::class.java))
            })

            // Handle the discovered device
            val discoveredDevice = uiState.discoveredDevice
            if (discoveredDevice != null) {
                findAndConnectDevice(discoveredDevice)
                viewModel.clearDiscoveredDevice()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.setCameraPermission(true)
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

        viewModel.updateStatusMessage("Please connect a USB serial device.")
        viewModel.loadMacros()
        viewModel.checkForExistingDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
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
        viewModel.disconnect()
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
                    viewModel.disconnect()
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
                    viewModel.connectToDevice(device)
                } else {
                    viewModel.updateStatusMessage("USB permission denied.")
                }
            }
        }
    }

    private fun findAndConnectDevice(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission), flags)
            usbManager.requestPermission(device, permissionIntent)
        } else {
            viewModel.connectToDevice(device)
        }
    }

    @Composable
    fun MainContent(uiState: UiState, onLaunchMacroEditor: () -> Unit) {
        val context = LocalContext.current
        val backgroundColor = Color.Black.copy(alpha = 0.7f)
        val contentColor = Color.White
        val terminalColor = Color(0xFF00FF00) // Bright green for terminal text

        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.hasCameraPermission) {
                CameraPreview(viewModel)
            }

            // Main UI content column
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = onLaunchMacroEditor,
                        modifier = Modifier.background(backgroundColor, CircleShape)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Macros", tint = contentColor)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                        modifier = Modifier.background(backgroundColor, CircleShape)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = contentColor)
                    }
                }

                // Spacer to push the main content to the bottom
                Spacer(modifier = Modifier.weight(1f))

                // Main content area with a semi-transparent background
                Surface(
                    color = backgroundColor,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // Status Message
                        Text(
                            text = uiState.statusMessage,
                            color = contentColor,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Serial Log
                        Text(
                            "Serial Log",
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp) // Increased height
                                .background(Color.Black, RoundedCornerShape(8.dp))
                                .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            val scrollState = rememberScrollState()
                            // Scroll to the bottom whenever the log changes
                            LaunchedEffect(uiState.serialLog) {
                                scrollState.animateScrollTo(scrollState.maxValue)
                            }
                            Text(
                                text = uiState.serialLog,
                                color = terminalColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                            )
                        }

                        // Network Status Section
                        Column(
                            modifier = Modifier.padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                             if (uiState.networkStatus.isNotBlank()) Text(text = uiState.networkStatus, color = contentColor, style = MaterialTheme.typography.bodySmall)
                             if (uiState.cameraStatus.isNotBlank()) Text(text = uiState.cameraStatus, color = contentColor, style = MaterialTheme.typography.bodySmall)
                             if (uiState.httpStatus.isNotBlank()) Text(text = uiState.httpStatus, color = contentColor, style = MaterialTheme.typography.bodySmall)
                             if (uiState.tcpProxyStatus.isNotBlank()) Text(text = uiState.tcpProxyStatus, color = contentColor, style = MaterialTheme.typography.bodySmall)
                        }

                        // Macro Buttons
                        Text(
                            "Macros",
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            if(uiState.macros.isEmpty()) {
                                Text("No macros defined. Edit macros to add some.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            } else {
                                uiState.macros.forEach { macro ->
                                    val buttonColor = if (macro.colorHex != null && macro.colorHex.isNotEmpty()) {
                                        Color(android.graphics.Color.parseColor(macro.colorHex))
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                    Button(
                                        onClick = { viewModel.sendMacroCommand(macro.command) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                                    ) {
                                        Text(macro.name)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun CameraPreview(viewModel: MainViewModel) {
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
                        val jpeg = imageProxy.toJpeg()
                        if(jpeg != null) {
                            viewModel.updateLatestJpeg(jpeg)
                        }
                        imageProxy.close()
                    }
                    
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                        viewModel.startCameraStreamServer()
                    } catch (e: Exception) {
                        Log.e(tag, "Use case binding failed", e)
                    }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
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
}
