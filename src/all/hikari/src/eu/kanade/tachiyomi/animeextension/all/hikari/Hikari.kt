package eu.kanade.tachiyomi.animeextension.all.hikari

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Hikari : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Hikari"

    override val baseUrl = "https://watch.hikaritv.xyz"

    override val lang = "all"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/ajax/getfilter?type=&country=&stats=&rate=&source=&season=&language=&aired_year=&aired_month=&aired_day=&sort=score&genres=&page=$page"
        val headers = headersBuilder().set("Referer", "$baseUrl/filter").build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<HtmlResponseDto>()

        val hasNextPage = response.request.url.queryParameter("page")!!.toInt() < parsed.page!!.totalPages
        val animeList = parsed.toHtml(baseUrl).select(popularAnimeSelector())
            .map(::popularAnimeFromElement)

        return AnimesPage(animeList, hasNextPage)
    }

    override fun popularAnimeSelector(): String = ".flw-item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[data-id]")!!.attr("abs:href"))
        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
        title = element.selectFirst(".film-name")!!.text()
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/ajax/getfilter?type=&country=&stats=&rate=&source=&season=&language=&aired_year=&aired_month=&aired_day=&sort=recently_updated&genres=&page=$page"
        val headers = headersBuilder().set("Referer", "$baseUrl/filter").build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    override fun latestUpdatesSelector(): String =
        throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String =
        throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addPathSegment("search")
                addQueryParameter("keyword", query)
                addQueryParameter("page", page.toString())
            } else {
                addPathSegment("ajax")
                addPathSegment("getfilter")
                filters.filterIsInstance<UriFilter>().forEach {
                    it.addToUri(this)
                }
                addQueryParameter("page", page.toString())
            }
        }.build()

        val headers = headersBuilder().apply {
            if (query.isNotEmpty()) {
                set("Referer", url.toString().substringBeforeLast("&page"))
            } else {
                set("Referer", "$baseUrl/filter")
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.encodedPath.startsWith("/search")) {
            super.searchAnimeParse(response)
        } else {
            popularAnimeParse(response)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "ul.pagination > li.active + li"

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: text search ignores filters"),
        AnimeFilter.Separator(),
        TypeFilter(),
        CountryFilter(),
        StatusFilter(),
        RatingFilter(),
        SourceFilter(),
        SeasonFilter(),
        LanguageFilter(),
        SortFilter(),
        AiringDateFilter(),
        GenreFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        with(document.selectFirst("#ani_detail")!!) {
            title = selectFirst(".film-name")!!.text()
            thumbnail_url = selectFirst(".film-poster img")!!.attr("abs:src")
            description = selectFirst(".film-description > .text")?.text()
            genre = select(".item-list:has(span:contains(Genres)) > a").joinToString { it.text() }
            author = select(".item:has(span:contains(Studio)) > a").joinToString { it.text() }
            status = selectFirst(".item:has(span:contains(Status)) > .name").parseStatus()
        }
    }

    private fun Element?.parseStatus(): Int = when (this?.text()?.lowercase()) {
        "currently airing" -> SAnime.ONGOING
        "finished" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    private val specialCharRegex = Regex("""(?![\-_])\W{1,}""")

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.split("/")[2]

        val sanitized = anime.title.replace(" ", "_")

        val refererUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("watch")
            addQueryParameter("anime", specialCharRegex.replace(sanitized, ""))
            addQueryParameter("uid", animeId)
            addQueryParameter("eps", "1")
        }.build()

        val headers = headersBuilder()
            .set("Referer", refererUrl.toString())
            .build()

        return GET("$baseUrl/ajax/episodelist/$animeId", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return response.parseAs<HtmlResponseDto>().toHtml(baseUrl)
            .select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeListSelector() = "a[class~=ep-item]"

    override fun episodeFromElement(element: Element): SEpisode {
        val ep = element.selectFirst(".ssli-order")!!.text()
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            episode_number = ep.toFloat()
            name = "Ep. $ep - ${element.selectFirst(".ep-name")?.text() ?: ""}"
        }
    }

    // ============================ Video Links =============================

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val embedRegex = Regex("""getEmbed\(\s*(\d+)\s*,\s*(\d+)\s*,\s*'(\d+)'""")

    override fun videoListRequest(episode: SEpisode): Request {
        val url = (baseUrl + episode.url).toHttpUrl()
        val animeId = url.queryParameter("uid")!!
        val episodeNum = url.queryParameter("eps")!!

        val headers = headersBuilder()
            .set("Referer", baseUrl + episode.url)
            .build()

        return GET("$baseUrl/ajax/embedserver/$animeId/$episodeNum", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.parseAs<HtmlResponseDto>().toHtml(baseUrl)

        val headers = headersBuilder()
            .set("Referer", response.request.url.toString())
            .build()

        val embedUrls = html.select(videoListSelector()).flatMap {
            val name = it.text()
            val onClick = it.selectFirst("a")!!.attr("onclick")
            val match = embedRegex.find(onClick)!!.groupValues
            val url = "$baseUrl/ajax/embed/${match[1]}/${match[2]}/${match[3]}"
            val iframeList = client.newCall(
                GET(url, headers),
            ).execute().parseAs<List<String>>()

            iframeList.map {
                Pair(Jsoup.parseBodyFragment(it).selectFirst("iframe")!!.attr("src"), name)
            }
        }

        return embedUrls.parallelCatchingFlatMapBlocking {
            getVideosFromEmbed(it.first, it.second)
        }
    }

    private fun getVideosFromEmbed(embedUrl: String, name: String): List<Video> = when {
        name.contains("vidhide", true) -> vidHideExtractor.videosFromUrl(embedUrl)
        embedUrl.contains("filemoon", true) -> {
            filemoonExtractor.videosFromUrl(embedUrl, prefix = "$name - ", headers = headers)
        }
        else -> emptyList()
    }

    override fun videoListSelector() = ".server-item:has(a[onclick~=getEmbed])"

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun videoFromElement(element: Element): Video =
        throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    @Serializable
    class HtmlResponseDto(
        val html: String,
        val page: PageDto? = null,
    ) {
        fun toHtml(baseUrl: String): Document = Jsoup.parseBodyFragment(html, baseUrl)

        @Serializable
        class PageDto(
            val totalPages: Int,
        )
    }

    companion object {
        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_ENTRIES = PREF_QUALITY_VALUES.map {
            "${it}p"
        }.toTypedArray()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
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
