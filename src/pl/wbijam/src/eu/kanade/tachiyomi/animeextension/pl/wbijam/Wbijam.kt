package eu.kanade.tachiyomi.animeextension.pl.wbijam

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors.CdaPlExtractor
import eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors.VkExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
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

class Wbijam : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Wbijam"

    override val baseUrl = "https://wbijam.pl"

    override val lang = "pl"

    override val supportsLatest = true

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

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeSelector(): String = "button:contains(Lista anime) + div.dropdown-content > a"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.selectFirst("a")!!.attr("href")
            thumbnail_url = ""
            title = element.selectFirst("a")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "button:contains(Wychodzące) + div.dropdown-content > a"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    // button:contains(Lista anime) + div.dropdown-content > a:contains(chainsaw)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return client.newCall(searchAnimeRequest(page, query, filters))
            .awaitSuccess()
            .let { response ->
                searchAnimeParse(response, query)
            }
    }

    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(searchAnimeSelector(query)).map { element ->
            searchAnimeFromElement(element)
        }

        return AnimesPage(animes, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)

    private fun searchAnimeSelector(query: String): String = "button:contains(Lista anime) + div.dropdown-content > a:contains($query)"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime) = anime

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        var counter = 1

        document.select("button:not(:contains(Wychodzące)):not(:contains(Warsztat)):not(:contains(Lista anime)) + div.dropdown-content > a").forEach seasons@{ season ->
            val seasonDoc = client.newCall(
                GET(response.request.url.toString() + "/${season.attr("href")}", headers = headers),
            ).execute().asJsoup()
            seasonDoc.select("table.lista > tbody > tr").reversed().forEach { ep ->
                val episode = SEpisode.create()

                // Skip over openings and engings
                if (preferences.getBoolean("preferred_opening", true)) {
                    if (season.text().contains("Openingi", true) || season.text().contains("Endingi", true)) {
                        return@seasons
                    }
                }

                if (ep.selectFirst("td > a") == null) {
                    val (name, scanlator) = if (preferences.getBoolean("preferred_season_view", true)) {
                        Pair(
                            ep.selectFirst("td")!!.text(),
                            season.text(),
                        )
                    } else {
                        Pair(
                            "[${season.text()}] ${ep.selectFirst("td")!!.text()}",
                            null,
                        )
                    }

                    val notUploaded = ep.selectFirst("td:contains(??.??.????)") != null

                    episode.name = name
                    episode.scanlator = if (notUploaded) {
                        "(Jeszcze nie przesłane) $scanlator"
                    } else {
                        scanlator
                    }
                    episode.episode_number = counter.toFloat()
                    episode.date_upload = ep.selectFirst("td:matches(\\d+\\.\\d+\\.\\d)")?.let { parseDate(it.text()) } ?: 0L
                    val urls = ep.select("td > span[class*=link]").map {
                        "https://${response.request.url.host}/${it.className().substringBefore("_link")}-${it.attr("rel")}.html"
                    }
                    episode.url = EpisodeType(
                        "single",
                        urls,
                    ).toJsonString()
                } else {
                    val (name, scanlator) = if (preferences.getBoolean("preferred_season_view", true)) {
                        Pair(
                            ep.selectFirst("td")!!.text(),
                            "${season.text()} • ${ep.selectFirst("td:matches([a-zA-Z]+):not(:has(a))")?.text()}",
                        )
                    } else {
                        Pair(
                            "[${season.text()}] ${ep.selectFirst("td")!!.text()}",
                            ep.selectFirst("td:matches([a-zA-Z]+):not(:has(a))")?.text(),
                        )
                    }

                    val notUploaded = ep.selectFirst("td:contains(??.??.????)") != null

                    episode.name = name
                    episode.episode_number = counter.toFloat()
                    episode.date_upload = ep.selectFirst("td:matches(\\d+\\.\\d+\\.\\d)")?.let { parseDate(it.text()) } ?: 0L
                    episode.scanlator = if (notUploaded) {
                        "(Jeszcze nie przesłane) $scanlator"
                    } else {
                        scanlator
                    }

                    episode.url = EpisodeType(
                        "multi",
                        listOf("https://${response.request.url.host}/${ep.selectFirst("td a")!!.attr("href")}"),
                    ).toJsonString()
                }

                episodeList.add(episode)
                counter++
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

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
                serverUrl.contains("sibnet.ru") -> {
                    SibnetExtractor(client).videosFromUrl(serverUrl)
                }
                serverUrl.contains("vk.com") -> {
                    VkExtractor(client).getVideosFromUrl(serverUrl, headers)
                }
                serverUrl.contains("dailymotion") -> {
                    DailymotionExtractor(client, headers).videosFromUrl(serverUrl)
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
