package com.novalive.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class NovaChannel(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val epgId: String = ""
)

data class NovaProgramme(
    val channelId: String,
    val title: String,
    val description: String,
    val start: Long,
    val end: Long
)

class NovaRepository(context: Context) {

    private val prefs = context.getSharedPreferences(
        "nova_repository",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val MAX_RECENT = 30
    }

    // -------------------------------
    // FAVORITES
    // -------------------------------

    fun getFavorites(): JSONArray {
        return try {
            JSONArray(prefs.getString("favorites", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun addFavorite(channel: NovaChannel) {

        val favorites = getFavorites()

        for (i in 0 until favorites.length()) {
            if (
                favorites.optJSONObject(i)
                    ?.optString("url") == channel.url
            ) return
        }

        favorites.put(channelToJson(channel))

        prefs.edit()
            .putString("favorites", favorites.toString())
            .apply()
    }

    fun removeFavorite(url: String) {

        val old = getFavorites()
        val updated = JSONArray()

        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue

            if (item.optString("url") != url) {
                updated.put(item)
            }
        }

        prefs.edit()
            .putString("favorites", updated.toString())
            .apply()
    }

    fun isFavorite(url: String): Boolean {

        val favorites = getFavorites()

        for (i in 0 until favorites.length()) {
            if (
                favorites.optJSONObject(i)
                    ?.optString("url") == url
            ) return true
        }

        return false
    }

    // -------------------------------
    // RECENT CHANNELS
    // -------------------------------

    fun getRecent(): JSONArray {
        return try {
            JSONArray(prefs.getString("recent", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun addRecent(channel: NovaChannel) {

        val old = getRecent()
        val updated = JSONArray()

        updated.put(channelToJson(channel))

        for (i in 0 until old.length()) {

            val item = old.optJSONObject(i) ?: continue

            if (
                item.optString("url") != channel.url &&
                updated.length() < MAX_RECENT
            ) {
                updated.put(item)
            }
        }

        prefs.edit()
            .putString("recent", updated.toString())
            .apply()
    }

    fun clearRecent() {
        prefs.edit()
            .putString("recent", "[]")
            .apply()
    }

    // -------------------------------
    // CHANNEL JSON
    // -------------------------------

    private fun channelToJson(
        channel: NovaChannel
    ): JSONObject {

        return JSONObject()
            .put("name", channel.name)
            .put("url", channel.url)
            .put("logo", channel.logo)
            .put("group", channel.group)
            .put("epgId", channel.epgId)
    }

    // -------------------------------
    // DUPLICATE FILTER
    // -------------------------------

    fun removeDuplicates(
        channels: List<NovaChannel>
    ): List<NovaChannel> {

        val unique = LinkedHashMap<String, NovaChannel>()

        channels.forEach { channel ->

            if (channel.url.isNotBlank()) {
                unique.putIfAbsent(
                    channel.url.trim(),
                    channel
                )
            }
        }

        return unique.values.toList()
    }

    // -------------------------------
    // XMLTV EPG PARSER
    // -------------------------------

    fun parseXmltv(xml: String): List<NovaProgramme> {

        val programmes = mutableListOf<NovaProgramme>()

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false

        val parser = factory.newPullParser()

        parser.setInput(StringReader(xml))

        var event = parser.eventType

        var channelId = ""
        var title = ""
        var description = ""
        var start = 0L
        var end = 0L

        while (event != XmlPullParser.END_DOCUMENT) {

            when (event) {

                XmlPullParser.START_TAG -> {

                    when (parser.name) {

                        "programme" -> {

                            channelId = parser.getAttributeValue(
                                null,
                                "channel"
                            ) ?: ""

                            start = parseXmltvTime(
                                parser.getAttributeValue(
                                    null,
                                    "start"
                                ) ?: ""
                            )

                            end = parseXmltvTime(
                                parser.getAttributeValue(
                                    null,
                                    "stop"
                                ) ?: ""
                            )

                            title = ""
                            description = ""
                        }

                        "title" -> {
                            title = parser.nextText().trim()
                        }

                        "desc" -> {
                            description = parser.nextText().trim()
                        }
                    }
                }

                XmlPullParser.END_TAG -> {

                    if (parser.name == "programme") {

                        if (
                            channelId.isNotBlank() &&
                            title.isNotBlank() &&
                            end > start
                        ) {

                            programmes.add(
                                NovaProgramme(
                                    channelId,
                                    title,
                                    description,
                                    start,
                                    end
                                )
                            )
                        }
                    }
                }
            }

            event = parser.next()
        }

        return programmes.sortedBy { it.start }
    }

    // -------------------------------
    // XMLTV TIMEZONE CONVERSION
    // -------------------------------

    private fun parseXmltvTime(
        value: String
    ): Long {

        val formats = listOf(
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm"
        )

        for (format in formats) {

            try {

                val formatter = SimpleDateFormat(
                    format,
                    Locale.US
                )

                formatter.isLenient = false

                if (format.contains("Z")) {
                    formatter.timeZone = TimeZone.getTimeZone("UTC")
                } else {
                    formatter.timeZone = TimeZone.getDefault()
                }

                return formatter.parse(value.trim())?.time ?: 0L

            } catch (_: Exception) {
                // Try next format
            }
        }

        return 0L
    }

    // -------------------------------
    // NOW PLAYING
    // -------------------------------

    fun getNowPlaying(
        channelId: String,
        programmes: List<NovaProgramme>,
        currentTime: Long = System.currentTimeMillis()
    ): NovaProgramme? {

        return programmes.firstOrNull {
            it.channelId == channelId &&
            currentTime >= it.start &&
            currentTime < it.end
        }
    }

    // -------------------------------
    // NEXT PROGRAMME
    // -------------------------------

    fun getNextProgramme(
        channelId: String,
        programmes: List<NovaProgramme>,
        currentTime: Long = System.currentTimeMillis()
    ): NovaProgramme? {

        return programmes
            .filter {
                it.channelId == channelId &&
                it.start > currentTime
            }
            .minByOrNull { it.start }
    }
}
