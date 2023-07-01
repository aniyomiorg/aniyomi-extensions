package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

open class AnimeWorldIndia(
    final override val lang: String,
    private val language: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeWorld India"

    override val baseUrl = "https://anime-world.in"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // =============================== Search ===============================

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li:has(span[aria-current=\"page\"]) + li"

    override fun searchAnimeSelector(): String = "div.col-span-1"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        var thumbnail = element.selectFirst("img")!!.attr("src")
        if (!thumbnail.contains("https")) {
            thumbnail = "$baseUrl/$thumbnail"
        }
        anime.thumbnail_url = thumbnail
        anime.title = element.select("div.font-medium.line-clamp-2.mb-3").text()
        return anime
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = AnimeWorldIndiaFilters().getSearchParams(filters)
        return GET("$baseUrl/advanced-search/page/$page/?s_keyword=$query&s_lang=$lang$searchParams")
    }

    override fun getFilterList() = AnimeWorldIndiaFilters().filters

    // ============================== Popular ===============================

    override fun popularAnimeNextPageSelector(): String = searchAnimeNextPageSelector()

    override fun popularAnimeSelector(): String = searchAnimeSelector()

    override fun popularAnimeFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/advanced-search/page/$page/?s_keyword=&s_type=all&s_status=all&s_lang=$lang&s_sub_type=all&s_year=all&s_orderby=viewed&s_genre=")

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector(): String = searchAnimeNextPageSelector()

    override fun latestUpdatesSelector(): String = searchAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/advanced-search/page/$page/?s_keyword=&s_type=all&s_status=all&s_lang=$lang&s_sub_type=all&s_year=all&s_orderby=update&s_genre=")

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create().apply {
            genre = document.select("span.leading-6 a[class~=border-opacity-30]").joinToString(", ") { it.text() }
            description = document.select("span.block.w-full.max-h-24.overflow-scroll.my-3.overflow-x-hidden.text-xs.text-gray-200").text()
            author = document.select("span.leading-6 a[href*=\"producer\"]:first-child").text()
            artist = document.select("span.leading-6 a[href*=\"studio\"]:first-child").text()
            status = parseStatus(document)
        }
        return anime
    }

    private val selector = "ul li:has(div.w-1.h-1.bg-gray-500.rounded-full) + li"

    private fun parseStatus(document: Document): Int {
        return when (document.select("$selector a:not(:contains(Ep))").text()) {
            "Movie" -> SAnime.COMPLETED
            else -> {
                val episodeString = document.select("$selector a:not(:contains(TV))").text().drop(3).split("/")
                if (episodeString[0].trim().compareTo(episodeString[1].trim()) == 0) {
                    SAnime.COMPLETED
                } else SAnime.ONGOING
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seasonsJson = json.decodeFromString<JsonArray>(
            document.html()
                .substringAfter("var season_list = ")
                .substringBefore("var season_label =")
                .trim().dropLast(1),
        )
        var seasonNumber = 1
        var episodeNumber = 1f
        val isAnimeCompleted = parseStatus(document) == SAnime.COMPLETED

        seasonsJson.forEach { season ->
            val seasonName = if (seasonsJson.size == 1) "" else "Season $seasonNumber"
            val episodesJson = season.jsonObject["episodes"]!!.jsonObject[language]?.jsonArray?.reversed() ?: return@forEach

            episodesJson.forEach {
                val episodeTitle = it.jsonObject["metadata"]!!
                    .jsonObject["title"]!!
                    .toString()
                    .drop(1).dropLast(1)

                val epNum = it.jsonObject["metadata"]!!
                    .jsonObject["number"]!!
                    .toString().drop(1)
                    .dropLast(1).toInt()

                val episodeName = if (isAnimeCompleted && seasonsJson.size == 1 && episodesJson.size == 1) {
                    "Movie"
                } else if (episodeTitle.isNotEmpty()) {
                    "$seasonName Ep $epNum - $episodeTitle"
                } else {
                    "$seasonName - Episode $epNum"
                }

                val episode = SEpisode.create().apply {
                    name = episodeName
                    episode_number = episodeNumber
                    setUrlWithoutDomain(url = "$baseUrl/wp-json/kiranime/v1/episode?id=${it.jsonObject["id"]}")
                    date_upload = it.jsonObject["metadata"]
                        ?.jsonObject?.get("released")?.toString()
                        ?.drop(1)?.dropLast(1)
                        ?.toLong()?.times(1000) ?: 0L
                }

                episodeNumber += 1
                episodeList.add(episode)
            }
            seasonNumber += 1
        }
        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoListSelector() = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val playersIndex = document.html().lastIndexOf("players")
        val documentTrimmed = document.html().substring(playersIndex)
            .substringAfter("players\":")
            .substringBefore(",\"noplayer\":")
            .trim()

        val playerJson = json.decodeFromString<JsonArray>(documentTrimmed)

        val filterStreams = playerJson.filter {
            it.jsonObject["type"].toString()
                .drop(1).dropLast(1)
                .compareTo("stream") == 0
        }

        val filterLanguages = filterStreams.filter {
            it.jsonObject["language"].toString()
                .drop(1).dropLast(1)
                .compareTo(language) == 0
        }

        // Abyss - Server does not work

        val abyssStreams = filterLanguages.filter {
            it.jsonObject["server"].toString()
                .drop(1).dropLast(1)
                .compareTo("Abyss") == 0
        }

        // MyStream

        filterLanguages.filter {
            it.jsonObject["server"].toString()
                .drop(1).dropLast(1)
                .compareTo("Mystream") == 0
        }.forEach {
            val url = it.jsonObject["url"].toString().drop(1).dropLast(1)
            val videos = MyStreamExtractor().videosFromUrl(url, headers)
            videoList.addAll(videos)
        }

        // StreamSB

        filterLanguages.filter {
            it.jsonObject["server"].toString()
                .drop(1).dropLast(1)
                .compareTo("Streamsb") == 0
        }.forEach {
            val url = "https://cloudemb.com/e/${it.jsonObject["url"].toString()
                .drop(1).dropLast(1)
                .substringAfter("id=")}.html"
            val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
            videoList.addAll(videos)
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "MyStream")!!
        return sortedWith(
            compareBy(
                { it.quality.lowercase().contains(quality.lowercase()) },
                { it.quality.lowercase().contains(server.lowercase()) },
            ),
        ).reversed()
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("MyStream", "StreamSB")
            entryValues = arrayOf("MyStream", "StreamSB")
            setDefaultValue("MyStream")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
