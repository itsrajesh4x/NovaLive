package com.novalive.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val io = Executors.newSingleThreadExecutor()

    private val prefs by lazy {
        getSharedPreferences("novalive", MODE_PRIVATE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Automatic background refresh schedule
        ChannelRefreshWorker.schedule(
            applicationContext,
            12
        )

        web = WebView(this)
        web.setBackgroundColor(0xff080914.toInt())

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false

        web.addJavascriptInterface(Bridge(), "NovaAndroid")

        setContentView(web)

        web.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.handleBack && window.handleBack()") { r ->
            if (r != "true") finish()
        }
    }

    inner class Bridge {

        @JavascriptInterface
        fun getSavedSources(): String =
            prefs.getString("sources", "[]") ?: "[]"

        @JavascriptInterface
        fun getSavedChannels(): String =
            prefs.getString("channels", "[]") ?: "[]"

        @JavascriptInterface
        fun saveChannels(value: String) {
            prefs.edit().putString("channels", value).apply()
        }

        @JavascriptInterface
        fun saveSources(value: String) {
            prefs.edit().putString("sources", value).apply()
        }

        @JavascriptInterface
        fun quit() {
            runOnUiThread { finishAffinity() }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun play(url: String, title: String) {
            runOnUiThread {
                startActivity(
                    Intent(
                        this@MainActivity,
                        PlayerActivity::class.java
                    )
                    .putExtra("url", url)
                    .putExtra("title", title)
                )
            }
        }

        @JavascriptInterface
        fun loadSource(
            type: String,
            address: String,
            username: String,
            password: String
        ) {
            io.execute {
                try {
                    val result = when (type.lowercase()) {

                        "xtream" ->
                            loadXtream(address, username, password)

                        "direct" ->
                            JSONArray().put(
                                JSONObject()
                                    .put("name", "Direct stream")
                                    .put("url", address)
                                    .put("group", "Direct")
                                    .put("logo", "")
                            )

                        else ->
                            parsePlaylist(fetch(address))
                    }

                    val payload = result.toString()

                    runOnUiThread {
                        web.evaluateJavascript(
                            "window.onSourceLoaded(" +
                                "${JSONObject.quote(payload)}, null)",
                            null
                        )
                    }

                } catch (e: Exception) {

                    val msg = e.message ?: "Source load failed"

                    runOnUiThread {
                        web.evaluateJavascript(
                            "window.onSourceLoaded('[]', " +
                                "${JSONObject.quote(msg)})",
                            null
                        )
                    }
                }
            }
        }
    }

    private fun fetch(address: String): String {

        val connection =
            URL(address).openConnection() as HttpURLConnection

        connection.connectTimeout = 15000
        connection.readTimeout = 20000

        connection.setRequestProperty(
            "User-Agent",
            "NovaLive/1.1"
        )

        try {
            if (connection.responseCode !in 200..299) {
                throw Exception("HTTP ${connection.responseCode}")
            }

            return BufferedReader(
                InputStreamReader(connection.inputStream)
            ).use {
                it.readText()
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun first(
        obj: JSONObject,
        vararg keys: String
    ): String {

        for (key in keys) {
            val value = obj.optString(key, "")

            if (value.isNotBlank() && value != "null") {
                return value
            }
        }

        return ""
    }

    private fun parsePlaylist(raw: String): JSONArray {

        val text = raw.trim()

        if (text.startsWith("[") || text.startsWith("{")) {

            val array: JSONArray =
                if (text.startsWith("[")) {
                    JSONArray(text)
                } else {
                    val root = JSONObject(text)

                    when {
                        root.has("channels") ->
                            root.optJSONArray("channels") ?: JSONArray()

                        root.has("data") &&
                            root.opt("data") is JSONArray ->
                            root.optJSONArray("data") ?: JSONArray()

                        else -> JSONArray()
                    }
                }

            val output = JSONArray()

            for (i in 0 until array.length()) {

                val obj = array.optJSONObject(i) ?: continue

                val url = first(
                    obj,
                    "url",
                    "stream_url",
                    "m3u8_url",
                    "mpd_url",
                    "channel_url",
                    "link"
                )

                if (url.isBlank()) continue

                output.put(
                    JSONObject()
                        .put(
                            "name",
                            first(obj, "name", "title", "channel_name")
                                .ifBlank { "Channel ${i + 1}" }
                        )
                        .put("url", url)
                        .put(
                            "group",
                            first(obj, "group", "category", "group_title")
                                .ifBlank { "Other" }
                        )
                        .put(
                            "logo",
                            first(obj, "logo", "tvg_logo", "icon")
                        )
                )
            }

            if (output.length() == 0) {
                throw Exception("No channels found in JSON")
            }

            return output
        }

        val output = JSONArray()

        var name = "Channel"
        var group = "Other"
        var logo = ""

        text.lineSequence().forEach { line ->

            val s = line.trim()

            if (s.startsWith("#EXTINF", true)) {

                name = s.substringAfterLast(",")
                    .trim()
                    .ifBlank { "Channel" }

                group = Regex(
                    "group-title=\"([^\"]*)\""
                ).find(s)?.groupValues?.get(1) ?: "Other"

                logo = Regex(
                    "tvg-logo=\"([^\"]*)\""
                ).find(s)?.groupValues?.get(1) ?: ""

            } else if (
                s.isNotBlank() &&
                !s.startsWith("#") &&
                (s.startsWith("http://") || s.startsWith("https://"))
            ) {

                output.put(
                    JSONObject()
                        .put("name", name)
                        .put("url", s)
                        .put("group", group)
                        .put("logo", logo)
                )

                name = "Channel"
                group = "Other"
                logo = ""
            }
        }

        if (output.length() == 0) {
            throw Exception("No playable M3U entries found")
        }

        return output
    }

    private fun loadXtream(
        server: String,
        user: String,
        pass: String
    ): JSONArray {

        if (user.isBlank() || pass.isBlank()) {
            throw Exception(
                "Xtream username and password are required"
            )
        }

        val base = server.trimEnd('/')

        val username = URLEncoder.encode(user, "UTF-8")
        val password = URLEncoder.encode(pass, "UTF-8")

        val array = JSONArray(
            fetch(
                "$base/player_api.php?" +
                    "username=$username&password=$password" +
                    "&action=get_live_streams"
            )
        )

        val output = JSONArray()

        for (i in 0 until array.length()) {

            val obj = array.optJSONObject(i) ?: continue

            val id = obj.optString("stream_id")

            if (id.isBlank()) continue

            val extension =
                obj.optString("container_extension", "m3u8")
                    .ifBlank { "m3u8" }

            output.put(
                JSONObject()
                    .put("name", obj.optString("name", "Channel"))
                    .put(
                        "url",
                        "$base/live/$username/$password/$id.$extension"
                    )
                    .put(
                        "group",
                        obj.optString("category_name", "Live TV")
                    )
                    .put(
                        "logo",
                        obj.optString("stream_icon", "")
                    )
            )
        }

        if (output.length() == 0) {
            throw Exception("Xtream returned no live channels")
        }

        return output
    }

    override fun onDestroy() {
        io.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
