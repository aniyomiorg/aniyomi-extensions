package eu.kanade.tachiyomi.animeextension.pl.ogladajanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.pl.ogladajanime.extractors.CdaPlExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class OgladajAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "OgladajAnime"

    override val baseUrl = "https://ogladajanime.pl"

    // private val apiUrl = " https://api.docchi.pl/v1"

    override val lang = "pl"

    override val supportsLatest = true

    private var currentReferer = ""

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/search/page/$page")
    }

    override fun popularAnimeSelector(): String = "div#anime_main div.card.bg-white"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.selectFirst("a")!!.attr("href")
            thumbnail_url = element.selectFirst("img")?.attr("data-srcset")
            title = element.selectFirst("h5.card-title > a")!!.text()
        }
    }
    override fun popularAnimeNextPageSelector(): String = "section:has(div#anime_main)" // To nie działa zostało to tylko dlatego by ładowało ale na końcu niestety wyskakuje ze "nie znaleziono" i tak zostaje zamiast zniknać możliwe ze zle fetchuje.

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search/new/$page")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

//    override suspend fun getSearchAnime(
//        page: Int,
//        query: String,
//        filters: AnimeFilterList,
//    ): AnimesPage {
//        return client.newCall(searchAnimeRequest(page, query, filters))
//            .awaitSuccess()
//            .let { response ->
//                searchAnimeParse(response, query)
//            }
//    }
//
//    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
//        val document = response.asJsoup()
//
//        val animes = document.select(searchAnimeSelector()).map { element ->
//            searchAnimeFromElement(element)
//        }
//
//        return AnimesPage(animes, false)
//    }
//  private fun searchAnimeSelector(query: String): String = "null"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/name/$query")

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String? = null

    // prosta bez filtrów jak na razie :) są dziury ale to kiedyś sie naprawi hihi. Wystarczy dobrze wyszukać animca i powinno wyszukać.

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            // status = document.selectFirst("div.toggle-content > ul > li:contains(Status)")?.let { parseStatus(it.text()) } ?: SAnime.UNKNOWN
            description = document.selectFirst("p#animeDesc")?.text()
            genre = document.select("div.row > div.col-12 > span.badge[href^=/search/name/]").joinToString(", ") {
                it.text()
            }
            author = document.select("div.row > div.col-12:contains(Studio:) > span.badge[href=#]").joinToString(", ") {
                it.text()
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector(): String = "ul#ep_list > li"

    override fun episodeFromElement(element: Element): SEpisode  {
        val episode = SEpisode.create()
        episode.name = "a"
        episode.episode_number =
        episode.url = "episode"
        return episode
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parsed = json.decodeFromString<EpisodeType>(episode.url)
        val serverList = mutableListOf<String>()

        parsed.url.forEach {
            val document = client.newCall(GET(it)).execute().asJsoup()

            if (parsed.type == "single") {
                serverList.add(
                    document.selectFirst("iframe")?.attr("src")
                        ?: document.selectFirst("span.odtwarzaj_vk")?.let { t -> "https://vk.com/video${t.attr("rel")}_${t.attr("id")}" } ?: "",
                )
            } else if (parsed.type == "multi") {
                document.select("table.lista > tbody > tr.lista_hover").forEach { server ->
                    val urlSpan = server.selectFirst("span[class*=link]")!!
                    val serverDoc = client.newCall(
                        GET("https://${it.toHttpUrl().host}/${urlSpan.className().substringBefore("_link")}-${urlSpan.attr("rel")}.html"),
                    ).execute().asJsoup()
                    serverList.add(
                        serverDoc.selectFirst("iframe")?.attr("src")
                            ?: serverDoc.selectFirst("span.odtwarzaj_vk")?.let { t -> "https://vk.com/video${t.attr("rel")}_${t.attr("id")}" } ?: "",
                    )
                }
            }
        }

        val videoList = serverList.parallelCatchingFlatMap { serverUrl ->
            when {
                serverUrl.contains("mp4upload") -> {
                    Mp4uploadExtractor(client).videosFromUrl(serverUrl, headers)
                }
                serverUrl.contains("cda.pl") -> {
                    CdaPlExtractor(client).getVideosFromUrl(serverUrl, headers)
                }
                else -> null
            }.orEmpty()
        }

        return videoList.sort()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun EpisodeType.toJsonString(): String {
        return json.encodeToString(this)
    }

    @Serializable
    data class EpisodeType(
        val type: String,
        val url: List<String>,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "vstream")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferowana jakość"
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
        }
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferowany serwer"
            entries = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            entryValues = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            setDefaultValue("cda.pl")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val seasonViewPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_season_view"
            title = "Przenieś nazwę sezonu do skanera"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        val openEndPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_opening"
            title = "Usuń zakończenia i otwory"
            summary = "Usuń zakończenia i otwarcia z listy odcinków"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
        screen.addPreference(seasonViewPref)
        screen.addPreference(openEndPref)
    }
}
