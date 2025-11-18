package com.jenniferbeidas.usbnetserver

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class MainViewModel(private val application: Application) : AndroidViewModel(application) {

    private val tag = "MainViewModel"

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val serialConnectionManager = SerialConnectionManager(
        application,
        usbManager,
        onDataReceived = { data -> 
            appendToSerialLog(data)
            writeToWebsocket(data)
            writeToTcp(data)
        },
        onStatusUpdate = { message -> updateStatusMessage(message) }
    )

    @Volatile
    private var latestJpeg: ByteArray? = null

    private var cameraServerSocket: ServerSocket? = null
    private val cameraServerPort = 8887

    private var unifiedServer: ServerSocket? = null
    private val unifiedPort = 8888
    private val webSocketClients = mutableListOf<Socket>()

    private var tcpProxyServer: ServerSocket? = null
    private val tcpProxyPort = 8889
    private val tcpProxyClients = mutableListOf<Socket>()

    private val gson = Gson()
    
    init {
        startUnifiedServer()
    }

    fun connectToDevice(device: UsbDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            if (serialConnectionManager.open(device)) {
                _uiState.update { it.copy(isConnected = true) }
                startAllServers()
            }
        }
    }

    fun disconnect() {
        _uiState.update { it.copy(isConnected = false) }
        serialConnectionManager.close()
        stopAllServers()
        onUsbDeviceDetached()
    }

    fun connectToFirstAvailableDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.isConnected) return@launch
            val deviceList = usbManager.deviceList
            deviceList.values.firstOrNull { usbManager.hasPermission(it) }?.let {
                connectToDevice(it)
            }
        }
    }

    fun sendMacroCommand(command: String) {
        if (command.startsWith("0x")) {
            val hexData = command.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            appendToSerialLog(hexData)
            serialConnectionManager.write(hexData)
        } else {
            val data = command.replace("\\n", "\n").replace("\\r", "\r").toByteArray()
            appendToSerialLog(data)
            serialConnectionManager.write(data)
        }
    }

    fun loadMacros() {
        viewModelScope.launch {
            val prefs = application.getSharedPreferences("macros", Context.MODE_PRIVATE)
            val oldMacrosString = prefs.getString("macros_ordered", null)

            if (oldMacrosString != null) {
                val legacyMacros = oldMacrosString.split("\n").mapNotNull {
                    val parts = it.split("|", limit = 4)
                    if (parts.size == 4) {
                        val name = parts[0]
                        val isBase64 = parts[1].toBoolean()
                        val command = if (isBase64) String(Base64.decode(parts[2], Base64.DEFAULT)) else parts[2]
                        val colorHex = parts[3].ifEmpty { null }
                        Macro(name, command, colorHex)
                    } else null
                }.toList()
                
                saveMacros(legacyMacros)
                prefs.edit().remove("macros_ordered").apply()
                _uiState.update { it.copy(macros = legacyMacros) }

            } else {
                val macrosJson = prefs.getString("macros", null)
                if (macrosJson == null) {
                    val defaultMacros = listOf(Macro("Soft Reset", "0x18"), Macro("Unlock", "\$X\n"))
                    saveMacros(defaultMacros)
                    _uiState.update { it.copy(macros = defaultMacros) }
                } else {
                    val type = object : TypeToken<List<Macro>>() {}.type
                    val macros = gson.fromJson<List<Macro>>(macrosJson, type)
                    _uiState.update { it.copy(macros = macros) }
                }
            }
        }
    }
    
    fun saveMacros(macros: List<Macro>) {
        val prefs = application.getSharedPreferences("macros", Context.MODE_PRIVATE)
        val macrosJson = gson.toJson(macros)
        prefs.edit().putString("macros", macrosJson).apply()
        _uiState.update { it.copy(macros = macros) }
    }

    fun updateLatestJpeg(jpeg: ByteArray?) {
        latestJpeg = jpeg
    }

    private fun startAllServers() {
        startCameraStreamServer()
        startTcpProxyServer()
    }

    private fun stopAllServers() {
        cameraServerSocket?.close()
        tcpProxyServer?.close()
    }

    fun startCameraStreamServer() {
        if (cameraServerSocket != null && !cameraServerSocket!!.isClosed) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    _uiState.update { it.copy(cameraStatus = "Camera: Could not get IP address.") }
                    return@launch
                }

                cameraServerSocket = ServerSocket(cameraServerPort)
                _uiState.update { it.copy(cameraStatus = "Camera: $ipAddress:$cameraServerPort") }

                while (true) {
                    val clientSocket = cameraServerSocket!!.accept()
                    Log.d(tag, "Client connected to camera: ${clientSocket.inetAddress}")
                    launch {
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
                                kotlinx.coroutines.delay(100) // Frame rate limiter
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

    private fun startUnifiedServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    updateStatusMessage("Server: Could not get IP.")
                    return@launch
                }

                val sslContext = getOrCreateSSLContext()
                val serverSocketFactory = sslContext.serverSocketFactory
                unifiedServer = serverSocketFactory.createServerSocket(unifiedPort)

                _uiState.update {
                    it.copy(
                        networkStatus = "1. Visit: https://$ipAddress:$unifiedPort",
                        httpStatus = "2. JS to: wss://$ipAddress:$unifiedPort"
                    )
                }

                while (true) {
                    val client = unifiedServer!!.accept()
                    Log.d(tag, "Client connected: ${client.inetAddress}")
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e(tag, "Unified server error", e)
                updateStatusMessage("Server: Error starting server")
            }
        }
    }

    private fun startTcpProxyServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    updateStatusMessage("TCP: Could not get IP.")
                    return@launch
                }

                tcpProxyServer = ServerSocket(tcpProxyPort)
                _uiState.update { it.copy(tcpProxyStatus = "TCP Proxy: $ipAddress:$tcpProxyPort") }

                while (true) {
                    val client = tcpProxyServer!!.accept()
                    Log.d(tag, "TCP client connected: ${client.inetAddress}")
                    synchronized(tcpProxyClients) { tcpProxyClients.add(client) }
                    launch {
                        try {
                            val buffer = ByteArray(1024)
                            while (client.isConnected) {
                                val bytesRead = client.getInputStream().read(buffer)
                                if (bytesRead == -1) break
                                val data = buffer.copyOf(bytesRead)
                                appendToSerialLog(data)
                                serialConnectionManager.write(data)
                            }
                        } catch (e: IOException) {
                            // Client disconnected
                        } finally {
                            synchronized(tcpProxyClients) { tcpProxyClients.remove(client) }
                            try {
                                client.close()
                            } catch (e: IOException) { /* ignore */
                            }
                            Log.d(tag, "TCP client disconnected")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "TCP proxy server error", e)
                updateStatusMessage("TCP: Error starting server")
            }
        }
    }

    private fun handleClient(client: Socket) {
        viewModelScope.launch(Dispatchers.IO) {
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
                    if (key == null || !_uiState.value.isConnected) {
                        client.close()
                        return@launch
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
                        appendToSerialLog(payload)
                        serialConnectionManager.write(payload)
                    }

                } else {
                     try {
                        val htmlFile = if (_uiState.value.isConnected) "index.html" else "no_device.html"
                        val htmlContent = application.assets.open(htmlFile).bufferedReader().use { it.readText() }
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
                try {
                    client.close()
                } catch (e: IOException) { /* ignore */
                }
                Log.d(tag, "Client disconnected")
            }
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
    
    private fun writeToWebsocket(data: ByteArray) {
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
    }

    private fun writeToTcp(data: ByteArray) {
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
            val keystoreFile = File(application.filesDir, "keystore.p12")
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

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    fun updateStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun onUsbDeviceDetached() {
        _uiState.update { it.copy(statusMessage = "USB device detached. Please connect a device.", networkStatus = "", httpStatus = "", tcpProxyStatus = "") }
    }

    fun setCameraPermission(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
        if(!granted) {
            updateStatusMessage("Camera permission is required for preview.")
        }
    }

    fun appendToSerialLog(data: ByteArray) {
        val prefs = application.getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        val displayMode = prefs.getString("display_mode", "Raw")
        val newLog = if (displayMode == "Hex") {
            _uiState.value.serialLog + data.toHexString()
        } else {
            _uiState.value.serialLog + String(data)
        }
        _uiState.update { it.copy(serialLog = newLog.takeLast(4000)) }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

    private fun ByteArray.toHexString(): String = joinToString(separator = " ") { "%02X".format(it) }
}
