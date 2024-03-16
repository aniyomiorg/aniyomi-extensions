package eu.kanade.tachiyomi.animeextension.en.animeflixlive

import GenreFilter
import SortFilter
import SubPageFilter
import TypeFilter
import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

class AnimeflixLive : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Animeflix.live"

    override val baseUrl by lazy { preferences.baseUrl }

    private val apiUrl by lazy { preferences.apiUrl }

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiHeaders = headersBuilder().apply {
        add("Accept", "*/*")
        add("Host", apiUrl.toHttpUrl().host)
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    private val docHeaders = headersBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Host", apiUrl.toHttpUrl().host)
        add("Referer", "$baseUrl/")
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiUrl/popular?page=${page - 1}", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<List<AnimeDto>>()
        val titlePref = preferences.titleType

        val animeList = parsed.map {
            it.toSAnime(titlePref)
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiUrl/trending?page=${page - 1}", apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsed = response.parseAs<TrendingDto>()
        val titlePref = preferences.titleType

        val animeList = parsed.trending.map {
            it.toSAnime(titlePref)
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().first().getValue()
        val type = filters.filterIsInstance<TypeFilter>().first().getValues()
        val genre = filters.filterIsInstance<GenreFilter>().first().getValues()
        val subPage = filters.filterIsInstance<SubPageFilter>().first().getValue()

        if (subPage.isNotBlank()) {
            return GET("$apiUrl/$subPage?page=${page - 1}", apiHeaders)
        }

        if (query.isEmpty()) {
            throw Exception("Search must not be empty")
        }

        val filtersObj = buildJsonObject {
            put("sort", sort)
            if (type.isNotEmpty()) {
                put("type", json.encodeToString(type))
            }
            if (genre.isNotEmpty()) {
                put("genre", json.encodeToString(genre))
            }
        }.toJsonString()

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("info")
            addPathSegment("")
            addQueryParameter("query", query)
            addQueryParameter("limit", "15")
            addQueryParameter("filters", filtersObj)
            addQueryParameter("k", query.substr(0, 3).sk())
        }.build()

        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<List<AnimeDto>>()
        val titlePref = preferences.titleType

        val animeList = parsed.map {
            it.toSAnime(titlePref)
        }

        val hasNextPage = if (response.request.url.queryParameter("limit") == null) {
            animeList.size == 44
        } else {
            animeList.size == 15
        }

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("NOTE: Subpage overrides search and other filters!"),
        SubPageFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$apiUrl/getslug/${anime.url}", apiHeaders)
    }

    override fun getAnimeUrl(anime: SAnime): String {
        return "$baseUrl/search/${anime.title}?anime=${anime.url}"
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val titlePref = preferences.titleType
        return response.parseAs<DetailsDto>().toSAnime(titlePref)
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val lang = preferences.lang

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("episodes")
            addQueryParameter("id", anime.url)
            addQueryParameter("dub", (lang == "Dub").toString())
            addQueryParameter("c", anime.url.sk())
        }.build()

        return GET(url, apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = response.request.url.queryParameter("id")!!

        return response.parseAs<EpisodeResponseDto>().episodes.map {
            it.toSEpisode(slug)
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val url = "$apiUrl${episode.url}".toHttpUrl().newBuilder().apply {
            addQueryParameter("server", "")
            addQueryParameter("c", episode.url.substringAfter("/watch/").sk())
        }.build()

        return GET(url, apiHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val initialPlayerUrl = apiUrl + response.parseAs<ServerDto>().source
        val initialServer = initialPlayerUrl.toHttpUrl().queryParameter("server")!!

        val initialPlayerDocument = client.newCall(
            GET(initialPlayerUrl, docHeaders),
        ).execute().asJsoup().unescape()

        videoList.addAll(
            videosFromPlayer(
                initialPlayerDocument,
                initialServer.replaceFirstChar { c -> c.titlecase(Locale.ROOT) },
            ),
        )

        // Go through rest of servers
        val servers = initialPlayerDocument.selectFirst("script:containsData(server-settings)")!!.data()
        val serversHtml = SERVER_REGEX.findAll(servers).map {
            Jsoup.parseBodyFragment(it.groupValues[1])
        }.toList()

        videoList.addAll(
            serversHtml.parallelCatchingFlatMapBlocking {
                val server = serverMapping[
                    it.selectFirst("button")!!
                        .attr("onclick")
                        .substringAfter("postMessage('")
                        .substringBefore("'"),
                ]
                if (server == initialServer) {
                    return@parallelCatchingFlatMapBlocking emptyList()
                }

                val serverUrl = response.request.url.newBuilder()
                    .setQueryParameter("server", server)
                    .build()
                val playerUrl = apiUrl + client.newCall(
                    GET(serverUrl, apiHeaders),
                ).execute().parseAs<ServerDto>().source

                if (server != playerUrl.toHttpUrl().queryParameter("server")!!) {
                    return@parallelCatchingFlatMapBlocking emptyList()
                }

                val playerDocument = client.newCall(
                    GET(playerUrl, docHeaders),
                ).execute().asJsoup().unescape()

                videosFromPlayer(
                    playerDocument,
                    server.replaceFirstChar { c -> c.titlecase(Locale.ROOT) },
                )
            },
        )

        return videoList
    }

    private val serverMapping = mapOf(
        "settings-0" to "moon",
        "settings-1" to "sun",
        "settings-2" to "zoro",
        "settings-3" to "gogo",
    )

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun getVideoHeaders(baseHeaders: Headers, referer: String, videoUrl: String): Headers {
        return baseHeaders.newBuilder().apply {
            add("Accept", "*/*")
            add("Accept-Language", "en-US,en;q=0.5")
            add("Host", videoUrl.toHttpUrl().host)
            add("Origin", "https://${apiUrl.toHttpUrl().host}")
            add("Referer", "$apiUrl/")
            add("Sec-Fetch-Dest", "empty")
            add("Sec-Fetch-Mode", "cors")
            add("Sec-Fetch-Site", "cross-site")
        }.build()
    }

    private fun Document.unescape(): Document {
        val unescapeScript = this.selectFirst("script:containsData(unescape)")
        return if (unescapeScript == null) {
            this
        } else {
            val data = URLDecoder.decode(unescapeScript.data(), "UTF-8")
            Jsoup.parse(data, this.location())
        }
    }

    private fun videosFromPlayer(document: Document, name: String): List<Video> {
        val dataScript = document.selectFirst("script:containsData(m3u8)")
            ?.data() ?: return emptyList()

        val subtitleList = document.select("video > track[kind=captions]").map {
            Track(it.attr("id"), it.attr("label"))
        }

        var masterPlaylist = M3U8_REGEX.find(dataScript)?.groupValues?.get(1)
            ?: return emptyList()

        if (name.equals("moon", true)) {
            masterPlaylist += dataScript.substringAfter("`${'$'}{url}")
                .substringBefore("`")
        }

        return playlistUtils.extractFromHls(
            masterPlaylist,
            videoHeadersGen = ::getVideoHeaders,
            videoNameGen = { q -> "$name - $q" },
            subtitleList = subtitleList,
        )
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        val server = preferences.server

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    private fun JsonObject.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun String.sk(): String {
        val t = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val n = 17 + (t.get(Calendar.DAY_OF_MONTH) - t.get(Calendar.MONTH)) / 2
        return this.toCharArray().fold("") { acc, c ->
            acc + c.code.toString(n).padStart(2, '0')
        }
    }

    private fun String.substr(start: Int, end: Int): String {
        val stop = min(end, this.length)
        return this.substring(start, stop)
    }

    companion object {
        private val SERVER_REGEX = Regex("""'1' === '1'.*?(<button.*?</button>)""", RegexOption.DOT_MATCHES_ALL)
        private val M3U8_REGEX = Regex("""const ?\w*? ?= ?`(.*?)`""")
        private const val PAGE_SIZE = 24

        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private const val PREF_DOMAIN_DEFAULT = "https://animeflix.live,https://api.animeflix.dev"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animeflix.live", "animeflix.ro")
        private val PREF_DOMAIN_ENTRY_VALUES = arrayOf(
            "https://animeflix.live,https://api.animeflix.dev",
            "https://animeflix.ro,https://api.animeflixtv.to",
        )

        private const val PREF_TITLE_KEY = "pref_title_type_key"
        private const val PREF_TITLE_DEFAULT = "English"
        private val PREF_TITLE_ENTRIES = arrayOf("English", "Native", "Romaji")

        private const val PREF_LANG_KEY = "pref_lang_key"
        private const val PREF_LANG_DEFAULT = "Sub"
        private val PREF_LANG_ENTRIES = arrayOf("Sub", "Dub")

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_ENTRIES = PREF_QUALITY_ENTRY_VALUES.map { "${it}p" }.toTypedArray()

        private const val PREF_SERVER_KEY = "pref_server_key"
        private const val PREF_SERVER_DEFAULT = "Moon"
        private val PREF_SERVER_ENTRIES = arrayOf("Moon", "Sun", "Zoro", "Gogo")
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
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
            key = PREF_TITLE_KEY
            title = "Preferred Title Type"
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_ENTRIES
            setDefaultValue(PREF_TITLE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Language"
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_ENTRIES
            setDefaultValue(PREF_LANG_DEFAULT)
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
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRY_VALUES
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
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
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

    private val SharedPreferences.baseUrl
        get() = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            .split(",").first()

    private val SharedPreferences.apiUrl
        get() = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            .split(",").last()

    private val SharedPreferences.titleType
        get() = getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT)!!

    private val SharedPreferences.lang
        get() = getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.server
        get() = getString(PREF_SERVER_KEY, PREF_QUALITY_DEFAULT)!!
}
