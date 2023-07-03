package eu.kanade.tachiyomi.animeextension.en.animedao

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.animedao.extractors.MixDropExtractor
import eu.kanade.tachiyomi.animeextension.en.animedao.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeDao : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeDao"

    override val baseUrl = "https://animedao.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animelist/popular")

    override fun popularAnimeSelector(): String = "div.container > div.row > div.col-md-6"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val thumbnailUrl = element.selectFirst("img")!!.attr("data-src")

        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            thumbnail_url = if (thumbnailUrl.contains(baseUrl.toHttpUrl().host)) {
                thumbnailUrl
            } else {
                baseUrl + thumbnailUrl
            }
            title = element.selectFirst("span.animename")!!.text()
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "div#latest-tab-pane > div.row > div.col-md-6"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val thumbnailUrl = element.selectFirst("img")!!.attr("data-src")

        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a.animeparent")!!.attr("href"))
            thumbnail_url = if (thumbnailUrl.contains(baseUrl.toHttpUrl().host)) {
                thumbnailUrl
            } else {
                baseUrl + thumbnailUrl
            }
            title = element.selectFirst("span.animename")!!.text()
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AnimeDaoFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: AnimeDaoFilters.FilterSearchParams): Request {
        return if (query.isNotBlank()) {
            val cleanQuery = query.replace(" ", "+")
            GET("$baseUrl/search/?search=$cleanQuery", headers = headers)
        } else {
            var url = "$baseUrl/animelist/".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("status[]=", filters.status)
                .addQueryParameter("order[]=", filters.order)
                .build().toString()

            if (filters.genre.isNotBlank()) url += "&${filters.genre}"
            if (filters.rating.isNotBlank()) url += "&${filters.rating}"
            if (filters.letter.isNotBlank()) url += "&${filters.letter}"
            if (filters.year.isNotBlank()) url += "&${filters.year}"
            if (filters.score.isNotBlank()) url += "&${filters.score}"
            url += "&page=$page"

            GET(url, headers = headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val selector = if (response.request.url.encodedPath.startsWith("/animelist/")) {
            searchAnimeSelectorFilter()
        } else {
            searchAnimeSelector()
        }

        val animes = document.select(selector).map { element ->
            searchAnimeFromElement(element)
        }

        val hasNextPage = searchAnimeNextPageSelector().let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun searchAnimeSelectorFilter(): String = "div.container div.col-12 > div.row > div.col-md-6"

    override fun searchAnimeNextPageSelector(): String = "ul.pagination > li.page-item:has(i.fa-arrow-right):not(.disabled)"

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeDaoFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val thumbnailUrl = document.selectFirst("div.card-body img")!!.attr("data-src")
        val moreInfo = document.select("div.card-body table > tbody > tr").joinToString("\n") { it.text() }

        return SAnime.create().apply {
            title = document.selectFirst("div.card-body h2")!!.text()
            thumbnail_url = if (thumbnailUrl.contains(baseUrl.toHttpUrl().host)) {
                thumbnailUrl
            } else {
                baseUrl + thumbnailUrl
            }
            status = document.selectFirst("div.card-body table > tbody > tr:has(>td:contains(Status)) td:not(:contains(Status))")?.let {
                parseStatus(it.text())
            } ?: SAnime.UNKNOWN
            description = (document.selectFirst("div.card-body div:has(>b:contains(Description))")?.ownText() ?: "") + "\n\n$moreInfo"
            genre = document.select("div.card-body table > tbody > tr:has(>td:contains(Genres)) td > a").joinToString(", ") { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        return if (preferences.getBoolean(PREF_EPISODE_SORT_KEY, PREF_EPISODE_SORT_DEFAULT)) {
            super.episodeListParse(response).sortedWith(
                compareBy(
                    { it.episode_number },
                    { it.name },
                ),
            ).reversed()
        } else {
            super.episodeListParse(response)
        }
    }

    override fun episodeListSelector(): String = "div#episodes-tab-pane > div.row > div > div.card"

    override fun episodeFromElement(element: Element): SEpisode {
        val episodeName = element.selectFirst("span.animename")!!.text()
        val episodeTitle = element.selectFirst("div.animetitle")?.text() ?: ""

        return SEpisode.create().apply {
            name = "$episodeName $episodeTitle"
            episode_number = if (episodeName.contains("Episode ", true)) {
                episodeName.substringAfter("Episode ").substringBefore(" ").toFloatOrNull() ?: 0F
            } else { 0F }
            if (element.selectFirst("span.filler") != null && preferences.getBoolean(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT)) {
                scanlator = "Filler Episode"
            }
            date_upload = element.selectFirst("span.date")?.let { parseDate(it.text()) } ?: 0L
            setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href"))
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val serverList = mutableListOf<Server>()
        val script = document.selectFirst("script:containsData(videowrapper)")!!.data()
        val frameRegex = """function (\w+).*?iframe src=\"(.*?)\"""".toRegex()

        frameRegex.findAll(script).forEach {
            val redirected = client.newCall(GET(baseUrl + it.groupValues[2])).execute().request.url.toString()
            serverList.add(
                Server(
                    redirected,
                    it.groupValues[1],
                ),
            )
        }

        // Get videos
        videoList.addAll(
            serverList.parallelMap { server ->
                runCatching {
                    val prefix = "${server.name} - "
                    val url = server.url

                    when {
                        url.contains("streamtape") -> {
                            val video = StreamTapeExtractor(client).videoFromUrl(url, server.name)
                            if (video == null) {
                                emptyList()
                            } else {
                                listOf(video)
                            }
                        }
                        url.contains("streamsb") -> {
                            StreamSBExtractor(client).videosFromUrl(url, headers = headers, prefix = prefix)
                        }
                        url.contains("vidstreaming") -> {
                            VidstreamingExtractor(client, json).videosFromUrl(url, prefix = prefix)
                        }
                        url.contains("mixdrop") -> {
                            MixDropExtractor(client).videoFromUrl(url, prefix = prefix)
                        }
                        url.contains("https://dood") -> {
                            DoodExtractor(client).videosFromUrl(url, quality = server.name)
                        }
                        url.contains("mp4upload") -> {
                            Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix)
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    data class Server(
        val url: String,
        val name: String,
    )

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "vstream"

        private const val PREF_EPISODE_SORT_KEY = "preferred_episode_sorting"
        private const val PREF_EPISODE_SORT_DEFAULT = true

        private const val PREF_MARK_FILLERS_KEY = "mark_fillers"
        private const val PREF_MARK_FILLERS_DEFAULT = true
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = arrayOf("Vidstreaming", "Vidstreaming2", "Vidstreaming3", "Mixdrop", "StreamSB", "Streamtape", "Vidstreaming4", "Doodstream")
            entryValues = arrayOf("vstream", "src2", "src", "mixdrop", "streamsb", "streamtape", "vplayer", "doodstream")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_EPISODE_SORT_KEY
            title = "Attempt episode sorting"
            summary = """AnimeDao displays the episodes in either ascending or descending order,
                | enable to attempt order or disable to set same as website.
            """.trimMargin()
            setDefaultValue(PREF_EPISODE_SORT_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(PREF_MARK_FILLERS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
