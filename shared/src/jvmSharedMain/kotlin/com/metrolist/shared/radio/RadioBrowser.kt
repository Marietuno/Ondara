package com.metrolist.shared.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class RadioStation(
    @SerialName("stationuuid") val id: String,
    val name: String,
    @SerialName("url_resolved") val urlResolved: String = "",
    val url: String = "",
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
) {
    val streamUrl: String get() = urlResolved.ifBlank { url }
    val isHls: Boolean get() = hls == 1 || streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    val displayName: String get() = name.trim()
    val subtitle: String
        get() =
            listOfNotNull(
                country.takeIf { it.isNotBlank() },
                tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
    val qualityLabel: String
        get() = listOfNotNull(codec.takeIf { it.isNotBlank() && it != "UNKNOWN" }, bitrate.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" ")
}

@Serializable
data class RadioCountry(
    val name: String,
    @SerialName("iso_3166_1") val code: String = "",
    @SerialName("stationcount") val stationCount: Int = 0,
)

@Serializable
data class RadioTag(
    val name: String,
    @SerialName("stationcount") val stationCount: Int = 0,
)

@Serializable
private data class RadioServer(
    val name: String,
)

data class RadioQuery(
    val name: String = "",
    val countryCode: String = "",
    val tag: String = "",
    val batterySaver: Boolean = false,
)

// Client for the community radio-browser.info directory (tens of thousands of stations worldwide).
object RadioBrowser {
    const val PAGE_SIZE = 60
    private const val USER_AGENT = "MetrolistRadio/1.0"
    private const val BATTERY_SAVER_MAX_BITRATE = 96
    private val fallbackServers = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info")
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    @Volatile
    private var servers: List<String>? = null

    suspend fun search(
        query: RadioQuery,
        offset: Int,
    ): List<RadioStation> {
        val stations =
            get<List<RadioStation>>("json/stations/search") {
                addQueryParameter("hidebroken", "true")
                addQueryParameter("order", if (query.name.isBlank()) "clickcount" else "votes")
                addQueryParameter("reverse", "true")
                addQueryParameter("limit", PAGE_SIZE.toString())
                addQueryParameter("offset", offset.toString())
                if (query.name.isNotBlank()) addQueryParameter("name", query.name.trim())
                if (query.countryCode.isNotBlank()) addQueryParameter("countrycode", query.countryCode)
                if (query.tag.isNotBlank()) addQueryParameter("tag", query.tag)
                if (query.batterySaver) addQueryParameter("bitrateMax", BATTERY_SAVER_MAX_BITRATE.toString())
            }.filter { it.streamUrl.isNotBlank() }
        return if (query.batterySaver) {
            // HE-AAC/AAC decodes cheaply and is usually eligible for hardware offload.
            stations.sortedWith(compareByDescending<RadioStation> { it.codec.startsWith("AAC", ignoreCase = true) })
        } else {
            stations
        }
    }

    suspend fun countries(): List<RadioCountry> =
        get<List<RadioCountry>>("json/countries") {
            addQueryParameter("order", "name")
            addQueryParameter("hidebroken", "true")
        }.filter { it.code.length == 2 && it.stationCount > 0 }

    suspend fun topTags(): List<RadioTag> =
        get<List<RadioTag>>("json/tags") {
            addQueryParameter("order", "stationcount")
            addQueryParameter("reverse", "true")
            addQueryParameter("hidebroken", "true")
            addQueryParameter("limit", "40")
        }.filter { it.name.isNotBlank() && it.name.length <= 20 }

    suspend fun station(id: String): RadioStation? =
        get<List<RadioStation>>("json/stations/byuuid") { addQueryParameter("uuids", id) }
            .firstOrNull { it.streamUrl.isNotBlank() }

    // Registers a listen with the directory, which keeps its popularity ranking useful.
    suspend fun reportClick(stationId: String) {
        runCatching { get<Unit>("json/url/$stationId", parse = false) {} }
    }

    private suspend inline fun <reified T> get(
        path: String,
        parse: Boolean = true,
        crossinline params: HttpUrl.Builder.() -> Unit,
    ): T =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (host in serverList()) {
                try {
                    val url =
                        "https://$host/$path"
                            .toHttpUrl()
                            .newBuilder()
                            .apply(params)
                            .build()
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header("User-Agent", USER_AGENT)
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body.string()
                        @Suppress("UNCHECKED_CAST")
                        return@withContext if (parse) json.decodeFromString<T>(body) else Unit as T
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("No radio directory server available")
        }

    private fun serverList(): List<String> {
        servers?.let { return it }
        val discovered =
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url("https://all.api.radio-browser.info/json/servers")
                        .header("User-Agent", USER_AGENT)
                        .build()
                client.newCall(request).execute().use { response ->
                    json
                        .decodeFromString<List<RadioServer>>(response.body.string())
                        .map { it.name }
                        .distinct()
                        .shuffled()
                }
            }.getOrNull().orEmpty()
        return (discovered + fallbackServers).distinct().also { servers = it }
    }
}
