package com.airplay.receiver.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Embedded HTTP server that handles AirPlay protocol requests.
 *
 * AirPlay video streaming works by the sender (iPhone/iPad/Mac) sending
 * an HTTP POST to /play with the video URL in the body. The receiver
 * then plays the video from that URL.
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

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                // POST /play — start video playback
                method == Method.POST && uri == "/play" -> handlePlay(session)

                // POST /stop — stop playback
                method == Method.POST && uri == "/stop" -> handleStop()

                // POST /rate — pause/resume (rate=0 means pause, rate=1 means play)
                method == Method.POST && uri == "/rate" -> handleRate(session)

                // POST /scrub — seek to position
                method == Method.POST && uri == "/scrub" -> handleScrub(session)

                // GET /scrub — get current position
                method == Method.GET && uri == "/scrub" -> handleGetScrub()

                // GET /playback-info — get playback status
                method == Method.GET && uri == "/playback-info" -> handlePlaybackInfo()

                // POST /action — handle generic actions
                method == Method.POST && uri == "/action" -> handleAction(session)

                // GET /server-info — server capabilities
                method == Method.GET && uri == "/server-info" -> handleServerInfo()

                // Respond OK to anything else
                else -> {
                    Log.d(TAG, "Unhandled request: $method $uri")
                    newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error")
        }
    }

    private fun handlePlay(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val reader = BufferedReader(InputStreamReader(session.inputStream))
            val buffer = CharArray(contentLength)
            reader.read(buffer, 0, contentLength)
            String(buffer)
        } else {
            ""
        }

        Log.d(TAG, "Play body: $body")

        // Parse the plist-style body to extract Content-Location and Start-Position
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

        if (rate == 0.0f) {
            listener.onVideoPause()
        } else {
            listener.onVideoResume()
        }

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
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val reader = BufferedReader(InputStreamReader(session.inputStream))
            val buffer = CharArray(contentLength)
            reader.read(buffer, 0, contentLength)
            val body = String(buffer)
            Log.d(TAG, "Action body: $body")
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleServerInfo(): Response {
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>deviceid</key>
    <string>00:11:22:33:44:55</string>
    <key>features</key>
    <integer>119</integer>
    <key>model</key>
    <string>AndroidTV</string>
    <key>protovers</key>
    <string>1.0</string>
    <key>srcvers</key>
    <string>150.33</string>
</dict>
</plist>"""
        return newFixedLengthResponse(Response.Status.OK, "text/x-apple-plist+xml", plist)
    }
}
