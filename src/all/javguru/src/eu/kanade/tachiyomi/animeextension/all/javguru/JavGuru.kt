package eu.kanade.tachiyomi.animeextension.all.javguru

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.javguru.extractors.EmTurboExtractor
import eu.kanade.tachiyomi.animeextension.all.javguru.extractors.MaxStreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher.fetchHDCovers
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.min

class JavGuru : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Jav Guru"

    override val baseUrl = "https://jav.guru"

    override val lang = "all"

    override val supportsLatest = true

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .build()

    private val preference by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private lateinit var popularElements: Elements

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        return if (page == 1) {
            client.newCall(popularAnimeRequest(page))
                .awaitSuccess()
                .use(::popularAnimeParse)
        } else {
            cachedPopularAnimeParse(page)
        }
    }

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/most-watched-rank/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        popularElements = response.asJsoup().select(".tabcontent li")

        return cachedPopularAnimeParse(1)
    }

    private fun cachedPopularAnimeParse(page: Int): AnimesPage {
        val end = min(page * 20, popularElements.size)
        val entries = popularElements.subList((page - 1) * 20, end).map { element ->
            SAnime.create().apply {
                element.select("a").let { a ->
                    getIDFromUrl(a)?.let { url = it }
                        ?: setUrlWithoutDomain(a.attr("href"))

                    title = a.text()
                    thumbnail_url = a.select("img").attr("abs:src")
                }
            }
        }
        return AnimesPage(entries, end < popularElements.size)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl + if (page > 1) "/page/$page/" else ""

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val entries = document.select("div.site-content div.inside-article:not(:contains(nothing))").map { element ->
            SAnime.create().apply {
                element.select("a").let { a ->
                    getIDFromUrl(a)?.let { url = it }
                        ?: setUrlWithoutDomain(a.attr("href"))
                }
                thumbnail_url = element.select("img").attr("abs:src")
                title = element.select("h2 > a").text()
            }
        }

        val page = document.location()
            .pageNumberFromUrlOrNull() ?: 1

        val lastPage = document.select("div.wp-pagenavi a")
            .last()
            ?.attr("href")
            .pageNumberFromUrlOrNull() ?: 1

        return AnimesPage(entries, page < lastPage)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_ID)) {
            val id = query.substringAfter(PREFIX_ID)
            if (id.toIntOrNull() == null) {
                return AnimesPage(emptyList(), false)
            }
            val url = "/$id/"
            val tempAnime = SAnime.create().apply { this.url = url }
            return getAnimeDetails(tempAnime).let {
                val anime = it.apply { this.url = url }
                AnimesPage(listOf(anime), false)
            }
        } else if (query.isNotEmpty()) {
            return client.newCall(searchAnimeRequest(page, query, filters))
                .awaitSuccess()
                .use(::searchAnimeParse)
        } else {
            filters.forEach { filter ->
                when (filter) {
                    is TagFilter,
                    is CategoryFilter,
                    -> {
                        if (filter.state != 0) {
                            val url = "$baseUrl${filter.toUrlPart()}" + if (page > 1) "page/$page/" else ""
                            val request = GET(url, headers)
                            return client.newCall(request)
                                .awaitSuccess()
                                .use(::searchAnimeParse)
                        }
                    }
                    is ActressFilter,
                    is ActorFilter,
                    is StudioFilter,
                    is MakerFilter,
                    -> {
                        if ((filter.state as String).isNotEmpty()) {
                            val url = "$baseUrl${filter.toUrlPart()}" + if (page > 1) "page/$page/" else ""
                            val request = GET(url, headers)
                            return client.newCall(request)
                                .awaitIgnoreCode(404)
                                .use(::searchAnimeParse)
                        }
                    }
                    else -> { }
                }
            }
        }

        throw Exception("Select at least one Filter")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("s", query)
        }.build().toString()

        return GET(url, headers)
    }

    override fun getFilterList() = getFilters()

    override fun searchAnimeParse(response: Response) = latestUpdatesParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val javId = document.selectFirst(".infoleft li:contains(code)")?.ownText()
        val siteCover = document.select(".large-screenshot img").attr("abs:src")

        return SAnime.create().apply {
            title = document.select(".titl").text()
            genre = document.select(".infoleft a[rel*=tag]").joinToString { it.text() }
            author = document.selectFirst(".infoleft li:contains(studio) a")?.text()
            artist = document.selectFirst(".infoleft li:contains(label) a")?.text()
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst(".infoleft li:contains(code)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(director)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(studio)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(label)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(actor)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(actress)")?.text()?.let { append("$it\n") }
            }
            thumbnail_url = if (preference.fetchHDCovers) {
                javId?.let { JavCoverFetcher.getCoverById(it) } ?: siteCover
            } else {
                siteCover
            }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Episode"
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val iframeData = document.selectFirst("script:containsData(iframe_url)")?.html()
            ?: return emptyList()

        val iframeUrls = IFRAME_B64_REGEX.findAll(iframeData)
            .map { it.groupValues[1] }
            .map { Base64.decode(it, Base64.DEFAULT).let(::String) }
            .toList()

        return iframeUrls
            .mapNotNull(::resolveHosterUrl)
            .parallelCatchingFlatMapBlocking(::getVideos)
    }

    private fun resolveHosterUrl(iframeUrl: String): String? {
        val iframeResponse = client.newCall(GET(iframeUrl, headers)).execute()

        if (iframeResponse.isSuccessful.not()) {
            iframeResponse.close()
            return null
        }

        val iframeDocument = iframeResponse.asJsoup()

        val script = iframeDocument.selectFirst("script:containsData(start_player)")
            ?.html() ?: return null

        val olid = IFRAME_OLID_REGEX.find(script)?.groupValues?.get(1)?.reversed()
            ?: return null

        val olidUrl = IFRAME_OLID_URL.find(script)?.groupValues?.get(1)
            ?.substringBeforeLast("=")?.let { "$it=$olid" }
            ?: return null

        val newHeaders = headersBuilder()
            .set("Referer", iframeUrl)
            .build()

        val redirectUrl = noRedirectClient.newCall(GET(olidUrl, newHeaders))
            .execute().use { it.header("location") }
            ?: return null

        if (redirectUrl.toHttpUrlOrNull() == null) {
            return null
        }

        return redirectUrl
    }

    private val streamWishExtractor by lazy {
        val swHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        StreamWishExtractor(client, swHeaders)
    }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val maxStreamExtractor by lazy { MaxStreamExtractor(client, headers) }
    private val emTurboExtractor by lazy { EmTurboExtractor(client, headers) }

    private fun getVideos(hosterUrl: String): List<Video> {
        return when {
            listOf("javplaya", "javclan").any { it in hosterUrl } -> {
                streamWishExtractor.videosFromUrl(hosterUrl)
            }

            hosterUrl.contains("streamtape") -> {
                streamTapeExtractor.videoFromUrl(hosterUrl).let(::listOfNotNull)
            }

            listOf("dood", "ds2play").any { it in hosterUrl } -> {
                doodExtractor.videosFromUrl(hosterUrl)
            }

            listOf("mixdrop", "mixdroop").any { it in hosterUrl } -> {
                mixDropExtractor.videoFromUrl(hosterUrl)
            }

            hosterUrl.contains("maxstream") -> {
                maxStreamExtractor.videoFromUrl(hosterUrl)
            }

            hosterUrl.contains("emturbovid") -> {
                emTurboExtractor.getVideos(hosterUrl)
            }

            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preference.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun getIDFromUrl(element: Elements): String? {
        return element.attr("abs:href")
            .toHttpUrlOrNull()
            ?.pathSegments
            ?.firstOrNull()
            ?.toIntOrNull()
            ?.toString()
            ?.let { "/$it/" }
    }

    private fun String?.pageNumberFromUrlOrNull() =
        this
            ?.substringBeforeLast("/")
            ?.toHttpUrlOrNull()
            ?.pathSegments
            ?.last()
            ?.toIntOrNull()

    private suspend fun Call.awaitIgnoreCode(code: Int): Response {
        return await().also { response ->
            if (!response.isSuccessful && response.code != code) {
                response.close()
                throw Exception("HTTP error ${response.code}")
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = PREF_QUALITY_TITLE
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        JavCoverFetcher.addPreferenceToScreen(screen)
    }

    companion object {
        const val PREFIX_ID = "id:"

        private val IFRAME_B64_REGEX = Regex(""""iframe_url":"([^"]+)"""")
        private val IFRAME_OLID_REGEX = Regex("""var OLID = '([^']+)'""")
        private val IFRAME_OLID_URL = Regex("""src="([^"]+)"""")

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException()
    }
}
