package eu.kanade.tachiyomi.animeextension.en.animedao

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.animedao.extractors.MixDropExtractor
import eu.kanade.tachiyomi.animeextension.en.animedao.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.en.animedao.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
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

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://animedao.to")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animelist/popular")

    override fun popularAnimeSelector(): String = "div.container > div.row > div.col-md-6"

    override fun popularAnimeNextPageSelector(): String? = null

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

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "div#latest-tab-pane > div.row > div.col-md-6"

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

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

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = if (response.request.url.encodedPath.startsWith("/animelist/")) {
            document.select(searchAnimeSelectorFilter()).map { element ->
                searchAnimeFromElement(element)
            }
        } else {
            document.select(searchAnimeSelector()).map { element ->
                searchAnimeFromElement(element)
            }
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
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

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    private fun searchAnimeSelectorFilter(): String = "div.container div.col-12 > div.row > div.col-md-6"

    override fun searchAnimeNextPageSelector(): String = "ul.pagination > li.page-item:has(i.fa-arrow-right):not(.disabled)"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeDaoFilters.filterList

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
        return if (preferences.getBoolean("preferred_episode_sorting", false)) {
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
                        url.contains("sbhight") || url.contains("sbrity") || url.contains("sbembed.com") || url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                            url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                            url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                            url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                            url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                            url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                            url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com") ||
                            url.contains("vidmovie.xyz") || url.contains("sbspeed.com") || url.contains("streamsss.net") ||
                            url.contains("sblanh.com") || url.contains("tvmshow.com") || url.contains("sbanh.com") ||
                            url.contains("streamovies.xyz") || url.contains("vcdn.space") -> {
                            val newUrl = url.replace("https://www.fembed.com", "https://vanfem.com")
                            FembedExtractor(client).videosFromUrl(newUrl, prefix = prefix, redirect = true)
                        }
                        url.contains("mixdrop") -> {
                            MixDropExtractor(client).videoFromUrl(url, prefix = prefix)
                        }
                        url.contains("https://dood") -> {
                            DoodExtractor(client).videosFromUrl(url, quality = server.name)
                        }
                        url.contains("mp4upload") -> {
                            val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                            Mp4uploadExtractor(client).getVideoFromUrl(url, headers, prefix)
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
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "vstream")!!

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
        return runCatching { DateFormatter.parse(dateStr)?.time }
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("animedao.to")
            entryValues = arrayOf("https://animedao.to")
            setDefaultValue("https://animedao.to")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
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
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Vidstreaming", "Vidstreaming2", "Vidstreaming3", "Mixdrop", "Fembed", "StreamSB", "Streamtape", "Vidstreaming4", "Doodstream")
            entryValues = arrayOf("vstream", "src2", "src", "mixdrop", "vcdn", "streamsb", "streamtape", "vplayer", "doodstream")
            setDefaultValue("vstream")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val episodeSortPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_episode_sorting"
            title = "Attempt episode sorting"
            summary = """AnimeDao displays the episodes in either ascending or descending order,
                | enable to attempt order or disable to set same as website.
            """.trimMargin()
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
        screen.addPreference(episodeSortPref)
    }
}
