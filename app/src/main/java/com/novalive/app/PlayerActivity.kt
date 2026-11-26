package com.novalive.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var videoFrame: FrameLayout
    private lateinit var infoPanel: LinearLayout
    private lateinit var topTitle: View
    private lateinit var topAccent: View
    private lateinit var resizeBtn: View
    private lateinit var fsBtn: TextView

    private var channelName = "Nova Live"
    private var streamUrl = ""
    private var channelHeaders = emptyMap<String, String>()
    private var attempt = 0
    private var resizeIdx = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.rgb(8, 9, 20)

        streamUrl = intent.getStringExtra("url") ?: ""
        channelName = intent.getStringExtra("title") ?: "Nova Live"
        channelHeaders = try {
            val obj = JSONObject(intent.getStringExtra("headers") ?: "{}")
            buildMap { val keys = obj.keys(); while (keys.hasNext()) { val k = keys.next(); put(k, obj.optString(k)) } }
        } catch (_: Exception) { emptyMap() }
        if (streamUrl.isBlank()) { finish(); return }

        buildUi()
    }

    private fun circleBtn(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(110, 0, 0, 0))
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) }
        setOnClickListener { action() }
    }

    private fun buildUi() {
        videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
        videoFrame.addView(playerView, ViewGroup.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT)
            )
        }
        topBar.addView(circleBtn("←") { finish() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        })
        val bar = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#00C6FF"), Color.parseColor("#FF0A8A"))
            )
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(26)).apply {
                marginStart = dp(12); marginEnd = dp(8)
            }
        }
        topBar.addView(bar)
        topBar.addView(TextView(this).apply {
            text = channelName
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (Build.VERSION.SDK_INT >= 26) topBar.addView(circleBtn("⧉") { enterPip() })
        topAccent = topBar.getChildAt(1)
        topTitle = topBar.getChildAt(2)
        resizeBtn = circleBtn("⛶") {
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeIdx]
        }
        topBar.addView(resizeBtn)
        fsBtn = circleBtn("⤢") { toggleOrientation() }
        topBar.addView(fsBtn)
        topBar.addView(circleBtn("⚙") { showSettings() })
        videoFrame.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v -> topBar.visibility = v }
        )

        spinner = ProgressBar(this)
        videoFrame.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(30), dp(20), dp(30), dp(20))
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }
        errorText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        }
        errorBox.addView(errorText)
        errorBox.addView(Button(this).apply {
            text = "Retry"
            setOnClickListener { attempt = 0; initializePlayer() }
        })
        videoFrame.addView(errorBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        infoPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 9, 20))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(TextView(this@PlayerActivity).apply {
                text = channelName
                textSize = 22f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@PlayerActivity).apply {
                text = "● LIVE"
                textSize = 14f
                setTextColor(Color.parseColor("#FF2D7A"))
                setPadding(0, dp(6), 0, dp(10))
            })
            addView(TextView(this@PlayerActivity).apply {
                text = "Tap ⤢ for landscape fullscreen"
                textSize = 13f
                setTextColor(Color.parseColor("#8B8B95"))
            })
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 9, 20))
            fitsSystemWindows = true
            addView(videoFrame, LinearLayout.LayoutParams(-1, dp(220)))
            addView(infoPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(outer)
        applyLayout()
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .setAutoEnterEnabled(true)
                        .build()
                )
            } catch (e: Exception) { }
        }
    }

    private fun isLandscape() =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleOrientation() {
        requestedOrientation = if (isLandscape())
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun applyLayout() {
        val land = isLandscape()
        val lp = videoFrame.layoutParams as LinearLayout.LayoutParams
        if (land) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            infoPanel.visibility = View.GONE
            hideSystemBars()
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            infoPanel.visibility = View.VISIBLE
            showSystemBars()
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        videoFrame.layoutParams = lp
        val v = if (land) View.VISIBLE else View.GONE
        topTitle.visibility = v
        topAccent.visibility = v
        resizeBtn.visibility = v
        fsBtn.text = if (land) "⤡" else "⤢"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val pip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (isLandscape() && !pip) toggleOrientation() else super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isLandscape()) hideSystemBars()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            } catch (e: Exception) { }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        playerView.useController = !inPip
        if (inPip) topBar.visibility = View.GONE
    }

    // url|nova=<base64 json>  OR  url|Header=value&Header2=value
    private fun parseStream(raw: String): Triple<String, Map<String, String>, String?> {
        val idx = raw.indexOf('|')
        if (idx < 0) return Triple(raw, emptyMap(), null)
        val url = raw.substring(0, idx)
        val extra = raw.substring(idx + 1)
        val headers = LinkedHashMap<String, String>()
        var key: String? = null
        if (extra.startsWith("nova=")) {
            try {
                val o = JSONObject(String(Base64.decode(extra.substring(5), Base64.NO_WRAP)))
                o.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k -> headers[k] = h.getString(k) }
                }
                key = o.optString("key", "").ifBlank { null }
            } catch (e: Exception) { }
        } else {
            extra.split("&").forEach { p ->
                val i = p.indexOf('=')
                if (i > 0) headers[p.substring(0, i).trim()] = p.substring(i + 1).trim()
            }
        }
        return Triple(url, headers, key)
    }

    private fun toB64Url(v: String): String {
        val bytes = if (Regex("^[0-9a-fA-F]+$").matches(v) && v.length % 2 == 0)
            ByteArray(v.length / 2) { v.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        else return v
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun clearKeyJson(spec: String): String {
        val keys = JSONArray()
        spec.split(",").forEach { pair ->
            val p = pair.split(":")
            if (p.size == 2) keys.put(
                JSONObject().put("kty", "oct").put("kid", toB64Url(p[0].trim())).put("k", toB64Url(p[1].trim()))
            )
        }
        return JSONObject().put("keys", keys).put("type", "temporary").toString()
    }

    private fun initializePlayer() {
        releasePlayer()
        errorBox.visibility = View.GONE
        spinner.visibility = View.VISIBLE

        try {
            val (url, inlineHeaders, key) = parseStream(streamUrl)
            val headers = LinkedHashMap<String, String>().apply {
                putAll(channelHeaders)
                putAll(inlineHeaders)
            }

            val ua = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
                ?: "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(headers.filterKeys { !it.equals("user-agent", true) })

            val msf = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

            val item = MediaItem.Builder().setUri(url)
            val lower = url.substringBefore('?').lowercase()
            if (attempt == 0) {
                val progressive = listOf(".mp4", ".mkv", ".ts", ".flv", ".avi", ".mp3", ".aac")
                when {
                    lower.contains(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
                    lower.contains(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
                    progressive.none { lower.endsWith(it) } -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }

            if (key != null) {
                item.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(LocalMediaDrmCallback(clearKeyJson(key).toByteArray()))
                msf.setDrmSessionManagerProvider(DrmSessionManagerProvider { manager })
            }

            val exo = ExoPlayer.Builder(this).setMediaSourceFactory(msf).build()
            player = exo
            playerView.player = exo

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    spinner.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (attempt == 0) {
                        attempt = 1
                        initializePlayer()
                        return
                    }
                    spinner.visibility = View.GONE
                    val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                    val detail = when {
                        http != null -> "Server replied HTTP ${http.responseCode}"
                        else -> error.cause?.message ?: error.message ?: ""
                    }
                    errorText.text = "Stream could not be played\n\n${error.errorCodeName}\n$detail"
                    errorBox.visibility = View.VISIBLE
                }
            })

            exo.setMediaItem(item.build())
            exo.prepare()
            exo.playWhenReady = true

        } catch (e: Exception) {
            spinner.visibility = View.GONE
            errorText.text = "Error: ${e.message}"
            errorBox.visibility = View.VISIBLE
        }
    }

    private fun showSettings() {
        val exo = player ?: return
        val types = listOf(
            "Video Quality" to C.TRACK_TYPE_VIDEO,
            "Audio" to C.TRACK_TYPE_AUDIO,
            "Subtitles" to C.TRACK_TYPE_TEXT
        ).filter { (_, t) -> exo.currentTracks.groups.any { it.type == t } }

        if (types.isEmpty()) return
        AlertDialog.Builder(this)
            .setItems(types.map { it.first }.toTypedArray()) { _, i ->
                TrackSelectionDialogBuilder(this, types[i].first, exo, types[i].second).build().show()
            }.show()
    }

    private fun releasePlayer() {
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        attempt = 0
        initializePlayer()
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
