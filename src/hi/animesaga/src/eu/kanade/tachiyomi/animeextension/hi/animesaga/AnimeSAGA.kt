package eu.kanade.tachiyomi.animeextension.hi.animesaga

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class AnimeSAGA : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeSAGA"

    override val baseUrl = "https://www.animesaga.in"

    private val videoHost = "cdn.animesaga.in"

    override val lang = "hi"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?sorting=popular".addPage(page), headers)

    override fun popularAnimeSelector(): String = "div#content > div#content.row > div"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href"))
        thumbnail_url = element.selectFirst("picture")?.getImage() ?: ""
        title = element.selectFirst(".title")!!.text()
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active + li:has(a)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/series?sorting=newest&released=1960;2023".addPage(page), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            require(query.length > 2) { "Search query must be longer than three letters" }
            GET("$baseUrl/search/${query.replace("+", "")}".addPage(page), headers)
        } else {
            val filters = AnimeSAGAFilters.getSearchParameters(filters)

            // Validation for ratings
            if (filters.ratingStart.isNotEmpty()) {
                require(filters.ratingEnd.isNotEmpty()) { "Both start and end must either be empty or populated" }
            }
            if (filters.ratingEnd.isNotEmpty()) {
                require(filters.ratingStart.isNotEmpty()) { "Both start and end must either be empty or populated" }
            }
            if (filters.ratingStart.isNotEmpty()) {
                require(filters.ratingStart.toFloatOrNull() != null) { "${filters.ratingStart} is not a float." }
                require(filters.ratingEnd.toFloatOrNull() != null) { "${filters.ratingEnd} is not a float." }
                require(filters.ratingStart.toFloat() in 5.0..10.0) { "Start must be between 5.0 and 10.0" }
                require(filters.ratingEnd.toFloat() in 5.0..10.0) { "End must be between 5.0 and 10.0" }
            }

            // Create url
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment(filters.type)
                if (filters.sorting.isNotEmpty()) addEncodedQueryParameter("sorting", filters.sorting)
                if (filters.genre.isNotEmpty()) addEncodedQueryParameter("genre", filters.genre)
                if (filters.ratingStart.isNotEmpty()) addEncodedQueryParameter("imdb", "${filters.ratingStart.toFloat().stringify()};${filters.ratingEnd.toFloat().stringify()}")
                addEncodedQueryParameter("released", "${filters.yearStart};${filters.yearEnd}")
            }.toString().addPage(page)

            GET(url, headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.pathSegments.first() == "search") {
            val document = response.asJsoup()

            val animeList = document
                .select("div.layout-section > div.row > div")
                .map(::popularAnimeFromElement)

            val hasNextPage = document.selectFirst(popularAnimeSelector()) != null

            AnimesPage(animeList, hasNextPage)
        } else {
            super.searchAnimeParse(response)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeSAGAFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        description = document.select("div.col-md > p.fs-sm").text()
        genre = document.select("div.card-tag > a").joinToString(", ") { it.text() }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val seasonList = document.select("div#seasonAccordion > div.accordion-item")
        // For movies
        if (seasonList.size == 0) {
            val dateStr: String? = document.select("ul.list-inline > li:not(:has(a))")
                .firstOrNull { t ->
                    Regex("^[A-Za-z]{3}\\. \\d{2}, \\d{4}$").matches(t.text().trim())
                }?.text()

            episodeList.add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "Movie"
                    episode_number = 1F
                    date_upload = dateStr?.let { parseDate(it) } ?: 0L
                },
            )
        } else {
            seasonList.forEach { season ->
                val seasonText = season.selectFirst("div.accordion-header")!!.text().trim()

                season.select(episodeListSelector()).forEachIndexed { index, ep ->
                    val epNumber = ep.selectFirst("a.episode")!!.text().trim().substringAfter("pisode ")

                    episodeList.add(
                        SEpisode.create().apply {
                            setUrlWithoutDomain(ep.selectFirst("a[href]")!!.attr("abs:href"))
                            name = "$seasonText Ep. $epNumber ${ep.selectFirst("a.name")?.text()?.trim() ?: ""}"
                            episode_number = epNumber.toFloatOrNull() ?: (index + 1).toFloat()
                        },
                    )
                }
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector() = "div.episodes > div.card-episode"

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val videoList = document.select("div.card-stream > button[data-id]").mapNotNull { stream ->
            val postBody = FormBody.Builder()
                .add("id", stream.attr("data-id"))
                .build()

            val postHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Content-Length", postBody.contentLength().toString())
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Origin", baseUrl)
                .add("Referer", response.request.url.toString())
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val doc = client.newCall(
                POST("$baseUrl/ajax/embed", body = postBody, headers = postHeaders),
            ).execute().asJsoup()

            doc.selectFirst("iframe[data-src]")?.attr("abs:data-src")
        }.parallelMap { iframeUrl ->
            runCatching {
                extractVideosFromIframe(iframeUrl)
            }.getOrElse { emptyList() }
        }.flatten()

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList.sort()
    }

    private fun extractVideosFromIframe(iframeUrl: String): List<Video> {
        return when {
            iframeUrl.toHttpUrl().host.equals(videoHost) -> {
                ChillxExtractor(client, headers).videoFromUrl(iframeUrl, "$baseUrl/")
            }
            STREAMSB_DOMAINS.any { it in iframeUrl } -> {
                StreamSBExtractor(client).videosFromUrl(iframeUrl, headers, prefix = "StreamSB - ")
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================= Utilities ==============================

    private fun String.addPage(page: Int): String {
        return if (page == 1) {
            this
        } else {
            this.toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .toString()
        }
    }

    private fun Element.getImage(): String {
        return this.selectFirst("source[data-srcset][type=image/png]")?.attr("abs:data-srcset")
            ?: this.selectFirst("img[data-src]")?.attr("abs:data-src")
            ?: ""
    }

    private fun Float.stringify(): String {
        return when {
            ceil(this) == floor(this) -> this.toInt().toString()
            else -> "%.1f".format(this).replace(",", ".")
        }
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "chillx"

        private val STREAMSB_DOMAINS = listOf(
            "sbhight", "sbrity", "sbembed.com", "sbembed1.com", "sbplay.org",
            "sbvideo.net", "streamsb.net", "sbplay.one", "cloudemb.com",
            "playersb.com", "tubesb.com", "sbplay1.com", "embedsb.com",
            "watchsb.com", "sbplay2.com", "japopav.tv", "viewsb.com",
            "sbfast", "sbfull.com", "javplaya.com", "ssbstream.net",
            "p1ayerjavseen.com", "sbthe.com", "vidmovie.xyz", "sbspeed.com",
            "streamsss.net", "sblanh.com", "tvmshow.com", "sbanh.com",
            "streamovies.xyz", "sblona.com", "baryonmode.online",
        )
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
            entries = arrayOf("Chillx", "StreamSB")
            entryValues = arrayOf("chillx", "streamsb")
            setDefaultValue(PREF_SERVER_DEFAULT)
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
