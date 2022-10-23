package eu.kanade.tachiyomi.animeextension.pl.desuonline

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class DesuOnline : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "desu-online"

    override val baseUrl = "https://desu-online.pl"

    override val lang = "pl"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime/?page=$page&order=popular")

    override fun popularAnimeSelector() = "div.listupd div.bsx > a"

    override fun popularAnimeNextPageSelector() = "div.pagination > a.next, div.hpage > a.r"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val animeTitle = element.select("div.tt > h2").text().trim()
        val img = element.select("div.limit > img")
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = animeTitle
            thumbnail_url = img.attr("data-src")
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val img = document.select("div.thumb > img").ifEmpty { null }
        val studio = document.select("div.info-content > div.spe > span:contains(Studio:)").ifEmpty { null }
        val statusSpan = document.select("div.info-content > div.spe > span:contains(Status:)").ifEmpty { null }
        val desc = document.select("div[itemprop=description] > p:last-child").ifEmpty { null }
        val director = document.select("div.info-content > div.spe > span:contains(Reżyser:)").ifEmpty { null }
        val genres = document.select("div.genxed > a")
        return SAnime.create().apply {
            title = document.select("h1.entry-title").text()
            thumbnail_url = img?.attr("data-src")
            author = studio?.text()?.substringAfter("Studio: ")
            status = parseStatus(statusSpan?.text()?.substringAfter("Status: "))
            description = desc?.text()?.trim()
            artist = director?.text()?.substringAfter("Reżyser: ")
            genre = genres.joinToString { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "div.eplister > ul > li"

    override fun episodeFromElement(element: Element): SEpisode {
        val a = element.select("a")
        val epNum = a.select("div.epl-num").text()
        val epTitle = a.select("div.epl-title").text()
        val date = a.select("div.epl-date").text()
        return SEpisode.create().apply {
            setUrlWithoutDomain(a.attr("href"))
            name = "Odcinek $epNum: $epTitle"
            date_upload = parseDate(date)
            episode_number = epNum.substringBefore(" ").toFloatOrNull() ?: 0F
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = response.asJsoup()
        document.select("select.mirror > option").filter {
            it.text().contains("CDA")
        }.map {
            val mirror = it.text().trim()

            val iframe = String(Base64.decode(it.attr("value"), Base64.DEFAULT))
            val src = iframe.substringAfter("src=\"").substringBefore("\"")

            val videoId = src.toHttpUrl().encodedPathSegments.last()

            val playerDataHtml = client.newCall(GET(src)).execute()
                .asJsoup().select("div[id=mediaplayer$videoId]")
                .attr("player_data")
            val playerData = json.decodeFromString<JsonObject>(playerDataHtml)

            val timeStamp = playerData["api"]!!.jsonObject["ts"]!!.jsonPrimitive.content
                .substringBefore("_")
            val videoData = playerData["video"]!!.jsonObject
            val hash = videoData["hash2"]!!.jsonPrimitive.content

            val qualities = videoData["qualities"]!!.jsonObject

            qualities.keys.reversed().map { quality ->
                val qualityId = qualities[quality]!!.jsonPrimitive.content
                val body = cdaBody(videoId, qualityId, timeStamp, hash)
                val videoResponse = json.decodeFromString<JsonObject>(
                    client.newCall(POST("https://www.cda.pl/", body = body))
                        .execute().body!!.string()
                )
                val videoUrl = videoResponse["result"]!!.jsonObject["resp"]!!.jsonPrimitive.content
                videoList.add(Video(videoUrl, "$mirror: $quality", videoUrl))
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("not used")
    override fun videoListSelector(): String = throw Exception("not used")
    override fun videoUrlParse(document: Document): String = throw Exception("not used")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/anime/?page=$page&s=$query")

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime/?page=$page&order=latest")

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Preferences ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Zakończony" -> SAnime.COMPLETED
            "Emitowany" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun cdaBody(
        videoId: String,
        qualityId: String,
        timeStamp: String,
        hash: String,
    ): RequestBody {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"videoGetLink\",\"params\":[\"$videoId\",\"$qualityId\",$timeStamp,\"$hash\",{}],\"id\":4}"
            .toRequestBody("application/json".toMediaType())
    }
}

private val DATE_FORMATTER by lazy {
    SimpleDateFormat("d MMM, yyyy", Locale("pl"))
}
