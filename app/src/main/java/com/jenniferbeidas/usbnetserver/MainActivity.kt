package com.jenniferbeidas.usbnetserver

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.graphics.toColorInt
import com.google.accompanist.flowlayout.FlowRow
import com.google.accompanist.flowlayout.MainAxisAlignment
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val tag = "USBNetServer"
    private val viewModel: MainViewModel by viewModels()

    private var rotation by mutableIntStateOf(0)

    private val actionUsbPermission = "com.jenniferbeidas.usbnetserver.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            viewModel.setCameraPermission(permissions[Manifest.permission.CAMERA] ?: false)
            viewModel.setAudioPermission(permissions[Manifest.permission.RECORD_AUDIO] ?: false)
        }

        val macroEditorResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.loadMacros()
            }
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            MainContent(uiState, onLaunchMacroEditor = {
                macroEditorResultLauncher.launch(Intent(this, MacroEditorActivity::class.java))
            })
        }

        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.setCameraPermission(true)
            viewModel.setAudioPermission(true)
        }

        val permissionFilter = IntentFilter(actionUsbPermission)
        registerReceiver(usbPermissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)

        val deviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbDeviceReceiver, deviceFilter, RECEIVER_NOT_EXPORTED)

        viewModel.updateStatusMessage("Please connect a USB serial device.")
        viewModel.loadMacros()
    }

    override fun onResume() {
        super.onResume()
        val sharedPreferences = getSharedPreferences("serial_settings", MODE_PRIVATE)
        rotation = sharedPreferences.getInt("rotation", 0)
        viewModel.connectToFirstAvailableDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
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
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
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
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    viewModel.connectToDevice(device)
                } else {
                    viewModel.updateStatusMessage("USB permission denied.")
                }
            }
        }
    }

    private fun findAndConnectDevice(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
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
                CameraPreview(viewModel, rotation)
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
                             if (uiState.webrtcStatus.isNotBlank()) Text(text = uiState.webrtcStatus, color = contentColor, style = MaterialTheme.typography.bodySmall)
                        }

                        // Macro Buttons
                        Text(
                            "Macros",
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            mainAxisSpacing = 8.dp,
                            crossAxisSpacing = 8.dp,
                            mainAxisAlignment = MainAxisAlignment.Center
                        ) {
                            if(uiState.macros.isEmpty()) {
                                Text("No macros defined. Edit macros to add some.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            } else {
                                uiState.macros.forEach { macro ->
                                    val buttonColor = if (macro.colorHex != null && macro.colorHex.isNotEmpty()) {
                                        Color(macro.colorHex.toColorInt())
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
    fun CameraPreview(viewModel: MainViewModel, rotation: Int) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current
        val previewView = remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }

        LaunchedEffect(rotation) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    viewModel.processFrame(imageProxy)
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                    viewModel.startWebRtc()
                } catch (e: Exception) {
                    Log.e(tag, "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
    }
}
