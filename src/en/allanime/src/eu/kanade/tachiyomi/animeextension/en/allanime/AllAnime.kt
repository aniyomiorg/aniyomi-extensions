package eu.kanade.tachiyomi.animeextension.en.allanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.AllAnimeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.gogostreamextractor.GogoStreamExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AllAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AllAnime"

    override val baseUrl by lazy { preferences.baseUrl }

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("type", "anime")
                put("size", PAGE_SIZE)
                put("dateRange", 7)
                put("page", page)
            }
            put("query", POPULAR_QUERY)
        }
        return buildPost(data)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<PopularResult>()

        val animeList = parsed.data.queryPopular.recommendations.filter { it.anyCard != null }.map {
            SAnime.create().apply {
                title = when (preferences.titleStyle) {
                    "romaji" -> it.anyCard!!.name
                    "eng" -> it.anyCard!!.englishName ?: it.anyCard.name
                    else -> it.anyCard!!.nativeName ?: it.anyCard.name
                }
                thumbnail_url = it.anyCard.thumbnail
                url = "${it.anyCard._id}<&sep>${it.anyCard.slugTime ?: ""}<&sep>${it.anyCard.name.slugify()}"
            }
        }

        return AnimesPage(animeList, animeList.size == PAGE_SIZE)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                putJsonObject("search") {
                    put("allowAdult", false)
                    put("allowUnknown", false)
                }
                put("limit", PAGE_SIZE)
                put("page", page)
                put("translationType", preferences.subPref)
                put("countryOrigin", "ALL")
            }
            put("query", SEARCH_QUERY)
        }
        return buildPost(data)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnime(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filters = AllAnimeFilters.getSearchParameters(filters)

        return if (query.isNotEmpty()) {
            val data = buildJsonObject {
                putJsonObject("variables") {
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
                put("query", SEARCH_QUERY)
            }
            buildPost(data)
        } else {
            val data = buildJsonObject {
                putJsonObject("variables") {
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
                put("query", SEARCH_QUERY)
            }
            buildPost(data)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AllAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("_id", anime.url.split("<&sep>").first())
            }
            put("query", DETAILS_QUERY)
        }
        return buildPost(data)
    }

    override fun getAnimeUrl(anime: SAnime): String {
        val (id, time, slug) = anime.url.split("<&sep>")
        val slugTime = if (time.isNotEmpty()) "-st-$time" else time
        val siteUrl = preferences.siteUrl

        return "$siteUrl/anime/$id/$slug$slugTime"
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

    override fun episodeListRequest(anime: SAnime): Request {
        val data = buildJsonObject {
            putJsonObject("variables") {
                put("_id", anime.url.split("<&sep>").first())
            }
            put("query", EPISODES_QUERY)
        }
        return buildPost(data)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val subPref = preferences.subPref
        val medias = response.parseAs<SeriesResult>()

        val episodesDetail = if (subPref == "sub") {
            medias.data.show.availableEpisodesDetail.sub!!
        } else {
            medias.data.show.availableEpisodesDetail.dub!!
        }

        return episodesDetail.map { ep ->
            val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")

            SEpisode.create().apply {
                episode_number = ep.toFloatOrNull() ?: 0F
                name = "Episode $numName ($subPref)"
                url = json.encodeToString(
                    buildJsonObject {
                        putJsonObject("variables") {
                            put("showId", medias.data.show._id)
                            put("translationType", subPref)
                            put("episodeString", ep)
                        }
                        put("query", STREAMS_QUERY)
                    },
                )
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val payload = episode.url
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val siteUrl = preferences.siteUrl
        val postHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", baseUrl.toHttpUrl().host)
            add("Origin", siteUrl)
            add("Referer", "$baseUrl/")
        }.build()

        return POST("$baseUrl/api", headers = postHeaders, body = payload)
    }

    private val allAnimeExtractor by lazy { AllAnimeExtractor(client, headers, preferences.siteUrl) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).await()

        val videoJson = response.parseAs<EpisodeResult>()
        val videoList = mutableListOf<Pair<Video, Float>>()
        val serverList = mutableListOf<Server>()

        val hosterSelection = preferences.getHosters
        val altHosterSelection = preferences.getAltHosters

        // list of alternative hosters
        val mappings = listOf(
            "vidstreaming" to listOf("vidstreaming", "https://gogo", "playgo1.cc", "playtaku"),
            "doodstream" to listOf("dood"),
            "okru" to listOf("ok.ru"),
            "mp4upload" to listOf("mp4upload.com"),
            "streamlare" to listOf("streamlare.com"),
        )

        videoJson.data.episode.sourceUrls.forEach { video ->
            val videoUrl = video.sourceUrl.decryptSource()

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
                    serverList.add(Server(videoUrl, "player@${video.sourceName}", video.priority))
                }
                matchingMapping != null -> {
                    serverList.add(Server(videoUrl, matchingMapping.first, video.priority))
                }
            }
        }

        videoList.addAll(
            serverList.parallelCatchingFlatMap { server ->
                val sName = server.sourceName
                when {
                    sName.startsWith("internal ") -> {
                        allAnimeExtractor.videoFromUrl(server.sourceUrl, server.sourceName)
                    }
                    sName.startsWith("player@") -> {
                        val endPoint = client.newCall(GET("${preferences.siteUrl}/getVersion")).await()
                            .parseAs<AllAnimeExtractor.VersionResponse>()
                            .episodeIframeHead

                        val videoHeaders = headers.newBuilder().apply {
                            add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                            add("Host", server.sourceUrl.toHttpUrl().host)
                            add("Referer", "$endPoint/")
                        }.build()

                        listOf(
                            Video(
                                server.sourceUrl,
                                "Original (player ${server.sourceName.substringAfter("player@")})",
                                server.sourceUrl,
                                headers = videoHeaders,
                            ),
                        )
                    }
                    sName == "vidstreaming" -> {
                        gogoStreamExtractor.videosFromUrl(server.sourceUrl.replace(Regex("^//"), "https://"))
                    }
                    sName == "dood" -> {
                        doodExtractor.videosFromUrl(server.sourceUrl)
                    }
                    sName == "okru" -> {
                        okruExtractor.videosFromUrl(server.sourceUrl)
                    }
                    sName == "mp4upload" -> {
                        mp4uploadExtractor.videosFromUrl(server.sourceUrl, headers)
                    }
                    sName == "streamlare" -> {
                        streamlareExtractor.videosFromUrl(server.sourceUrl)
                    }
                    else -> emptyList()
                }.let { it.map { v -> Pair(v, server.priority) } }
            },
        )

        return prioritySort(videoList)
    }

    // ============================= Utilities ==============================

    private fun String.decryptSource(): String {
        return if (this.startsWith("-")) {
            this.substringAfterLast('-').chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray().map {
                    (it.toInt() xor 56).toChar()
                }.joinToString("")
        } else {
            this
        }
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

    private fun buildPost(dataObject: JsonObject): Request {
        val payload = json.encodeToString(dataObject)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val siteUrl = preferences.siteUrl
        val postHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Length", payload.contentLength().toString())
            add("Content-Type", payload.contentType().toString())
            add("Host", baseUrl.toHttpUrl().host)
            add("Origin", siteUrl)
            add("Referer", "$baseUrl/")
        }.build()

        return POST("$baseUrl/api", headers = postHeaders, body = payload)
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
        val parsed = response.parseAs<SearchResult>()

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
            "doodstream",
        )

        private const val PREF_SITE_DOMAIN_KEY = "preferred_site_domain"
        private const val PREF_SITE_DOMAIN_DEFAULT = "https://allanime.to"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://api.allanime.day"

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
    }

    // ============================== Settings ==============================

    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SITE_DOMAIN_KEY
            title = "Preferred domain for site (requires app restart)"
            entries = arrayOf("allmanga.to")
            entryValues = arrayOf("https://allmanga.to")
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
            entries = arrayOf("api.allanime.day")
            entryValues = arrayOf("https://api.allanime.day")
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
