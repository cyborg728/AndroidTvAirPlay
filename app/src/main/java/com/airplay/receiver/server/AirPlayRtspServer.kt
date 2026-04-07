package com.airplay.receiver.server

import android.util.Log
import com.airplay.receiver.crypto.PairSetupHandler
import com.airplay.receiver.crypto.PairVerifyHandler
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Combined HTTP + RTSP server for AirPlay 2.
 *
 * Modern AirPlay 2 uses RTSP on port 7000 for video setup.
 * iOS sends: GET /info (HTTP), then POST /pair-setup, POST /pair-verify,
 * then SETUP/ANNOUNCE/RECORD (RTSP) for actual streaming.
 * This server handles all of these on a single port.
 */
class AirPlayRtspServer(
    private val port: Int,
    private val listener: AirPlayListener,
    private val deviceName: String,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "AirPlayRtsp"
        const val AIRPLAY_PORT = 7000
    }

    interface AirPlayListener {
        fun onVideoPlay(url: String, startPosition: Double)
        fun onVideoStop()
        fun onVideoPause()
        fun onVideoResume()
        fun onVideoScrub(position: Double)
        fun onVideoGetPlaybackInfo(): PlaybackInfo
    }

    data class PlaybackInfo(
        val duration: Double = 0.0,
        val position: Double = 0.0,
        val rate: Float = 1.0f,
        val isPlaying: Boolean = false
    )

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var running = false
    private val pairSetupHandlers = ConcurrentHashMap<String, PairSetupHandler>()
    private val pairVerifyHandlers = ConcurrentHashMap<String, PairVerifyHandler>()

    // Allocated UDP ports for streaming
    private var eventPort = 0
    private var dataPort = 0
    private var timingPort = 0

    fun start() {
        running = true
        executor = Executors.newCachedThreadPool()
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Server started on port $port")
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor?.submit { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    fun stop() {
        running = false
        serverSocket?.close()
        executor?.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        val remoteAddr = socket.remoteSocketAddress.toString()
        Log.d(TAG, "Client connected: $remoteAddr")
        try {
            socket.soTimeout = 120000 // 2 minutes — iOS keeps connections alive
            socket.keepAlive = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (running && !socket.isClosed) {
                val request = readRequest(input) ?: break
                val uriPath = request.uri.substringBefore('?')
                Log.d(TAG, "Request from $remoteAddr: ${request.method} ${request.uri} ${request.protocol}")

                val response = handleRequest(request, uriPath, remoteAddr)
                writeResponse(output, response, request.protocol)
                output.flush()

                if (request.method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            if (running) Log.d(TAG, "Client disconnected: $remoteAddr (${e.message})")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    data class Request(
        val method: String,
        val uri: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    data class Response(
        val status: Int,
        val statusText: String,
        val headers: MutableMap<String, String> = mutableMapOf(),
        val body: ByteArray = ByteArray(0)
    )

    private fun readRequest(input: InputStream): Request? {
        val headerLines = mutableListOf<String>()
        val lineBuffer = StringBuilder()

        // Read request line and headers
        while (true) {
            val b = input.read()
            if (b == -1) return null
            val c = b.toChar()
            if (c == '\n') {
                val line = lineBuffer.toString().trimEnd('\r')
                lineBuffer.clear()
                if (line.isEmpty()) break
                headerLines.add(line)
            } else {
                lineBuffer.append(c)
            }
        }

        if (headerLines.isEmpty()) return null

        // Parse request line: METHOD URI PROTOCOL
        val requestLine = headerLines[0].split(" ", limit = 3)
        if (requestLine.size < 2) return null

        val method = requestLine[0]
        val uri = requestLine[1]
        val protocol = if (requestLine.size > 2) requestLine[2] else "HTTP/1.1"

        // Parse headers
        val headers = mutableMapOf<String, String>()
        for (i in 1 until headerLines.size) {
            val colonIdx = headerLines[i].indexOf(':')
            if (colonIdx > 0) {
                val key = headerLines[i].substring(0, colonIdx).trim().lowercase()
                val value = headerLines[i].substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        // Read body
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            buf
        } else {
            ByteArray(0)
        }

        return Request(method, uri, protocol, headers, body)
    }

    private fun writeResponse(output: OutputStream, response: Response, protocol: String) {
        val sb = StringBuilder()
        sb.append("$protocol ${response.status} ${response.statusText}\r\n")
        response.headers["Content-Length"] = response.body.size.toString()
        for ((key, value) in response.headers) {
            sb.append("$key: $value\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray())
        if (response.body.isNotEmpty()) {
            output.write(response.body)
        }
    }

    /** Parse query string parameters from URI */
    private fun parseQueryParams(uri: String): Map<String, String> {
        val queryString = uri.substringAfter('?', "")
        if (queryString.isEmpty()) return emptyMap()
        return queryString.split('&').mapNotNull { param ->
            val parts = param.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    private fun handleRequest(request: Request, uriPath: String, clientAddr: String): Response {
        return try {
            when {
                uriPath == "/info" -> handleInfo()
                uriPath == "/server-info" -> handleServerInfo()
                uriPath == "/pair-setup" -> handlePairSetup(request, clientAddr)
                uriPath == "/pair-verify" -> handlePairVerify(request, clientAddr)
                uriPath == "/pair-pin-start" -> ok() // iOS asks to start PIN display
                uriPath == "/fp-setup" -> handleFpSetup(request)
                uriPath == "/fp-setup2" -> handleFpSetup(request) // AirPlay 2 variant
                uriPath == "/feedback" -> ok()
                uriPath == "/command" -> handleCommand(request)
                uriPath == "/configure" -> handleConfigure(request)
                uriPath == "/play" && request.method == "POST" -> handlePlay(request)
                uriPath == "/stop" -> handleStop()
                uriPath == "/rate" -> handleRate(request)
                uriPath == "/scrub" && request.method == "POST" -> handleScrub(request)
                uriPath == "/scrub" && request.method == "GET" -> handleGetScrub()
                uriPath == "/playback-info" -> handlePlaybackInfo()
                uriPath == "/action" -> handleAction(request)
                uriPath == "/photo" -> handlePhoto(request)
                uriPath == "/slideshow-features" -> handleSlideshowFeatures()
                uriPath == "/volume" -> ok()
                uriPath == "/getProperty" -> handleGetProperty(request)
                uriPath == "/setProperty" -> handleSetProperty(request)
                // RTSP methods
                request.method == "SETUP" -> handleRtspSetup(request)
                request.method == "ANNOUNCE" -> handleRtspAnnounce(request)
                request.method == "RECORD" -> handleRtspRecord(request)
                request.method == "TEARDOWN" -> handleTeardown()
                request.method == "OPTIONS" -> handleOptions(request)
                request.method == "GET_PARAMETER" -> ok()
                request.method == "SET_PARAMETER" -> handleSetParameter(request)
                request.method == "FLUSH" -> ok()
                else -> {
                    Log.d(TAG, "Unhandled: ${request.method} ${request.uri}")
                    ok()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ${request.method} ${request.uri}", e)
            Response(500, "Internal Server Error")
        }
    }

    private fun ok(): Response = Response(200, "OK")

    // --- Info endpoints ---

    private fun handleInfo(): Response {
        Log.d(TAG, "Handling /info")
        val info = linkedMapOf<String, Any>(
            "deviceid" to deviceId,
            "features" to 0x5A7FFFF7L,
            "model" to "AppleTV3,2",
            "name" to deviceName,
            "protovers" to "1.0",
            "srcvers" to "220.68",
            "statusFlags" to 4,
            "pi" to "2e388006-13ba-4041-9a67-25dd4a43d536",
            "pk" to "b07727d6f6cd6e08b58571d525391f99be98e8744e5dbcee5ccb705485e05b71",
            "vv" to 2
        )

        // Try binary plist first, fall back to XML
        return try {
            val body = BPlistEncoder.encode(info)
            Response(200, "OK", mutableMapOf(
                "Content-Type" to "application/x-apple-binary-plist"
            ), body)
        } catch (e: Exception) {
            Log.e(TAG, "Binary plist failed, using XML", e)
            infoAsXml()
        }
    }

    private fun infoAsXml(): Response {
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>deviceid</key>
    <string>$deviceId</string>
    <key>features</key>
    <integer>1518338039</integer>
    <key>model</key>
    <string>AppleTV3,2</string>
    <key>name</key>
    <string>$deviceName</string>
    <key>protovers</key>
    <string>1.0</string>
    <key>srcvers</key>
    <string>220.68</string>
    <key>statusFlags</key>
    <integer>4</integer>
    <key>pi</key>
    <string>2e388006-13ba-4041-9a67-25dd4a43d536</string>
    <key>pk</key>
    <string>b07727d6f6cd6e08b58571d525391f99be98e8744e5dbcee5ccb705485e05b71</string>
    <key>vv</key>
    <integer>2</integer>
</dict>
</plist>"""
        return Response(200, "OK", mutableMapOf(
            "Content-Type" to "text/x-apple-plist+xml"
        ), plist.toByteArray())
    }

    private fun handleServerInfo(): Response = handleInfo()

    // --- Pairing ---

    private fun handlePairSetup(request: Request, clientAddr: String): Response {
        Log.d(TAG, "pair-setup: ${request.body.size} bytes from $clientAddr, hex=${request.body.take(16).joinToString("") { "%02x".format(it) }}...")
        val handler = pairSetupHandlers.getOrPut(clientAddr) { PairSetupHandler() }
        val responseData = handler.handle(request.body)
        Log.d(TAG, "pair-setup response: ${responseData.size} bytes")
        return Response(200, "OK", mutableMapOf(
            "Content-Type" to "application/octet-stream"
        ), responseData)
    }

    private fun handlePairVerify(request: Request, clientAddr: String): Response {
        Log.d(TAG, "pair-verify: ${request.body.size} bytes from $clientAddr, hex=${request.body.take(16).joinToString("") { "%02x".format(it) }}...")
        val handler = pairVerifyHandlers.getOrPut(clientAddr) { PairVerifyHandler() }
        val responseData = handler.handle(request.body)

        if (handler.isComplete()) {
            Log.d(TAG, "pair-verify COMPLETE for $clientAddr")
        }

        Log.d(TAG, "pair-verify response: ${responseData.size} bytes")
        return Response(200, "OK", mutableMapOf(
            "Content-Type" to "application/octet-stream"
        ), responseData)
    }

    private fun handleFpSetup(request: Request): Response {
        Log.d(TAG, "fp-setup: ${request.body.size} bytes")

        // FairPlay setup: iOS sends this for DRM negotiation.
        // For non-DRM content (YouTube links, etc.), we acknowledge but don't enforce.
        val fpType = if (request.body.isNotEmpty()) request.body[0].toInt() and 0xFF else -1
        Log.d(TAG, "fp-setup type: $fpType")

        return Response(200, "OK", mutableMapOf(
            "Content-Type" to "application/octet-stream"
        ), ByteArray(0))
    }

    // --- RTSP handlers ---

    private fun handleOptions(request: Request): Response {
        val cseq = request.headers["cseq"] ?: "0"
        return Response(200, "OK", mutableMapOf(
            "CSeq" to cseq,
            "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"
        ))
    }

    private fun handleRtspSetup(request: Request): Response {
        val cseq = request.headers["cseq"] ?: "0"
        Log.d(TAG, "RTSP SETUP: ${request.uri}, body size: ${request.body.size}")

        // iOS sends binary plist body with stream configuration
        val contentType = request.headers["content-type"] ?: ""
        val config = if (contentType.contains("bplist") || contentType.contains("binary") ||
            (request.body.size > 8 && String(request.body, 0, 6, Charsets.US_ASCII) == "bplist")) {
            BPlistDecoder.decodeAsMap(request.body)
        } else {
            null
        }

        if (config != null) {
            Log.d(TAG, "SETUP config: $config")
        } else {
            Log.d(TAG, "SETUP body (text): ${String(request.body).take(200)}")
        }

        // Allocate UDP ports for streams
        if (eventPort == 0) {
            eventPort = allocateUdpPort()
            dataPort = allocateUdpPort()
            timingPort = allocateUdpPort()
        }

        // Parse transport header
        val transport = request.headers["transport"] ?: ""
        Log.d(TAG, "Transport: $transport")

        // Build response — iOS expects stream port information
        val responseMap = linkedMapOf<String, Any>(
            "eventPort" to eventPort,
            "timingPort" to timingPort,
            "dataPort" to dataPort,
            "isScreenMirroringSession" to false
        )

        // Check if this is a screen mirroring or stream request
        val streamType = config?.get("type")
        if (streamType != null) {
            Log.d(TAG, "Stream type: $streamType")
        }

        return try {
            val body = BPlistEncoder.encode(responseMap)
            Response(200, "OK", mutableMapOf(
                "CSeq" to cseq,
                "Content-Type" to "application/x-apple-binary-plist",
                "Session" to "AIRPLAY_SESSION"
            ), body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode SETUP response", e)
            Response(200, "OK", mutableMapOf(
                "CSeq" to cseq,
                "Session" to "AIRPLAY_SESSION",
                "Transport" to transport
            ))
        }
    }

    private fun handleRtspAnnounce(request: Request): Response {
        val cseq = request.headers["cseq"] ?: "0"
        Log.d(TAG, "RTSP ANNOUNCE, body size: ${request.body.size}")
        if (request.body.isNotEmpty()) {
            Log.d(TAG, "ANNOUNCE body: ${String(request.body).take(500)}")
        }
        return Response(200, "OK", mutableMapOf("CSeq" to cseq))
    }

    private fun handleRtspRecord(request: Request): Response {
        val cseq = request.headers["cseq"] ?: "0"
        Log.d(TAG, "RTSP RECORD")
        return Response(200, "OK", mutableMapOf(
            "CSeq" to cseq,
            "Audio-Latency" to "11025",
            "Audio-Jack-Status" to "connected; type=analog"
        ))
    }

    private fun handleTeardown(): Response {
        Log.d(TAG, "TEARDOWN")
        listener.onVideoStop()
        return ok()
    }

    private fun handleSetParameter(request: Request): Response {
        val cseq = request.headers["cseq"] ?: "0"
        val contentType = request.headers["content-type"] ?: ""

        if (contentType.contains("bplist") || contentType.contains("binary")) {
            val plist = BPlistDecoder.decodeAsMap(request.body)
            Log.d(TAG, "SET_PARAMETER (plist): $plist")
        } else {
            val body = String(request.body)
            Log.d(TAG, "SET_PARAMETER: $body")
            if (body.contains("volume")) {
                Log.d(TAG, "Volume change")
            }
        }

        return Response(200, "OK", mutableMapOf("CSeq" to cseq))
    }

    // --- Playback handlers ---

    private fun handlePlay(request: Request): Response {
        Log.d(TAG, "Play: body size=${request.body.size}")
        val contentType = request.headers["content-type"] ?: ""

        var videoUrl = ""
        var startPosition = 0.0

        // Try binary plist first
        if (contentType.contains("bplist") || contentType.contains("binary") ||
            (request.body.size > 8 && String(request.body, 0, minOf(6, request.body.size), Charsets.US_ASCII) == "bplist")) {

            val plist = BPlistDecoder.decodeAsMap(request.body)
            Log.d(TAG, "Play plist: $plist")
            if (plist != null) {
                videoUrl = (plist["Content-Location"] ?: plist["content-location"] ?: "").toString()
                startPosition = (plist["Start-Position"] ?: plist["start-position"] ?: 0.0) as? Double ?: 0.0
            }
        }

        // Fall back to text parsing
        if (videoUrl.isEmpty()) {
            val body = String(request.body)
            Log.d(TAG, "Play text: $body")
            body.lines().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("Content-Location:", ignoreCase = true) ->
                        videoUrl = trimmed.substringAfter(":").trim()
                    trimmed.startsWith("Start-Position:", ignoreCase = true) ->
                        startPosition = trimmed.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
                }
            }
        }

        if (videoUrl.isNotEmpty()) {
            Log.d(TAG, "Playing: $videoUrl at $startPosition")
            listener.onVideoPlay(videoUrl, startPosition)
        } else {
            Log.w(TAG, "Play: No video URL found in request")
        }
        return ok()
    }

    private fun handleStop(): Response {
        listener.onVideoStop()
        return ok()
    }

    private fun handleRate(request: Request): Response {
        val params = parseQueryParams(request.uri)
        val rate = params["value"]?.toFloatOrNull() ?: 1.0f
        Log.d(TAG, "Rate: $rate")
        if (rate == 0.0f) listener.onVideoPause() else listener.onVideoResume()
        return ok()
    }

    private fun handleScrub(request: Request): Response {
        val params = parseQueryParams(request.uri)
        val position = params["position"]?.toDoubleOrNull() ?: 0.0
        Log.d(TAG, "Scrub to: $position")
        listener.onVideoScrub(position)
        return ok()
    }

    private fun handleGetScrub(): Response {
        val info = listener.onVideoGetPlaybackInfo()
        val body = "duration: ${info.duration}\nposition: ${info.position}\n"
        return Response(200, "OK", mutableMapOf(
            "Content-Type" to "text/parameters"
        ), body.toByteArray())
    }

    private fun handlePlaybackInfo(): Response {
        val info = listener.onVideoGetPlaybackInfo()
        val rateValue = if (info.isPlaying) info.rate else 0.0f
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>duration</key><real>${info.duration}</real>
    <key>position</key><real>${info.position}</real>
    <key>rate</key><real>$rateValue</real>
    <key>readyToPlay</key><true/>
    <key>playbackBufferEmpty</key><false/>
    <key>playbackBufferFull</key><true/>
    <key>playbackLikelyToKeepUp</key><true/>
    <key>loadedTimeRanges</key>
    <array><dict>
        <key>duration</key><real>${info.duration}</real>
        <key>start</key><real>0.0</real>
    </dict></array>
    <key>seekableTimeRanges</key>
    <array><dict>
        <key>duration</key><real>${info.duration}</real>
        <key>start</key><real>0.0</real>
    </dict></array>
</dict></plist>"""
        return Response(200, "OK", mutableMapOf(
            "Content-Type" to "text/x-apple-plist+xml"
        ), plist.toByteArray())
    }

    private fun handleAction(request: Request): Response {
        Log.d(TAG, "Action: body size=${request.body.size}")
        val contentType = request.headers["content-type"] ?: ""
        if (contentType.contains("bplist") || contentType.contains("binary")) {
            val plist = BPlistDecoder.decodeAsMap(request.body)
            Log.d(TAG, "Action plist: $plist")
        } else {
            Log.d(TAG, "Action: ${String(request.body)}")
        }
        return ok()
    }

    private fun handleCommand(request: Request): Response {
        Log.d(TAG, "Command: body size=${request.body.size}")
        val contentType = request.headers["content-type"] ?: ""
        if (contentType.contains("bplist") || contentType.contains("binary")) {
            val plist = BPlistDecoder.decodeAsMap(request.body)
            Log.d(TAG, "Command plist: $plist")
        } else {
            Log.d(TAG, "Command: ${String(request.body)}")
        }
        return ok()
    }

    private fun handleConfigure(request: Request): Response {
        Log.d(TAG, "Configure: body size=${request.body.size}")
        return ok()
    }

    private fun handlePhoto(request: Request): Response {
        Log.d(TAG, "Photo received: ${request.body.size} bytes")
        return ok()
    }

    private fun handleSlideshowFeatures(): Response {
        return ok()
    }

    private fun handleGetProperty(request: Request): Response {
        Log.d(TAG, "GetProperty: ${request.uri}")
        return ok()
    }

    private fun handleSetProperty(request: Request): Response {
        Log.d(TAG, "SetProperty: body size=${request.body.size}")
        return ok()
    }

    /** Allocate a random available UDP port */
    private fun allocateUdpPort(): Int {
        return try {
            val socket = DatagramSocket(0)
            val port = socket.localPort
            socket.close()
            port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate UDP port", e)
            (49152..65535).random()
        }
    }
}
