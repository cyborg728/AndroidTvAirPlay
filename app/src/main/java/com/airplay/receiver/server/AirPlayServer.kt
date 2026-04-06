package com.airplay.receiver.server

import android.util.Log
import com.airplay.receiver.crypto.PairSetupHandler
import com.airplay.receiver.crypto.PairVerifyHandler
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Embedded HTTP server that handles AirPlay protocol requests.
 * Supports AirPlay 2 transient pairing for modern iOS devices.
 */
class AirPlayServer(
    port: Int,
    private val listener: AirPlayListener
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "AirPlayServer"
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

    private val pairSetupHandler = PairSetupHandler()
    private var pairVerifyHandler = PairVerifyHandler()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                method == Method.POST && uri == "/play" -> handlePlay(session)
                method == Method.POST && uri == "/stop" -> handleStop()
                method == Method.POST && uri == "/rate" -> handleRate(session)
                method == Method.POST && uri == "/scrub" -> handleScrub(session)
                method == Method.GET && uri == "/scrub" -> handleGetScrub()
                method == Method.GET && uri == "/playback-info" -> handlePlaybackInfo()
                method == Method.POST && uri == "/action" -> handleAction(session)
                method == Method.GET && uri == "/server-info" -> handleServerInfo()
                method == Method.GET && uri == "/info" -> handleInfo()
                method == Method.POST && uri == "/pair-setup" -> handlePairSetup(session)
                method == Method.POST && uri == "/pair-verify" -> handlePairVerify(session)
                method == Method.POST && uri == "/fp-setup" -> handleFpSetup(session)
                method == Method.POST && uri == "/feedback" -> handleFeedback(session)
                method == Method.POST && (uri == "/command" || uri == "/action") -> handleCommand(session)
                method == Method.GET && uri == "/artwork" -> handleArtwork()
                else -> {
                    Log.d(TAG, "Unhandled request: $method $uri")
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: $method $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
        }
    }

    private fun readBodyBytes(session: IHTTPSession): ByteArray {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return ByteArray(0)
        val buffer = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = session.inputStream.read(buffer, totalRead, contentLength - totalRead)
            if (read < 0) break
            totalRead += read
        }
        return if (totalRead == contentLength) buffer else buffer.copyOf(totalRead)
    }

    private fun readBodyString(session: IHTTPSession): String {
        val bytes = readBodyBytes(session)
        return String(bytes)
    }

    // --- Pairing handlers ---

    private fun handlePairSetup(session: IHTTPSession): Response {
        val data = readBodyBytes(session)
        Log.d(TAG, "pair-setup: received ${data.size} bytes")

        val responseData = pairSetupHandler.handle(data)

        Log.d(TAG, "pair-setup: responding with ${responseData.size} bytes")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            ByteArrayInputStream(responseData),
            responseData.size.toLong()
        )
    }

    private fun handlePairVerify(session: IHTTPSession): Response {
        val data = readBodyBytes(session)
        Log.d(TAG, "pair-verify: received ${data.size} bytes")

        val responseData = pairVerifyHandler.handle(data)

        if (pairVerifyHandler.isComplete()) {
            Log.d(TAG, "pair-verify: COMPLETE - session established!")
            // Reset for next connection
            pairVerifyHandler = PairVerifyHandler()
        }

        Log.d(TAG, "pair-verify: responding with ${responseData.size} bytes")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            ByteArrayInputStream(responseData),
            responseData.size.toLong()
        )
    }

    private fun handleFpSetup(session: IHTTPSession): Response {
        val data = readBodyBytes(session)
        Log.d(TAG, "fp-setup: received ${data.size} bytes (FairPlay - acknowledging)")
        // FairPlay DRM setup — return empty OK to skip DRM
        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", "")
    }

    // --- Playback handlers ---

    private fun handlePlay(session: IHTTPSession): Response {
        val body = readBodyString(session)
        Log.d(TAG, "Play body: $body")

        var videoUrl = ""
        var startPosition = 0.0

        body.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Content-Location:") -> {
                    videoUrl = trimmed.substringAfter("Content-Location:").trim()
                }
                trimmed.startsWith("Start-Position:") -> {
                    startPosition = trimmed.substringAfter("Start-Position:").trim().toDoubleOrNull() ?: 0.0
                }
            }
        }

        if (videoUrl.isNotEmpty()) {
            Log.d(TAG, "Playing video: $videoUrl at position $startPosition")
            listener.onVideoPlay(videoUrl, startPosition)
        }

        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleStop(): Response {
        listener.onVideoStop()
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleRate(session: IHTTPSession): Response {
        val params = session.parms
        val rate = params["value"]?.toFloatOrNull() ?: 1.0f
        Log.d(TAG, "Rate: $rate")
        if (rate == 0.0f) listener.onVideoPause() else listener.onVideoResume()
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleScrub(session: IHTTPSession): Response {
        val params = session.parms
        val position = params["position"]?.toDoubleOrNull() ?: 0.0
        Log.d(TAG, "Scrub to: $position")
        listener.onVideoScrub(position)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleGetScrub(): Response {
        val info = listener.onVideoGetPlaybackInfo()
        val body = "duration: ${info.duration}\nposition: ${info.position}\n"
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, body)
    }

    private fun handlePlaybackInfo(): Response {
        val info = listener.onVideoGetPlaybackInfo()
        val rateValue = if (info.isPlaying) info.rate else 0.0f
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>duration</key>
    <real>${info.duration}</real>
    <key>position</key>
    <real>${info.position}</real>
    <key>rate</key>
    <real>$rateValue</real>
    <key>readyToPlay</key>
    <true/>
    <key>playbackBufferEmpty</key>
    <false/>
    <key>playbackBufferFull</key>
    <true/>
    <key>playbackLikelyToKeepUp</key>
    <true/>
    <key>loadedTimeRanges</key>
    <array>
        <dict>
            <key>duration</key>
            <real>${info.duration}</real>
            <key>start</key>
            <real>0.0</real>
        </dict>
    </array>
    <key>seekableTimeRanges</key>
    <array>
        <dict>
            <key>duration</key>
            <real>${info.duration}</real>
            <key>start</key>
            <real>0.0</real>
        </dict>
    </array>
</dict>
</plist>"""
        return newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", plist)
    }

    private fun handleAction(session: IHTTPSession): Response {
        val body = readBodyString(session)
        if (body.isNotEmpty()) Log.d(TAG, "Action body: $body")
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleCommand(session: IHTTPSession): Response {
        val body = readBodyString(session)
        if (body.isNotEmpty()) Log.d(TAG, "Command body: $body")
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleFeedback(session: IHTTPSession): Response {
        readBodyBytes(session) // consume body
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleArtwork(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
    }

    // --- Info handlers ---

    private fun handleServerInfo(): Response {
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>deviceid</key>
    <string>AA:BB:CC:DD:EE:FF</string>
    <key>features</key>
    <integer>1518338039</integer>
    <key>model</key>
    <string>AppleTV3,2</string>
    <key>protovers</key>
    <string>1.0</string>
    <key>srcvers</key>
    <string>220.68</string>
</dict>
</plist>"""
        return newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", plist)
    }

    private fun handleInfo(): Response {
        Log.d(TAG, "Handling /info request")
        // Return features as 64-bit: lower=0x5A7FFFF7 (1518338039), upper=0x1E (30)
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>deviceid</key>
    <string>AA:BB:CC:DD:EE:FF</string>
    <key>features</key>
    <integer>1518338039</integer>
    <key>model</key>
    <string>AppleTV3,2</string>
    <key>name</key>
    <string>Android TV AirPlay</string>
    <key>protovers</key>
    <string>1.0</string>
    <key>srcvers</key>
    <string>220.68</string>
    <key>statusFlags</key>
    <integer>68</integer>
    <key>pi</key>
    <string>2e388006-13ba-4041-9a67-25dd4a43d536</string>
    <key>pk</key>
    <string>b07727d6f6cd6e08b58571d525391f99be98e8744e5dbcee5ccb705485e05b71</string>
    <key>vv</key>
    <integer>2</integer>
    <key>audioFormats</key>
    <array>
        <dict>
            <key>type</key>
            <integer>96</integer>
            <key>audioInputFormats</key>
            <integer>67108860</integer>
            <key>audioOutputFormats</key>
            <integer>67108860</integer>
        </dict>
    </array>
    <key>audioLatencies</key>
    <array>
        <dict>
            <key>inputLatencyMicros</key>
            <integer>0</integer>
            <key>outputLatencyMicros</key>
            <integer>400000</integer>
            <key>type</key>
            <integer>96</integer>
        </dict>
    </array>
</dict>
</plist>"""
        return newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", plist)
    }
}
