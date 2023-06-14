package eu.kanade.tachiyomi.animeextension.en.allanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.AllAnimeExtractor
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AllAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AllAnime"

    override val baseUrl by lazy { preferences.baseUrl }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val variables = buildJsonObject {
            put("type", "anime")
            put("size", PAGE_SIZE)
            put("dateRange", 7)
            put("page", page)
        }
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$POPULAR_QUERY", headers = headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<PopularResult>(response.body.string())
        val animeList = mutableListOf<SAnime>()

        parsed.data.queryPopular.recommendations.forEach {
            if (it.anyCard != null) {
                animeList.add(
                    SAnime.create().apply {
                        title = when (preferences.titleStyle) {
                            "romaji" -> it.anyCard.name
                            "eng" -> it.anyCard.englishName ?: it.anyCard.name
                            else -> it.anyCard.nativeName ?: it.anyCard.name
                        }
                        thumbnail_url = it.anyCard.thumbnail
                        url = "${it.anyCard._id}<&sep>${it.anyCard.slugTime ?: ""}<&sep>${it.anyCard.name.slugify()}"
                    },
                )
            }
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val variables = buildJsonObject {
            putJsonObject("search") {
                put("allowAdult", false)
                put("allowUnknown", false)
            }
            put("limit", PAGE_SIZE)
            put("page", page)
            put("translationType", preferences.subPref)
            put("countryOrigin", "ALL")
        }
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$SEARCH_QUERY", headers = headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnime(response)

    // =============================== Search ===============================

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AllAnimeFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AllAnimeFilters.FilterSearchParams): Request {
        return if (query.isNotEmpty()) {
            val variables = buildJsonObject {
                putJsonObject("search") {
                    put("query", query)
                    put("allowAdult", false)
                    put("allowUnknown", false)
                }
                put("limit", PAGE_SIZE)
                put("page", page)
                put("translationType", preferences.subPref)
                put("countryOrigin", "ALL")
            }
            GET("$baseUrl/allanimeapi?variables=$variables&query=$SEARCH_QUERY", headers = headers)
        } else {
            val variables = buildJsonObject {
                putJsonObject("search") {
                    put("allowAdult", false)
                    put("allowUnknown", false)
                    if (filters.season != "all") put("season", filters.season)
                    if (filters.releaseYear != "all") put("year", filters.releaseYear.toInt())
                    if (filters.genres != "all") {
                        put("genres", json.decodeFromString(filters.genres))
                        put("excludeGenres", buildJsonArray { })
                    }
                    if (filters.types != "all") put("types", json.decodeFromString(filters.types))
                    if (filters.sortBy != "update") put("sortBy", filters.sortBy)
                }
                put("limit", PAGE_SIZE)
                put("page", page)
                put("translationType", preferences.subPref)
                put("countryOrigin", filters.origin)
            }
            GET("$baseUrl/allanimeapi?variables=$variables&query=$SEARCH_QUERY", headers = headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AllAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequestInternal(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    private fun animeDetailsRequestInternal(anime: SAnime): Request {
        val variables = buildJsonObject {
            put("_id", anime.url.split("<&sep>").first())
        }
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$DETAILS_QUERY", headers = headers)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (id, time, slug) = anime.url.split("<&sep>")
        val slugTime = if (time.isNotEmpty()) "-st-$time" else time
        val siteUrl = preferences.siteUrl

        return GET("$siteUrl/anime/$id/$slug$slugTime")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val show = json.decodeFromString<DetailsResult>(response.body.string()).data.show

        return SAnime.create().apply {
            genre = show.genres?.joinToString(separator = ", ") ?: ""
            status = parseStatus(show.status)
            author = show.studios?.firstOrNull()
            description = buildString {
                append(
                    Jsoup.parse(
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

    override fun episodeListRequest(anime: SAnime): Request {
        val variables = buildJsonObject {
            put("_id", anime.url.split("<&sep>").first())
        }
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$EPISODES_QUERY", headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val subPref = preferences.subPref
        val medias = json.decodeFromString<SeriesResult>(response.body.string())

        val episodesDetail = if (subPref == "sub") {
            medias.data.show.availableEpisodesDetail.sub!!
        } else {
            medias.data.show.availableEpisodesDetail.dub!!
        }

        return episodesDetail.map { ep ->
            val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")
            val variables = buildJsonObject {
                put("showId", medias.data.show._id)
                put("translationType", subPref)
                put("episodeString", ep)
            }

            SEpisode.create().apply {
                episode_number = ep.toFloatOrNull() ?: 0F
                name = "Episode $numName ($subPref)"
                setUrlWithoutDomain("/allanimeapi?variables=$variables&query=$STREAMS_QUERY")
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val videoJson = json.decodeFromString<EpisodeResult>(response.body.string())
        val videoList = mutableListOf<Pair<Video, Float>>()
        val serverList = mutableListOf<Server>()

        val hosterSelection = preferences.getHosters
        val altHosterSelection = preferences.getAltHosters

        // list of alternative hosters
        val mappings = listOf(
            "streamsb" to listOf("streamsb"),
            "vidstreaming" to listOf("vidstreaming", "https://gogo", "playgo1.cc"),
            "doodstream" to listOf("dood"),
            "okru" to listOf("ok.ru"),
            "mp4upload" to listOf("mp4upload.com"),
            "streamlare" to listOf("streamlare.com"),
        )

        videoJson.data.episode.sourceUrls.forEach { video ->
            val videoUrl = if (video.sourceUrl.startsWith("#")) {
                hexToText(video.sourceUrl.substringAfter("#"))
            } else {
                video.sourceUrl
            }

            val matchingMapping = mappings.firstOrNull { (altHoster, urlMatches) ->
                altHosterSelection.contains(altHoster) && videoUrl.containsAny(urlMatches)
            }

            when {
                videoUrl.startsWith("/apivtwo/") && INTERAL_HOSTER_NAMES.any {
                    Regex("""\b${it.lowercase()}\b""").find(video.sourceName.lowercase()) != null &&
                        hosterSelection.contains(it.lowercase())
                } -> {
                    serverList.add(Server(videoUrl, "internal ${video.sourceName}", video.priority))
                }
                altHosterSelection.contains("player") && video.type == "player" -> {
                    serverList.add(Server(videoUrl, "player", video.priority))
                }
                matchingMapping != null -> {
                    serverList.add(Server(videoUrl, matchingMapping.first, video.priority))
                }
            }
        }

        videoList.addAll(
            serverList.parallelMap { server ->
                runCatching {
                    val sName = server.sourceName
                    when {
                        sName.startsWith("internal ") -> {
                            val extractor = AllAnimeExtractor(client)
                            runCatching {
                                extractor.videoFromUrl(server.sourceUrl, server.sourceName)
                            }.getOrNull()?.map {
                                Pair(it, server.priority)
                            } ?: emptyList()
                        }
                        sName == "player" -> {
                            listOf(
                                Video(
                                    server.sourceUrl,
                                    "Original (player ${server.sourceName})",
                                    server.sourceUrl,
                                ) to server.priority,
                            )
                        }
                        sName == "streamsb" -> {
                            val extractor = StreamSBExtractor(client)
                            runCatching {
                                extractor.videosFromUrl(server.sourceUrl, headers)
                            }.getOrNull()?.map {
                                Pair(it, server.priority)
                            } ?: emptyList()
                        }
                        sName == "gogo" -> {
                            val extractor = VidstreamingExtractor(client, json)
                            runCatching {
                                extractor.videosFromUrl(server.sourceUrl.replace(Regex("^//"), "https://"))
                            }.getOrNull()?.map {
                                Pair(it, server.priority)
                            } ?: emptyList()
                        }
                        sName == "dood" -> {
                            val extractor = DoodExtractor(client)
                            runCatching {
                                extractor.videosFromUrl(server.sourceUrl)
                            }.getOrNull()?.map {
                                Pair(it, server.priority)
                            } ?: emptyList()
                        }
                        sName == "okru" -> {
                            val extractor = OkruExtractor(client)
                            runCatching {
                                extractor.videosFromUrl(server.sourceUrl)
                            }.getOrNull()?.map {
                                Pair(it, server.priority)
                            } ?: emptyList()
                        }
                        sName == "mp4upload" -> {
                            val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                            runCatching {
                                Mp4uploadExtractor(client).getVideoFromUrl(server.sourceUrl, headers)
                            }.getOrNull()?.map {
                                Pair(it, server.priority)
                            } ?: emptyList()
                        }
                        sName == "streamlare" -> {
                            val extractor = StreamlareExtractor(client)
                            runCatching {
                                extractor.videosFromUrl(server.sourceUrl)
                            }.getOrNull()?.map {
                                Pair(it, server.priority)
                            } ?: emptyList()
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        if (videoList.isEmpty()) {
            throw Exception("No videos found")
        }

        return prioritySort(videoList)
    }

    // ============================= Utilities ==============================

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

    data class Server(
        val sourceUrl: String,
        val sourceName: String,
        val priority: Float,
    )

    private fun parseStatus(string: String?): Int {
        return when (string) {
            "Releasing" -> SAnime.ONGOING
            "Finished" -> SAnime.COMPLETED
            "Not Yet Released" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun String.slugify(): String {
        return this.replace("""[^a-zA-Z0-9]""".toRegex(), "-")
            .replace("""-{2,}""".toRegex(), "-")
            .lowercase()
    }

    private fun parseAnime(response: Response): AnimesPage {
        val parsed = json.decodeFromString<SearchResult>(response.body.string())

        val animeList = parsed.data.shows.edges.map { ani ->
            SAnime.create().apply {
                title = when (preferences.titleStyle) {
                    "romaji" -> ani.name
                    "eng" -> ani.englishName ?: ani.name
                    else -> ani.nativeName ?: ani.name
                }
                thumbnail_url = ani.thumbnail
                url = "${ani._id}<&sep>${ani.slugTime ?: ""}<&sep>${ani.name.slugify()}"
            }
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    private fun String.containsAny(keywords: List<String>): Boolean {
        return keywords.any { this.contains(it) }
    }

    private fun hexToText(inputString: String): String {
        return inputString.chunked(2).map {
            it.toInt(16).toByte()
        }.toByteArray().toString(Charsets.UTF_8)
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private const val PAGE_SIZE = 26 // number of items to retrieve when calling API
        private val INTERAL_HOSTER_NAMES = arrayOf(
            "Default", "Ac", "Ak", "Kir", "Rab", "Luf-mp4",
            "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls",
        )

        private val ALT_HOSTER_NAMES = arrayOf(
            "player",
            "vidstreaming",
            "okru",
            "mp4upload",
            "streamlare",
            "streamsb",
            "doodstream",
        )

        private const val PREF_SITE_DOMAIN_KEY = "preferred_site_domain"
        private const val PREF_SITE_DOMAIN_DEFAULT = "https://allanime.to"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://api.allanime.to"

        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_ENTRIES = arrayOf("Site Default") +
            INTERAL_HOSTER_NAMES.sliceArray(1 until INTERAL_HOSTER_NAMES.size) +
            ALT_HOSTER_NAMES
        private val PREF_SERVER_ENTRY_VALUES = arrayOf("site_default") +
            INTERAL_HOSTER_NAMES.sliceArray(1 until INTERAL_HOSTER_NAMES.size).map {
                it.lowercase()
            }.toTypedArray() +
            ALT_HOSTER_NAMES
        private const val PREF_SERVER_DEFAULT = "site_default"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val PREF_HOSTER_ENTRY_VALUES = INTERAL_HOSTER_NAMES.map {
            it.lowercase()
        }.toTypedArray()
        private val PREF_HOSTER_DEFAULT = setOf("default", "ac", "ak", "kir", "luf-mp4", "si-hls", "s-mp4", "ac-hls")

        private const val PREF_ALT_HOSTER_KEY = "alt_hoster_selection"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf(
            "1080p",
            "720p",
            "480p",
            "360p",
            "240p",
            "80p",
            "1440p (okru only)",
            "2160p (okru only)",
        )
        private val PREF_QUALITY_ENTRY_VALUES = PREF_QUALITY_ENTRIES.map {
            it.substringBefore("p")
        }.toTypedArray()
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_DEFAULT = "romaji"

        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_DEFAULT = "sub"
    }

    // ============================== Settings ==============================

    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SITE_DOMAIN_KEY
            title = "Preferred domain for site (requires app restart)"
            entries = arrayOf("allanime.to", "allanime.co")
            entryValues = arrayOf("https://allanime.to", "https://allanime.co")
            setDefaultValue(PREF_SITE_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("api.allanime.to", "api.allanime.co")
            entryValues = arrayOf("https://api.allanime.to", "https://api.allanime.co")
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
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = INTERAL_HOSTER_NAMES
            entryValues = PREF_HOSTER_ENTRY_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_ALT_HOSTER_KEY
            title = "Enable/Disable Alternative Hosts"
            entries = ALT_HOSTER_NAMES
            entryValues = ALT_HOSTER_NAMES
            setDefaultValue(ALT_HOSTER_NAMES.toSet())

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
    }

    private val SharedPreferences.subPref
        get() = getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.baseUrl
        get() = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    private val SharedPreferences.siteUrl
        get() = getString(PREF_SITE_DOMAIN_KEY, PREF_SITE_DOMAIN_DEFAULT)!!

    private val SharedPreferences.titleStyle
        get() = getString(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)!!

    private val SharedPreferences.quality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.prefServer
        get() = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

    private val SharedPreferences.getHosters
        get() = getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

    private val SharedPreferences.getAltHosters
        get() = getStringSet(PREF_ALT_HOSTER_KEY, ALT_HOSTER_NAMES.toSet())!!
}
