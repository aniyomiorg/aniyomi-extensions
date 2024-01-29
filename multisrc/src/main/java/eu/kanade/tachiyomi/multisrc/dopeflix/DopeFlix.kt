package eu.kanade.tachiyomi.multisrc.dopeflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.VideoDto
import eu.kanade.tachiyomi.multisrc.dopeflix.extractors.DopeFlixExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class DopeFlix(
    override val name: String,
    override val lang: String,
    private val domainArray: Array<String>,
    private val defaultDomain: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val baseUrl by lazy {
        "https://" + preferences.getString(PREF_DOMAIN_KEY, defaultDomain)!!
    }

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.film_list-wrap div.flw-item div.film-poster"

    override fun popularAnimeRequest(page: Int): Request {
        val type = preferences.getString(PREF_POPULAR_KEY, PREF_POPULAR_DEFAULT)!!
        return GET("$baseUrl/$type?page=$page")
    }

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val ahref = element.selectFirst("a")!!
        setUrlWithoutDomain(ahref.attr("href"))
        title = ahref.attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li.page-item a[title=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home/")

    override fun latestUpdatesSelector(): String {
        val sectionLabel = preferences.getString(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)!!
        return "section.block_area:has(h2.cat-heading:contains($sectionLabel)) div.film-poster"
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = DopeFlixFilters.getSearchParameters(filters)

        val url = if (query.isNotBlank()) {
            val fixedQuery = query.replace(" ", "-")
            "$baseUrl/search/$fixedQuery?page=$page"
        } else {
            "$baseUrl/filter".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("type", params.type)
                .addQueryParameter("quality", params.quality)
                .addQueryParameter("release_year", params.releaseYear)
                .addIfNotBlank("genre", params.genres)
                .addIfNotBlank("country", params.countries)
                .build()
                .toString()
        }

        return GET(url, headers)
    }

    override fun getFilterList() = DopeFlixFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("img.film-poster-img")!!.attr("src")
        title = document.selectFirst("img.film-poster-img")!!.attr("title")
        genre = document.select("div.row-line:contains(Genre) a").eachText().joinToString()
        description = document.selectFirst("div.detail_page-watch div.description")!!
            .text().replace("Overview:", "")
        author = document.select("div.row-line:contains(Production) a").eachText().joinToString()
        status = parseStatus(document.selectFirst("li.status span.value")?.text())
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Ongoing" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val infoElement = document.selectFirst("div.detail_page-watch")!!
        val id = infoElement.attr("data-id")
        val dataType = infoElement.attr("data-type") // Tv = 2 or movie = 1
        return if (dataType == "2") {
            val seasonUrl = "$baseUrl/ajax/v2/tv/seasons/$id"
            val seasonsHtml = client.newCall(
                GET(
                    seasonUrl,
                    headers = Headers.headersOf("Referer", document.location()),
                ),
            ).execute().asJsoup()
            seasonsHtml
                .select("a.dropdown-item.ss-item")
                .flatMap(::parseEpisodesFromSeries)
                .reversed()
        } else {
            val movieUrl = "$baseUrl/ajax/movie/episodes/$id"
            SEpisode.create().apply {
                name = document.selectFirst("h2.heading-name")!!.text()
                episode_number = 1F
                setUrlWithoutDomain(movieUrl)
            }.let(::listOf)
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("data-id")
        val seasonName = element.text()
        val episodesUrl = "$baseUrl/ajax/v2/season/episodes/$seasonId"
        val episodesHtml = client.newCall(GET(episodesUrl)).execute()
            .asJsoup()
        val episodeElements = episodesHtml.select("div.eps-item")
        return episodeElements.map { episodeFromElement(it, seasonName) }
    }

    private fun episodeFromElement(element: Element, seasonName: String) = SEpisode.create().apply {
        val episodeId = element.attr("data-id")
        val epNum = element.selectFirst("div.episode-number")!!.text()
        val epName = element.selectFirst("h3.film-name a")!!.text()
        name = "$seasonName $epNum $epName"
        episode_number = "${seasonName.getNumber()}.${epNum.getNumber().padStart(3, '0')}".toFloatOrNull() ?: 1F
        setUrlWithoutDomain("$baseUrl/ajax/v2/episode/servers/$episodeId")
    }

    private fun String.getNumber() = filter(Char::isDigit)

    // ============================ Video Links =============================
    private val extractor by lazy { DopeFlixExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val episodeReferer = Headers.headersOf("Referer", response.request.header("referer")!!)
        return doc.select("ul.fss-list a.btn-play")
            .parallelCatchingFlatMapBlocking { server ->
                val name = server.selectFirst("span")!!.text()
                val id = server.attr("data-id")
                val url = "$baseUrl/ajax/sources/$id"
                val reqBody = client.newCall(GET(url, episodeReferer)).execute()
                    .body.string()
                val sourceUrl = reqBody.substringAfter("\"link\":\"")
                    .substringBefore("\"")
                when {
                    "DoodStream" in name ->
                        DoodExtractor(client).videoFromUrl(sourceUrl)
                            ?.let(::listOf)
                    "Vidcloud" in name || "UpCloud" in name -> {
                        val video = extractor.getVideoDto(sourceUrl)
                        getVideosFromServer(video, name)
                    }
                    else -> null
                }.orEmpty()
            }
    }

    private fun getVideosFromServer(video: VideoDto, name: String): List<Video> {
        val masterUrl = video.sources.first().file
        val subs = video.tracks
            ?.filter { it.kind == "captions" }
            ?.mapNotNull { Track(it.file, it.label) }
            ?.let(::subLangOrder)
            ?: emptyList<Track>()
        if (masterUrl.contains("playlist.m3u8")) {
            return playlistUtils.extractFromHls(
                masterUrl,
                videoNameGen = { "$name - $it" },
                subtitleList = subs,
            )
        }

        return listOf(
            Video(masterUrl, "$name - Default", masterUrl, subtitleTracks = subs),
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) }, // preferred quality first
                // then group by quality
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!
        return tracks.sortedWith(
            compareBy { it.lang.contains(language) },
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = domainArray
            entryValues = domainArray
            setDefaultValue(defaultDomain)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_LIST
            entryValues = PREF_QUALITY_LIST
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
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_LANGUAGES
            entryValues = PREF_SUB_LANGUAGES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LATEST_KEY
            title = PREF_LATEST_TITLE
            entries = PREF_LATEST_PAGES
            entryValues = PREF_LATEST_PAGES
            setDefaultValue(PREF_LATEST_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_POPULAR_KEY
            title = PREF_POPULAR_TITLE
            entries = PREF_POPULAR_ENTRIES
            entryValues = PREF_POPULAR_VALUES
            setDefaultValue(PREF_POPULAR_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain_new"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        private const val PREF_SUB_DEFAULT = "English"
        private val PREF_SUB_LANGUAGES = arrayOf(
            "Arabic", "English", "French", "German", "Hungarian",
            "Italian", "Japanese", "Portuguese", "Romanian", "Russian",
            "Spanish",
        )

        private const val PREF_LATEST_KEY = "preferred_latest_page"
        private const val PREF_LATEST_TITLE = "Preferred latest page"
        private const val PREF_LATEST_DEFAULT = "Movies"
        private val PREF_LATEST_PAGES = arrayOf("Movies", "TV Shows")

        private const val PREF_POPULAR_KEY = "preferred_popular_page_new"
        private const val PREF_POPULAR_TITLE = "Preferred popular page"
        private const val PREF_POPULAR_DEFAULT = "movie"
        private val PREF_POPULAR_ENTRIES = PREF_LATEST_PAGES
        private val PREF_POPULAR_VALUES = arrayOf("movie", "tv-show")
    }
}
