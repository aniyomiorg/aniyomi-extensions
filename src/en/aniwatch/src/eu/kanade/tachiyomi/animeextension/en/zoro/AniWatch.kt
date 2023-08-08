package eu.kanade.tachiyomi.animeextension.en.zoro

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.zoro.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.en.zoro.extractors.AniWatchExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class AniWatch : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AniWatch.to"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val id = 6706411382606718900L

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val ajaxRoute by lazy { if (baseUrl == "https://kaido.to") "" else "/v2" }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.flw-item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-popular?page=$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("div.film-poster > img")!!.attr("data-src")
        val filmDetail = element.selectFirst("div.film-detail a")!!
        setUrlWithoutDomain(filmDetail.attr("href"))
        title = filmDetail.attr("data-jname")
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a[title=Next]"

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/top-airing")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$slug"))
                .asObservableSuccess()
                .map(::searchAnimeBySlugParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeBySlugParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniWatchFilters.getSearchParameters(filters)
        val endpoint = if (query.isEmpty()) "filter" else "search"
        val url = "$baseUrl/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addIfNotBlank("keyword", query)
            .addIfNotBlank("type", params.type)
            .addIfNotBlank("status", params.status)
            .addIfNotBlank("rated", params.rated)
            .addIfNotBlank("score", params.score)
            .addIfNotBlank("season", params.season)
            .addIfNotBlank("language", params.language)
            .addIfNotBlank("sort", params.sort)
            .addIfNotBlank("sy", params.start_year)
            .addIfNotBlank("sm", params.start_month)
            .addIfNotBlank("sd", params.start_day)
            .addIfNotBlank("ey", params.end_year)
            .addIfNotBlank("em", params.end_month)
            .addIfNotBlank("ed", params.end_day)
            .addIfNotBlank("genres", params.genres)
            .build()

        return GET(url.toString())
    }

    override fun getFilterList() = AniWatchFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val info = document.selectFirst("div.anisc-info")!!
        val detail = document.selectFirst("div.anisc-detail")!!
        thumbnail_url = document.selectFirst("div.anisc-poster img")!!.attr("src")
        title = detail.selectFirst("h2")!!.attr("data-jname")
        author = info.getInfo("Studios:")
        status = parseStatus(info.getInfo("Status:"))
        genre = info.getInfo("Genres:", isList = true)

        description = buildString {
            info.getInfo("Overview:")?.also { append(it + "\n") }

            detail.select("div.film-stats div.tick-dub").eachText().also {
                append("\nLanguage: " + it.joinToString())
            }

            info.getInfo("Aired:", full = true)?.also(::append)
            info.getInfo("Premiered:", full = true)?.also(::append)
            info.getInfo("Synonyms:", full = true)?.also(::append)
            info.getInfo("Japanese:", full = true)?.also(::append)
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        val referer = Headers.headersOf("Referer", baseUrl + anime.url)
        return GET("$baseUrl/ajax$ajaxRoute/episode/list/$id", referer)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.parseAs<HtmlResponse>().html)

        return document.select("a.ep-item")
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        episode_number = element.attr("data-number").toFloatOrNull() ?: 1F
        name = "Episode ${element.attr("data-number")}: ${element.attr("title")}"
        setUrlWithoutDomain(element.attr("href"))
        if (element.hasClass("ssl-item-filler") && preferences.getBoolean(MARK_FILLERS_KEY, MARK_FILLERS_DEFAULT)) {
            scanlator = "Filler Episode"
        }
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val id = episode.url.substringAfterLast("?ep=")
        val referer = Headers.headersOf("Referer", baseUrl + episode.url)
        return GET("$baseUrl/ajax$ajaxRoute/episode/servers?episodeId=$id", referer)
    }

    private val aniwatchExtractor by lazy { AniWatchExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val episodeReferer = Headers.headersOf("Referer", response.request.header("referer")!!)
        val serversDoc = Jsoup.parse(response.parseAs<HtmlResponse>().html)
        return serversDoc.select("div.server-item")
            .parallelMap { server ->
                val name = server.text()
                val id = server.attr("data-id")
                val subDub = server.attr("data-type")

                val url = "$baseUrl/ajax$ajaxRoute/episode/sources?id=$id"
                val reqBody = client.newCall(GET(url, episodeReferer)).execute()
                    .use { it.body.string() }
                val sourceUrl = reqBody.substringAfter("\"link\":\"")
                    .substringBefore("\"")
                runCatching {
                    when {
                        "Vidstreaming" in name || "Vidcloud" in name -> {
                            aniwatchExtractor.getVideoDto(sourceUrl).let {
                                getVideosFromServer(it, subDub, name)
                            }
                        }
                        "Streamtape" in name ->
                            StreamTapeExtractor(client)
                                .videoFromUrl(sourceUrl, "StreamTape - $subDub")
                                ?.let(::listOf)
                        else -> null
                    }
                }.onFailure { it.printStackTrace() }.getOrNull() ?: emptyList()
            }.flatten()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun getVideosFromServer(video: VideoDto, subDub: String, name: String): List<Video> {
        val masterUrl = video.sources.first().file
        val subs2 = video.tracks
            ?.filter { it.kind == "captions" }
            ?.mapNotNull { Track(it.file, it.label) }
            ?: emptyList<Track>()
        val subs = subLangOrder(subs2)
        return playlistUtils.extractFromHls(
            masterUrl,
            videoNameGen = { "$name - $it - $subDub" },
            subtitleList = subs,
        )
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(type) },
            ),
        ).reversed()
    }

    private fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!
        return tracks.sortedWith(
            compareBy { it.lang.contains(language) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_ENTRY_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
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
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
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
            key = PREF_TYPE_KEY
            title = PREF_TYPE_TITLE
            entries = PREF_TYPE_ENTRIES
            entryValues = PREF_TYPE_ENTRIES
            setDefaultValue(PREF_TYPE_DEFAULT)
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
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_ENTRIES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = MARK_FILLERS_KEY
            title = MARK_FILLERS_TITLE
            setDefaultValue(MARK_FILLERS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        return use { it.body.string() }.let(json::decodeFromString)
    }

    @Serializable
    private data class HtmlResponse(val html: String)

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(
        tag: String,
        isList: Boolean = false,
        full: Boolean = false,
    ): String? {
        if (isList) {
            return select("div.item-list:contains($tag) > a").eachText().joinToString()
        }
        val value = selectFirst("div.item-title:contains($tag)")
            ?.selectFirst("*.name, *.text")
            ?.text()
        return if (full && value != null) "\n$tag $value" else value
    }

    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        const val PREFIX_SEARCH = "slug:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "720p", "1080p")

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private const val PREF_DOMAIN_DEFAULT = "https://kaido.to"
        private val PREF_DOMAIN_ENTRIES = arrayOf("kaido.to", "aniwatch.to")
        private val PREF_DOMAIN_ENTRY_VALUES = arrayOf("https://kaido.to", "https://aniwatch.to")

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_TITLE = "Preferred episode type/mode"
        private const val PREF_TYPE_DEFAULT = "dub"
        private val PREF_TYPE_ENTRIES = arrayOf("sub", "dub")

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        private const val PREF_SUB_DEFAULT = "English"
        private val PREF_SUB_ENTRIES = arrayOf(
            "English",
            "Spanish",
            "Portuguese",
            "French",
            "German",
            "Italian",
            "Japanese",
            "Russian",
            "Arabic",
        )

        private const val MARK_FILLERS_KEY = "mark_fillers"
        private const val MARK_FILLERS_TITLE = "Mark filler episodes"
        private const val MARK_FILLERS_DEFAULT = true
    }
}
