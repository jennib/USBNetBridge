package com.jenniferbeidas.usbnetserver

import android.app.Application
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
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
import org.json.JSONObject
import org.webrtc.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit


class MainViewModel(private val application: Application) : AndroidViewModel(application) {

    private val tag = "MainViewModel"

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val serialConnectionManager = SerialConnectionManager(
        application,
        usbManager,
        onDataReceived = { data ->
            val inboundData = "IN: ".toByteArray() + data
            appendToSerialLog(inboundData)
            writeToWebsocket(inboundData)
            writeToTcp(inboundData)
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

    private val iceCandidateBuffer = Collections.synchronizedList(mutableListOf<IceCandidate>())

    private val gson = Gson()
    private val eglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        val videoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()
    }

    private val videoSource: VideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val videoTrack: VideoTrack by lazy { peerConnectionFactory.createVideoTrack(UUID.randomUUID().toString(), videoSource) }
    private val audioSource: AudioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints()) }
    private val audioTrack: AudioTrack by lazy { peerConnectionFactory.createAudioTrack(UUID.randomUUID().toString(), audioSource) }

    init {
        startUnifiedServer()
    }

    fun startWebRtc() {
        Log.d(tag, "Starting WebRTC video source capturer")
        videoSource.capturerObserver.onCapturerStarted(true)
    }

    fun stopWebRtc() {
        Log.d(tag, "Stopping WebRTC video source capturer")
        videoSource.capturerObserver.onCapturerStopped()
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
            val supportedDevice = deviceList.values.firstOrNull { serialConnectionManager.isSerialDevice(it) }

            if (supportedDevice == null) {
                updateStatusMessage("Please connect a USB serial device.")
                return@launch
            }

            if (usbManager.hasPermission(supportedDevice)) {
                connectToDevice(supportedDevice)
            } else {
                _uiState.update { it.copy(permissionRequestDevice = supportedDevice) }
            }
        }
    }

    fun permissionRequestCompleted() {
        _uiState.update { it.copy(permissionRequestDevice = null) }
    }

    fun sendStringCommand(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val data = if (command.startsWith("0x")) {
                command.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                command.replace("\\n", "\n").replace("\\r", "\r").toByteArray()
            }
            val outboundData = "OUT: ".toByteArray() + data
            appendToSerialLog(outboundData)
            serialConnectionManager.write(data)
            writeToWebsocket(outboundData)
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

    @ExperimentalGetImage
    fun processFrame(image: ImageProxy) {
        val i420Buffer = image.toI420()
        if (i420Buffer != null) {
            val timestamp = TimeUnit.MICROSECONDS.toNanos(image.imageInfo.timestamp)
            val videoFrame = VideoFrame(i420Buffer, image.imageInfo.rotationDegrees, timestamp)
            videoSource.capturerObserver.onFrameCaptured(videoFrame)
            videoFrame.release()
        } else {
            Log.e(tag, "Failed to convert ImageProxy to I420Buffer")
        }
        image.close()
    }

    @ExperimentalGetImage
    fun ImageProxy.toI420(): VideoFrame.I420Buffer? {
        if (format != ImageFormat.YUV_420_888) {
            Log.e(tag, "Image format is not YUV_420_888")
            return null
        }

        val yData = planes[0].buffer
        val uData = planes[1].buffer
        val vData = planes[2].buffer

        val yStride = planes[0].rowStride
        val uStride = planes[1].rowStride
        val vStride = planes[2].rowStride

        val uPixelStride = planes[1].pixelStride

        val i420Buffer = JavaI420Buffer.allocate(width, height)

        if (uPixelStride == 2) { // Case for NV21/NV12 (interleaved U/V)
            i420Buffer.dataY.put(yData)

            val vBuffer = vData.duplicate()
            val uBuffer = uData.duplicate()

            val vDest = i420Buffer.dataV
            val uDest = i420Buffer.dataU

            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    vDest.put(vBuffer[row * vStride + col * uPixelStride])
                    uDest.put(uBuffer[row * uStride + col * uPixelStride])
                }
            }
        } else { // Case for I420 (planar)
            YuvHelper.I420Copy(
                yData, yStride, uData, uStride, vData, vStride,
                i420Buffer.dataY, i420Buffer.strideY, i420Buffer.dataU, i420Buffer.strideU, i420Buffer.dataV, i420Buffer.strideV,
                width, height
            )
        }

        return i420Buffer
    }

    private fun startAllServers() {
        startVideoStreamServer()
        startTcpProxyServer()
    }

    private fun stopAllServers() {
        cameraServerSocket?.close()
        tcpProxyServer?.close()
        stopWebRtc()
    }

    fun startVideoStreamServer() {
        if (cameraServerSocket != null && !cameraServerSocket!!.isClosed) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ipAddress = getLocalIpAddress()
                if (ipAddress == null) {
                    _uiState.update { it.copy(cameraStatus = "MJPEG Stream: Could not get IP address.") }
                    return@launch
                }

                cameraServerSocket = ServerSocket(cameraServerPort)
                _uiState.update { it.copy(cameraStatus = "MJPEG Stream: $ipAddress:$cameraServerPort") }

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

                unifiedServer = ServerSocket(unifiedPort)

                _uiState.update {
                    it.copy(
                        networkStatus = "Web Interface: http://$ipAddress:$unifiedPort",
                        webSocketUrl = "WebRTC URL: ws://$ipAddress:$unifiedPort/webrtc"
                    )
                }

                while (true) {
                    val client = unifiedServer!!.accept()
                    Log.d(tag, "Client connected: ${client.inetAddress}")
                    launch { handleClient(client) }
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
                    updateStatusMessage("TCP Proxy: Could not get IP.")
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
                                val outboundData = "OUT: ".toByteArray() + data
                                appendToSerialLog(outboundData)
                                writeToWebsocket(outboundData)
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

    private suspend fun handleClient(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val requestLines = mutableListOf<String>()
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line.isNullOrBlank()) {
                    break
                }
                requestLines.add(line)
            }

            if (requestLines.isEmpty()) {
                client.close()
                return
            }

            val requestLine = requestLines.first()
            val path = requestLine.split(" ").getOrNull(1)

            val headers = requestLines.drop(1).mapNotNull {
                val parts = it.split(": ", limit = 2)
                if (parts.size == 2) parts[0].lowercase() to parts[1] else null
            }.toMap()

            if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                when (path) {
                    "/webrtc" -> handleWebRTCConnection(client, headers)
                    else -> handleSerialWebSocketConnection(client, headers)
                }
            } else {
                try {
                    when (path) {
                        "/macro-editor" -> serveFile(client.getOutputStream(), "macro_editor.html")
                        "/index.html" -> {
                            val htmlFile = if (_uiState.value.isConnected) "index.html" else "no_device.html"
                            serveFile(client.getOutputStream(), htmlFile)
                        }
                        else -> serveFile(client.getOutputStream(), "webrtc.html")
                    }
                } finally {
                    client.close()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling client connection", e)
            try { client.close() } catch (ioe: IOException) { /* ignore */ }
        }
    }

    private suspend fun handleSerialWebSocketConnection(client: Socket, headers: Map<String, String>) {
        try {
            val out = client.getOutputStream()
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

            // Keep the connection alive, but we don't expect any messages from the client
            while (client.isConnected) {
                kotlinx.coroutines.delay(1000)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in serial websocket connection", e)
        } finally {
            synchronized(webSocketClients) { webSocketClients.remove(client) }
            client.close()
        }
    }

    private suspend fun handleWebRTCConnection(client: Socket, headers: Map<String, String>) {
        var peerConnection: PeerConnection? = null
        try {
            val out = client.getOutputStream()
            val `in` = client.getInputStream()

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

            val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
            val observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        synchronized(iceCandidateBuffer) {
                            iceCandidateBuffer.add(it)
                        }
                    }
                }
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }

            peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            peerConnection?.addTransceiver(videoTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            peerConnection?.addTransceiver(audioTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))

            startWebRtc()

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(offer: SessionDescription?) {
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            val offerJson = JSONObject().apply {
                                put("type", "offer")
                                put("sdp", offer?.description)
                            }
                            writeWsFrame(out, offerJson.toString().toByteArray(), 1)
                        }

                        override fun onCreateFailure(p0: String?) { Log.e(tag, "setLocalDescription onCreateFailure: $p0") }
                        override fun onSetFailure(p0: String?) { Log.e(tag, "setLocalDescription onSetFailure: $p0") }
                    }, offer)
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) { Log.e(tag, "createOffer onCreateFailure: $p0") }
                override fun onSetFailure(p0: String?) { Log.e(tag, "createOffer onSetFailure: $p0") }
            }, MediaConstraints())

            while (client.isConnected) {
                val payload = readWsFrame(`in`) ?: break
                val message = JSONObject(String(payload))

                when (message.optString("type")) {
                    "answer" -> {
                        val sdp = message.getString("sdp")
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.d(tag, "Remote description set, draining ${iceCandidateBuffer.size} ICE candidates.")
                                synchronized(iceCandidateBuffer) {
                                    iceCandidateBuffer.forEach { candidate ->
                                        try {
                                            val candidateJson = JSONObject().apply {
                                                put("candidate", candidate.sdp)
                                                put("sdpMid", candidate.sdpMid)
                                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                                            }
                                            val iceMessage = JSONObject().apply {
                                                put("type", "iceCandidate")
                                                put("candidate", candidateJson)
                                            }
                                            writeWsFrame(out, iceMessage.toString().toByteArray(), 1)
                                        } catch (e: IOException) {
                                            Log.e(tag, "Failed to send buffered ICE candidate", e)
                                        }
                                    }
                                    iceCandidateBuffer.clear()
                                }
                            }
                            override fun onCreateFailure(p0: String?) { Log.e(tag, "setRemoteDescription onCreateFailure: $p0") }
                            override fun onSetFailure(p0: String?) { Log.e(tag, "setRemoteDescription onSetFailure: $p0") }
                        }, answer)
                    }
                    "iceCandidate" -> {
                        val candidate = message.getJSONObject("candidate")
                        val iceCandidate = IceCandidate(
                            candidate.getString("sdpMid"),
                            candidate.getInt("sdpMLineIndex"),
                            candidate.getString("candidate")
                        )
                        peerConnection?.addIceCandidate(iceCandidate)
                    }
                    "requestKeyFrame" -> {
                        Log.d(tag, "Keyframe requested by remote")
                        videoSource.capturerObserver.onCapturerStarted(true)
                    }
                    "getMacros" -> {
                        val macros = _uiState.value.macros
                        val macrosJson = gson.toJson(macros)
                        val responseMsg = JSONObject().apply {
                            put("type", "macros")
                            put("data", macrosJson)
                        }
                        writeWsFrame(client.getOutputStream(), responseMsg.toString().toByteArray(), 1)
                    }
                    "sendSerial" -> {
                        val command = message.getString("command")
                        sendStringCommand(command)
                    }
                    "saveMacros" -> {
                        val macrosJson = message.getString("data")
                        val type = object : TypeToken<List<Macro>>() {}.type
                        val macros = gson.fromJson<List<Macro>>(macrosJson, type)
                        saveMacros(macros)
                        writeWsFrame(client.getOutputStream(), "{\"type\":\"macrosSaved\"}".toByteArray(), 1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in webrtc connection", e)
        } finally {
            peerConnection?.dispose()
            stopWebRtc()
            iceCandidateBuffer.clear()
            synchronized(webSocketClients) { webSocketClients.remove(client) }
            client.close()
        }
    }

    @Throws(IOException::class)
    private fun serveFile(out: OutputStream, fileName: String, contentType: String = "text/html") {
        try {
            val content = application.assets.open(fileName).bufferedReader().use { it.readText() }
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${content.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" + content
            out.write(response.toByteArray())
            out.flush()
        } catch (e: IOException) {
            val errorResponse = "HTTP/1.1 404 Not Found\r\n\r\nFile not found."
            out.write(errorResponse.toByteArray())
            out.flush()
        }
    }

    @Throws(IOException::class)
    private fun readWsFrame(stream: InputStream): ByteArray? {
        val b1 = stream.read()
        if (b1 == -1) return null

        val opCode = b1 and 0x0F
        if (opCode == 8) return null // Close frame
        if (opCode != 1 && opCode != 2) return null // We only support text and binary frames

        val b2 = stream.read()
        if (b2 == -1) return null

        val isMasked = (b2 and 0x80) != 0
        if (!isMasked) return null // All client frames must be masked

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

    override fun onCleared() {
        super.onCleared()
        disconnect()
        peerConnectionFactory.dispose()
        eglBase.release()
    }

    fun updateStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun onUsbDeviceDetached() {
        _uiState.update { it.copy(statusMessage = "USB device detached. Please connect a device.", networkStatus = "", tcpProxyStatus = "") }
    }

    fun setCameraPermission(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
        if(!granted) {
            updateStatusMessage("Camera permission is required for preview.")
        }
    }

    fun setAudioPermission(granted: Boolean) {
        _uiState.update { it.copy(hasAudioPermission = granted) }
        if(!granted) {
            updateStatusMessage("Audio permission is required for streaming.")
        }
    }

    private fun appendToSerialLog(data: ByteArray) {
        val prefs = application.getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        val displayMode = prefs.getString("display_mode", "Raw")
        val newLog = if (displayMode == "Hex") {
            data.toHexString()
        } else {
            String(data)
        }
        _uiState.update { it.copy(serialLog = _uiState.value.serialLog + newLog) }
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