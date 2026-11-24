package com.novalive.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    private lateinit var channelName: String
    private lateinit var streamUrl: String

    private lateinit var status: TextView
    private lateinit var retryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        streamUrl = intent.getStringExtra("url") ?: ""
        channelName = intent.getStringExtra("title") ?: "Nova Live"

        if (streamUrl.isBlank()) {
            finish()
            return
        }

        createPlayerUI()
        initializePlayer()
    }

    private fun createPlayerUI() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 9, 20))
        }

        val title = TextView(this).apply {
            text = "▶  $channelName"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 15, 18, 15)
        }

        playerView = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
        }

        status = TextView(this).apply {
            text = "Connecting to stream..."
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 12, 10, 12)
        }

        retryButton = Button(this).apply {
            text = "Retry Playback"
            visibility = View.GONE
            setOnClickListener {
                initializePlayer()
            }
        }

        val tracksButton = Button(this).apply {
            text = "Audio / Subtitle Tracks"
            setOnClickListener {
                showTracks()
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                finish()
            }
        }

        root.addView(title)

        root.addView(
            playerView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(status)
        root.addView(retryButton)
        root.addView(tracksButton)
        root.addView(backButton)

        setContentView(root)
    }

    private fun initializePlayer() {

        releasePlayer()

        status.text = "Loading channel..."
        retryButton.visibility = View.GONE

        try {

            val exoPlayer = ExoPlayer.Builder(this).build()

            player = exoPlayer
            playerView.player = exoPlayer

            exoPlayer.addListener(
                object : Player.Listener {

                    override fun onPlaybackStateChanged(
                        playbackState: Int
                    ) {

                        when (playbackState) {

                            Player.STATE_BUFFERING -> {
                                status.text = "Buffering..."
                            }

                            Player.STATE_READY -> {
                                status.text = "Playing: $channelName"
                                retryButton.visibility = View.GONE
                            }

                            Player.STATE_ENDED -> {
                                status.text = "Stream ended"
                            }

                            Player.STATE_IDLE -> {
                                status.text = "Player idle"
                            }
                        }
                    }

                    override fun onPlayerError(
                        error: androidx.media3.common.PlaybackException
                    ) {

                        status.text =
                            "Playback error: ${error.errorCodeName}"

                        retryButton.visibility = View.VISIBLE
                    }
                }
            )

            val mediaItem = MediaItem.fromUri(streamUrl)

            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

        } catch (e: Exception) {

            status.text = "Error: ${e.message}"
            retryButton.visibility = View.VISIBLE
        }
    }

    private fun showTracks() {

        val exoPlayer = player ?: return

        TrackSelectionDialogBuilder(
            this,
            "Select Audio / Subtitle Track",
            exoPlayer,
            androidx.media3.common.C.TRACK_TYPE_AUDIO
        ).build().show()

        // Subtitle track selection can also be opened
        // from the Media3 PlayerView subtitle controls
    }

    private fun releasePlayer() {

        playerView.let {
            if (::playerView.isInitialized) {
                it.player = null
            }
        }

        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
