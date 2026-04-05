package com.airplay.receiver.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.airplay.receiver.R

/**
 * Full-screen video player activity that receives video URLs from the AirPlay service.
 * Controlled via broadcast intents from the background service.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_START_POSITION = "start_position"
        const val ACTION_PLAY = "com.airplay.receiver.ACTION_PLAY"
        const val ACTION_PAUSE = "com.airplay.receiver.ACTION_PAUSE"
        const val ACTION_RESUME = "com.airplay.receiver.ACTION_RESUME"
        const val ACTION_STOP = "com.airplay.receiver.ACTION_STOP"
        const val ACTION_SCRUB = "com.airplay.receiver.ACTION_SCRUB"
        const val EXTRA_POSITION = "position"

        // Broadcast from player back to service with playback info
        const val ACTION_PLAYBACK_INFO = "com.airplay.receiver.ACTION_PLAYBACK_INFO"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_CURRENT_POSITION = "current_position"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_RATE = "rate"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY -> {
                    val url = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return
                    val startPos = intent.getDoubleExtra(EXTRA_START_POSITION, 0.0)
                    playVideo(url, startPos)
                }
                ACTION_PAUSE -> player?.pause()
                ACTION_RESUME -> player?.play()
                ACTION_STOP -> {
                    player?.stop()
                    finish()
                }
                ACTION_SCRUB -> {
                    val pos = intent.getDoubleExtra(EXTRA_POSITION, 0.0)
                    player?.seekTo((pos * 1000).toLong())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        initPlayer()

        // Register for control commands from the service
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
            addAction(ACTION_STOP)
            addAction(ACTION_SCRUB)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        // Play the initial video if URL was provided in the launch intent
        intent?.getStringExtra(EXTRA_VIDEO_URL)?.let { url ->
            val startPos = intent.getDoubleExtra(EXTRA_START_POSITION, 0.0)
            playVideo(url, startPos)
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also {
            playerView.player = it

            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    broadcastPlaybackInfo()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    broadcastPlaybackInfo()
                }
            })
        }
    }

    private fun playVideo(url: String, startPosition: Double) {
        player?.let { p ->
            val mediaItem = MediaItem.fromUri(url)
            p.setMediaItem(mediaItem)
            p.prepare()
            if (startPosition > 0) {
                p.seekTo((startPosition * p.duration).toLong())
            }
            p.play()
        }
    }

    private fun broadcastPlaybackInfo() {
        player?.let { p ->
            val intent = Intent(ACTION_PLAYBACK_INFO).apply {
                putExtra(EXTRA_DURATION, p.duration / 1000.0)
                putExtra(EXTRA_CURRENT_POSITION, p.currentPosition / 1000.0)
                putExtra(EXTRA_IS_PLAYING, p.isPlaying)
                putExtra(EXTRA_RATE, if (p.isPlaying) p.playbackParameters.speed else 0f)
            }
            sendBroadcast(intent)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_BACK -> {
                player?.stop()
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(controlReceiver)
        player?.release()
        player = null
    }
}
