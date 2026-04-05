package com.airplay.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.airplay.receiver.R
import com.airplay.receiver.player.PlayerActivity
import com.airplay.receiver.server.AirPlayMdnsService
import com.airplay.receiver.server.AirPlayServer
import com.airplay.receiver.ui.MainActivity

/**
 * Foreground service that keeps the AirPlay server and mDNS advertisement
 * running in the background. This allows receiving AirPlay connections
 * even when the app UI is not visible.
 */
class AirPlayService : Service(), AirPlayServer.AirPlayListener {

    companion object {
        private const val TAG = "AirPlayService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "airplay_service"

        const val ACTION_STATUS_CHANGED = "com.airplay.receiver.STATUS_CHANGED"
        const val EXTRA_STATUS = "status"

        fun start(context: Context) {
            val intent = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPlayService::class.java))
        }
    }

    private var airPlayServer: AirPlayServer? = null
    private var mdnsService: AirPlayMdnsService? = null

    // Playback state reported by PlayerActivity
    private var currentDuration: Double = 0.0
    private var currentPosition: Double = 0.0
    private var isPlaying: Boolean = false
    private var currentRate: Float = 0f

    private val playbackInfoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlayerActivity.ACTION_PLAYBACK_INFO) {
                currentDuration = intent.getDoubleExtra(PlayerActivity.EXTRA_DURATION, 0.0)
                currentPosition = intent.getDoubleExtra(PlayerActivity.EXTRA_CURRENT_POSITION, 0.0)
                isPlaying = intent.getBooleanExtra(PlayerActivity.EXTRA_IS_PLAYING, false)
                currentRate = intent.getFloatExtra(PlayerActivity.EXTRA_RATE, 0f)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        val filter = IntentFilter(PlayerActivity.ACTION_PLAYBACK_INFO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackInfoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playbackInfoReceiver, filter)
        }

        startAirPlayServer()
    }

    private fun startAirPlayServer() {
        try {
            val deviceName = getDeviceName()

            // Start HTTP server
            airPlayServer = AirPlayServer(AirPlayServer.AIRPLAY_PORT, this).apply {
                start()
            }
            Log.d(TAG, "AirPlay server started on port ${AirPlayServer.AIRPLAY_PORT}")

            // Start mDNS advertisement
            mdnsService = AirPlayMdnsService(this).apply {
                start(deviceName)
            }
            Log.d(TAG, "mDNS service started with name: $deviceName")

            broadcastStatus(getString(R.string.status_service_running))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AirPlay server", e)
        }
    }

    private fun getDeviceName(): String {
        val prefs = getSharedPreferences("airplay_prefs", MODE_PRIVATE)
        return prefs.getString("device_name", null) ?: run {
            val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            if (name.isBlank()) "Android TV AirPlay" else name
        }
    }

    // --- AirPlayListener callbacks ---

    override fun onVideoPlay(url: String, startPosition: Double) {
        Log.d(TAG, "Play video: $url")
        val intent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, url)
            putExtra(PlayerActivity.EXTRA_START_POSITION, startPosition)
        }
        startActivity(intent)
        broadcastStatus(getString(R.string.status_playing))
    }

    override fun onVideoStop() {
        Log.d(TAG, "Stop video")
        sendBroadcast(Intent(PlayerActivity.ACTION_STOP))
        isPlaying = false
        broadcastStatus(getString(R.string.status_waiting))
    }

    override fun onVideoPause() {
        Log.d(TAG, "Pause video")
        sendBroadcast(Intent(PlayerActivity.ACTION_PAUSE))
    }

    override fun onVideoResume() {
        Log.d(TAG, "Resume video")
        sendBroadcast(Intent(PlayerActivity.ACTION_RESUME))
    }

    override fun onVideoScrub(position: Double) {
        Log.d(TAG, "Scrub to: $position")
        sendBroadcast(Intent(PlayerActivity.ACTION_SCRUB).apply {
            putExtra(PlayerActivity.EXTRA_POSITION, position)
        })
    }

    override fun onVideoGetPlaybackInfo(): AirPlayServer.PlaybackInfo {
        return AirPlayServer.PlaybackInfo(
            duration = currentDuration,
            position = currentPosition,
            rate = currentRate,
            isPlaying = isPlaying
        )
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_text)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_STATUS, status)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        unregisterReceiver(playbackInfoReceiver)

        airPlayServer?.stop()
        airPlayServer = null

        mdnsService?.stop()
        mdnsService = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
