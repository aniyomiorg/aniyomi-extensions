package eu.kanade.tachiyomi.animeextension.en.allanimechi

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.allanimechi.extractors.AllAnimeExtractor
import eu.kanade.tachiyomi.animeextension.en.allanimechi.extractors.InternalExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.gogostreamextractor.GogoStreamExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class AllAnimeChi : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AllAnimeChi"

    override val baseUrl = "aHR0cHM6Ly9hY2FwaS5hbGxhbmltZS5kYXk=".decodeBase64()
    private val siteUrl = "aHR0cHM6Ly9hbGxhbmltZS50bw==".decodeBase64()

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiHeaders = Headers.Builder().apply {
        add("app-version", "android_c-253")
        add("content-type", "application/json; charset=UTF-8")
        add("from-app", "YW5pbWVjaGlja2Vu".decodeBase64())
        add("host", baseUrl.toHttpUrl().host)
        add("platformstr", "android_c")
        add("Referer", "$siteUrl/")
        add("user-agent", "Dart/2.19 (dart:io)")
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val variables = buildJsonObject {
            put("type", "anime")
            put("size", PAGE_SIZE)
            put("dateRange", 7)
            put("page", page)
            put("allowAdult", false)
            put("allowUnknown", false)
            put("denyEcchi", false)
        }.encode()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", POPULAR_HASH)
            }
        }.encode()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("variables", variables)
            addQueryParameter("extensions", extensions)
        }.build().toString().replace("%3A", ":")

        return GET(url, apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<PopularResult>()
        val animeList = parsed.data.queryPopular.recommendations.mapNotNull {
            it.anyCard?.toSAnime(preferences.titleStyle)
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val variables = buildJsonObject {
            putJsonObject("search") {
                put("allowAdult", false)
                put("allowUnknown", false)
                put("denyEcchi", false)
            }
            put("translationType", preferences.subPref)
            put("limit", PAGE_SIZE)
            put("page", page)
            put("countryOrigin", "ALL")
        }.encode()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", LATEST_HASH)
            }
        }.encode()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("variables", variables)
            addQueryParameter("extensions", extensions)
        }.build().toString().replace("%3A", ":")

        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsed = response.parseAs<SearchResult>()
        val animeList = parsed.data.shows.edges.map {
            it.toSAnime(preferences.titleStyle)
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AllAnimeChiFilters.getSearchParameters(filters)

        val variables = buildJsonObject {
            putJsonObject("search") {
                if (query.isBlank()) {
                    if (params.genres.isNotEmpty()) {
                        putJsonArray("genres") {
                            params.genres.forEach {
                                add(it)
                            }
                        }
                    }
                    if (params.type != "all") {
                        putJsonArray("types") {
                            add(params.type)
                        }
                    }
                    if (params.season != "all") {
                        put("season", params.season)
                    }
                    if (params.releaseYear != "all") {
                        put("year", params.releaseYear.toInt())
                    }
                    if (params.episodeCount != "all") {
                        val (start, end) = params.episodeCount.split("-")
                        if (start.isNotBlank()) put("epRangeStart", start.toInt())
                        if (end.isNotBlank()) put("epRangeEnd", end.toInt())
                    }
                } else {
                    put("query", query)
                }
                put("sortBy", "Latest_Update")
                put("allowAdult", false)
                put("allowUnknown", false)
                put("denyEcchi", false)
            }
            put("translationType", preferences.subPref)
            put("limit", PAGE_SIZE)
            put("page", page)
            put("countryOrigin", params.origin)
        }.encode()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", LATEST_HASH)
            }
        }.encode()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("variables", variables)
            addQueryParameter("extensions", extensions)
        }.build().toString().replace("%3A", ":")

        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = latestUpdatesParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AllAnimeChiFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val variables = buildJsonObject {
            put("_id", anime.url)
        }.encode()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", DETAILS_HASH)
            }
        }.encode()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("variables", variables)
            addQueryParameter("extensions", extensions)
        }.build().toString()

        return GET(url, apiHeaders)
    }

    override fun getAnimeUrl(anime: SAnime): String {
        return "data:text/plain,This%20extension%20does%20not%20have%20a%20website."
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val show = response.parseAs<DetailsResult>().data.show

        return SAnime.create().apply {
            genre = show.genres?.joinToString(separator = ", ") ?: ""
            status = parseStatus(show.status)
            author = show.studios?.firstOrNull()
            description = buildString {
                append(
                    Jsoup.parseBodyFragment(
                        show.description?.replace("<br>", "br2n") ?: "",
                    ).text().replace("br2n", "\n"),
                )
                append("\n\n")
                append("Type: ${show.type ?: "Unknown"}")
                append("\nAired: ${show.season?.quarter ?: "-"} ${show.season?.year ?: "-"}")
                append("\nScore: ${show.score ?: "-"}★")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = response.parseAs<SeriesResult>()
        val subPref = preferences.subPref

        val episodesDetail = if (subPref == "sub") {
            media.data.show.availableEpisodesDetail.sub!!
        } else {
            media.data.show.availableEpisodesDetail.dub!!
        }

        return episodesDetail.map { ep ->
            val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")

            SEpisode.create().apply {
                episode_number = ep.toFloatOrNull() ?: 0F
                name = "Episode $numName ($subPref)"
                url = buildJsonObject {
                    put("showId", media.data.show._id)
                    put("translationType", subPref)
                    put("episodeString", ep)
                }.encode()
            }
        }
    }

    // ============================ Video Links =============================

    private val internalExtractor by lazy { InternalExtractor(client, apiHeaders, headers) }

    override fun videoListRequest(episode: SEpisode): Request {
        val variables = episode.url

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", STREAMS_HASH)
            }
        }.encode()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addQueryParameter("variables", variables)
            addQueryParameter("extensions", extensions)
        }.build().toString()

        return GET(url, apiHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoJson = response.parseAs<EpisodeResult>()

        val hosterBlackList = preferences.getHosterBlacklist
        val altHosterBlackList = preferences.getAltHosterBlacklist
        val useHosterNames = preferences.useHosterName

        val serverList = videoJson.data.episode.sourceUrls.mapNotNull { video ->
            when {
                // "Internal" sources
                video.sourceUrl.startsWith("/apivtwo/") && !hosterBlackList.any {
                    Regex("""\b${it.lowercase()}\b""").find(video.sourceName.lowercase()) != null
                } -> {
                    Server(video.sourceUrl, video.sourceName, video.priority, "internal")
                }

                // Player, direct video links.
                video.type == "player" && !altHosterBlackList.contains(video.sourceName, true) -> {
                    Server(video.sourceUrl, video.sourceName, video.priority, "player")
                }

                // External video players
                !altHosterBlackList.contains(video.sourceName, true) && !video.sourceUrl.startsWith("/apivtwo/") -> {
                    Server(video.sourceUrl, video.sourceName, video.priority, "external")
                }

                else -> null
            }
        }

        return prioritySort(
            serverList.parallelCatchingFlatMapBlocking { getVideoFromServer(it, useHosterNames) },
        )
    }

    private fun getVideoFromServer(server: Server, useHosterName: Boolean): List<Pair<Video, Float>> {
        return when (server.type) {
            "player" -> getFromPlayer(server, useHosterName)
            "internal" -> internalExtractor.videosFromServer(server, useHosterName, removeRaw = preferences.removeRaw)
            "external" -> getFromExternal(server, useHosterName)
            else -> emptyList()
        }
    }

    private fun getFromPlayer(server: Server, useHosterName: Boolean): List<Pair<Video, Float>> {
        val name = if (useHosterName) {
            getHostName(server.sourceUrl, server.sourceName)
        } else {
            server.sourceName
        }

        val videoHeaders = headers.newBuilder().apply {
            add("origin", siteUrl)
            add("referer", "$siteUrl/")
        }.build()

        val video = Video(server.sourceUrl, name, server.sourceUrl, headers = videoHeaders)
        return listOf(
            Pair(video, server.priority),
        )
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val gogoExtractor by lazy { GogoStreamExtractor(client) }
    private val allanimeExtractor by lazy { AllAnimeExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    private fun getFromExternal(server: Server, useHosterName: Boolean): List<Pair<Video, Float>> {
        val url = server.sourceUrl.replace(Regex("""^//"""), "https://")
        val prefix = if (useHosterName) {
            "${getHostName(url, server.sourceName)} - "
        } else {
            "${server.sourceName} - "
        }

        val videoList = when {
            url.startsWith("https://ok") -> okruExtractor.videosFromUrl(url, prefix = prefix)
            url.startsWith("https://filemoon") -> filemoonExtractor.videosFromUrl(url, prefix = prefix)
            url.startsWith("https://streamlare") -> streamlareExtractor.videosFromUrl(url, prefix = prefix)
            url.startsWith("https://mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers, prefix = prefix)
            server.sourceName.equals("Vid-mp4", true) -> gogoExtractor.videosFromUrl(url)
            url.startsWith("https://allanime") -> allanimeExtractor.videosFromUrl(url, prefix = prefix)
            url.startsWith("https://streamwish") -> streamwishExtractor.videosFromUrl(url, videoNameGen = { q -> prefix + q })
            else -> emptyList()
        }

        return videoList.map { Pair(it, server.priority) }
    }

    // ============================= Utilities ==============================

    private fun getHostName(host: String, fallback: String): String {
        return host.toHttpUrlOrNull()?.host?.split(".")?.let {
            it.getOrNull(it.size - 2)?.replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString()
            }
        } ?: fallback
    }

    private fun String.decodeBase64(): String {
        return String(Base64.decode(this, Base64.DEFAULT))
    }

    private fun JsonObject.encode(): String {
        return json.encodeToString(this)
    }

    data class Server(
        val sourceUrl: String,
        val sourceName: String,
        val priority: Float,
        val type: String,
    )

    fun Set<String>.contains(element: String, ignoreCase: Boolean): Boolean {
        return this.any { it.equals(element, ignoreCase = ignoreCase) }
    }

    private fun prioritySort(pList: List<Pair<Video, Float>>): List<Video> {
        val prefServer = preferences.prefServer
        val quality = preferences.quality
        val subPref = preferences.subPref

        return pList.sortedWith(
            compareBy(
                { if (prefServer == "site_default") it.second else it.first.quality.contains(prefServer, true) },
                { it.first.quality.contains(quality, true) },
                { it.first.quality.contains(subPref, true) },
            ),
        ).reversed().map { t -> t.first }
    }

    private fun parseStatus(string: String?): Int {
        return when (string) {
            "Releasing" -> SAnime.ONGOING
            "Finished" -> SAnime.COMPLETED
            "Not Yet Released" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private const val PAGE_SIZE = 30 // number of items to retrieve when calling API

        private const val POPULAR_HASH = "31a117653812a2547fd981632e8c99fa8bf8a75c4ef1a77a1567ef1741a7ab9c"
        private const val LATEST_HASH = "e42a4466d984b2c0a2cecae5dd13aa68867f634b16ee0f17b380047d14482406"
        private const val DETAILS_HASH = "bb263f91e5bdd048c1c978f324613aeccdfe2cbc694a419466a31edb58c0cc0b"
        private const val STREAMS_HASH = "5e7e17cdd0166af5a2d8f43133d9ce3ce9253d1fdb5160a0cfd515564f98d061"

        private val INTERNAL_HOSTER_NAMES = arrayOf(
            "Default 0 (Cr/vrv)",
            "Default",
            "Luf-mp4 (Gogo)",
            "S-mp4",
            "Sak",
            "Uv-mp4",
        )

        private val EXTERNAL_HOSTER_NAMES = arrayOf(
            "AK",
            "Fm-Hls",
            "Mp4",
            "Ok",
            "Sw",
            "Vid-mp4 (Vidstreaming)",
            "Yt-mp4",
        )

        private const val PREF_HOSTER_BLACKLIST_KEY = "pref_hoster_blacklist"
        private val PREF_HOSTER_BLACKLIST_ENTRY_VALUES = INTERNAL_HOSTER_NAMES.map {
            it.lowercase().substringBefore(" (")
        }.toTypedArray()

        private const val PREF_ALT_HOSTER_BLACKLIST_KEY = "pref_alt_hoster_blacklist"
        private val PREF_ALT_HOSTER_BLACKLIST_ENTRY_VALUES = EXTERNAL_HOSTER_NAMES.map {
            it.lowercase().substringBefore(" (")
        }.toTypedArray()

        private const val PREF_REMOVE_RAW_KEY = "pref_remove_raw"
        private const val PREF_REMOVE_RAW_DEFAULT = true

        // Names as they appear in video list
        private val HOSTER_NAMES = arrayOf(
            "AK", "Crunchyroll", "Default", "Fm-Hls", "Luf-mp4",
            "Mp4", "Ok", "S-mp4", "Sak", "Sw", "Uv-mp4",
            "Vidstreaming", "Vrv", "Yt-mp4",
        )

        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_ENTRIES = arrayOf("Site Default") +
            HOSTER_NAMES
        private val PREF_SERVER_ENTRY_VALUES = arrayOf("site_default") +
            HOSTER_NAMES.map { it.lowercase() }
        private const val PREF_SERVER_DEFAULT = "site_default"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf(
            "2160p",
            "1440p",
            "1080p",
            "720p",
            "480p",
            "360p",
            "240p",
            "80p",
        )
        private val PREF_QUALITY_ENTRY_VALUES = PREF_QUALITY_ENTRIES.map {
            it.substringBefore("p")
        }.toTypedArray()
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_DEFAULT = "romaji"

        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_DEFAULT = "sub"

        private const val PREF_USE_HOSTER_NAMES_KEY = "use_host_prefix"
        private const val PREF_USE_HOSTER_NAMES_DEFAULT = false
    }

    // ============================== Settings ==============================

    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Video Server"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRY_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_BLACKLIST_KEY
            title = "Internal hoster blacklist"
            entries = INTERNAL_HOSTER_NAMES
            entryValues = PREF_HOSTER_BLACKLIST_ENTRY_VALUES
            setDefaultValue(emptySet<String>())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_ALT_HOSTER_BLACKLIST_KEY
            title = "External hoster blacklist"
            entries = EXTERNAL_HOSTER_NAMES
            entryValues = PREF_ALT_HOSTER_BLACKLIST_ENTRY_VALUES
            setDefaultValue(emptySet<String>())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
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
            key = PREF_TITLE_STYLE_KEY
            title = "Preferred Title Style"
            entries = arrayOf("Romaji", "English", "Native")
            entryValues = arrayOf("romaji", "eng", "native")
            setDefaultValue(PREF_TITLE_STYLE_DEFAULT)
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
            title = "Prefer subs or dubs?"
            entries = arrayOf("Subs", "Dubs")
            entryValues = arrayOf("sub", "dub")
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
            key = PREF_REMOVE_RAW_KEY
            title = "Attempt to filter out raw"
            setDefaultValue(PREF_REMOVE_RAW_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_HOSTER_NAMES_KEY
            title = "Use names of video hoster"
            setDefaultValue(PREF_USE_HOSTER_NAMES_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)
    }

    private val SharedPreferences.subPref
        get() = getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.titleStyle
        get() = getString(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)!!

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.prefServer
        get() = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

    private val SharedPreferences.getHosterBlacklist
        get() = getStringSet(PREF_HOSTER_BLACKLIST_KEY, emptySet())!!

    private val SharedPreferences.getAltHosterBlacklist
        get() = getStringSet(PREF_ALT_HOSTER_BLACKLIST_KEY, emptySet())!!

    private val SharedPreferences.removeRaw
        get() = getBoolean(PREF_REMOVE_RAW_KEY, PREF_REMOVE_RAW_DEFAULT)

    private val SharedPreferences.useHosterName
        get() = getBoolean(PREF_USE_HOSTER_NAMES_KEY, PREF_USE_HOSTER_NAMES_DEFAULT)
}
