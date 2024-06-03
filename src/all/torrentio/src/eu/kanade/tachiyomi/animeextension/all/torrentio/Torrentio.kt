package eu.kanade.tachiyomi.animeextension.all.torrentio

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.EpisodeList
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.GetPopularTitlesResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.GetUrlTitleDetailsResponse
import eu.kanade.tachiyomi.animeextension.all.torrentio.dto.StreamDataTorrent
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Torrentio : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Torrentio (Torrent / Debrid)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // ============================== JustWatch API Request ===================
    private fun makeGraphQLRequest(query: String, variables: String): Request {
        val requestBody = """
        {"query": "${query.replace("\n", "")}", "variables": $variables}
        """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaType())

        return POST("https://apis.justwatch.com/graphql", headers = headers, body = requestBody)
    }

    // ============================== JustWatch Api Query ======================
    private fun justWatchQuery(): String {
        return """
            query GetPopularTitles(
              ${"$"}country: Country!,
              ${"$"}first: Int!,
              ${"$"}language: Language!,
              ${"$"}offset: Int,
              ${"$"}searchQuery: String,
              ${"$"}packages: [String!]!,
              ${"$"}objectTypes: [ObjectType!]!,
              ${"$"}popularTitlesSortBy: PopularTitlesSorting!,
              ${"$"}releaseYear: IntFilter
            ) {
              popularTitles(
                country: ${"$"}country
                first: ${"$"}first
                offset: ${"$"}offset
                sortBy: ${"$"}popularTitlesSortBy
                filter: {
                  objectTypes: ${"$"}objectTypes,
                  searchQuery: ${"$"}searchQuery,
                  packages: ${"$"}packages,
                  genres: [],
                  excludeGenres: [],
                  releaseYear: ${"$"}releaseYear
                }
              ) {
                edges {
                  node {
                    id
                    objectType
                    content(country: ${"$"}country, language: ${"$"}language) {
                      fullPath
                      title
                      shortDescription
                      externalIds {
                        imdbId
                      }
                      posterUrl
                      genres {
                        translation(language: ${"$"}language)
                      }
                      credits {
                        name
                        role
                      }
                    }
                  }
                }
                pageInfo {
                  hasPreviousPage
                  hasNextPage
                }
              }
            }
        """.trimIndent()
    }

    private fun parseSearchJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val popularTitlesResponse = json.decodeFromString<GetPopularTitlesResponse>(jsonData)

        val edges = popularTitlesResponse.data?.popularTitles?.edges.orEmpty()
        val hasNextPage = popularTitlesResponse.data?.popularTitles?.pageInfo?.hasNextPage ?: false

        val metaList = edges
            .mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                val content = node.content ?: return@mapNotNull null

                SAnime.create().apply {
                    url = "${content.externalIds?.imdbId ?: ""},${node.objectType ?: ""},${content.fullPath ?: ""}"
                    title = content.title ?: ""
                    thumbnail_url = "https://images.justwatch.com${content.posterUrl?.replace("{profile}", "s276")?.replace("{format}", "webp")}"
                    description = content.shortDescription ?: ""
                    val genresList = content.genres?.mapNotNull { it.translation }.orEmpty()
                    genre = genresList.joinToString()

                    val directors = content.credits?.filter { it.role == "DIRECTOR" }?.mapNotNull { it.name }
                    author = directors?.joinToString()
                    val actors = content.credits?.filter { it.role == "ACTOR" }?.take(4)?.mapNotNull { it.name }
                    artist = actors?.joinToString()
                    initialized = true
                }
            }

        return AnimesPage(metaList, hasNextPage)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return searchAnimeRequest(page, "", AnimeFilterList())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData) }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val country = preferences.getString(PREF_REGION_KEY, PREF_REGION_DEFAULT)
        val language = preferences.getString(PREF_JW_LANG_KEY, PREF_JW_LANG_DEFAULT)
        val perPage = 40
        val packages = ""
        val year = 0
        val objectTypes = ""
        val variables = """
            {
              "first": $perPage,
              "offset": ${(page - 1) * perPage},
              "platform": "WEB",
              "country": "$country",
              "language": "$language",
              "searchQuery": "${query.replace(searchQueryRegex, "").trim()}",
              "packages": [$packages],
              "objectTypes": [$objectTypes],
              "popularTitlesSortBy": "TRENDING",
              "releaseYear": {
                "min": $year,
                "max": $year
              }
            }
        """.trimIndent()

        return makeGraphQLRequest(justWatchQuery(), variables)
    }

    private val searchQueryRegex by lazy {
        Regex("[^A-Za-z0-9 ]")
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // override suspend fun getAnimeDetails(anime: SAnime): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val query = """
            query GetUrlTitleDetails(${"$"}fullPath: String!, ${"$"}country: Country!, ${"$"}language: Language!) {
              urlV2(fullPath: ${"$"}fullPath) {
                node {
                  ...TitleDetails
                }
              }
            }

            fragment TitleDetails on Node {
              ... on MovieOrShowOrSeason {
                id
                objectType
                content(country: ${"$"}country, language: ${"$"}language) {
                  title
                  shortDescription
                  externalIds {
                    imdbId
                  }
                  posterUrl
                  genres {
                    translation(language: ${"$"}language)
                  }
                }
              }
            }
        """.trimIndent()

        val country = preferences.getString(PREF_REGION_KEY, PREF_REGION_DEFAULT)
        val language = preferences.getString(PREF_JW_LANG_KEY, PREF_JW_LANG_DEFAULT)
        val variables = """
            {
              "fullPath": "${anime.url.split(',').last()}",
              "country": "$country",
              "language": "$language"
            }
        """.trimIndent()

        val content = runCatching {
            json.decodeFromString<GetUrlTitleDetailsResponse>(client.newCall(makeGraphQLRequest(query, variables)).execute().body.string())
        }.getOrNull()?.data?.urlV2?.node?.content

        anime.title = content?.title ?: ""
        anime.thumbnail_url = "https://images.justwatch.com${content?.posterUrl?.replace("{profile}", "s718")?.replace("{format}", "webp")}"
        anime.description = content?.shortDescription ?: ""
        val genresList = content?.genres?.mapNotNull { it.translation }.orEmpty()
        anime.genre = genresList.joinToString()

        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val parts = anime.url.split(",")
        val type = parts[1].lowercase()
        val imdbId = parts[0]
        return GET("https://cinemeta-live.strem.io/meta/$type/$imdbId.json")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val episodeList = json.decodeFromString<EpisodeList>(responseString)
        return when (episodeList.meta?.type) {
            "show" -> {
                episodeList.meta.videos
                    ?.let { videos ->
                        if (preferences.getBoolean(UPCOMING_EP_KEY, UPCOMING_EP_DEFAULT)) { videos } else { videos.filter { video -> (video.firstAired?.let { parseDate(it) } ?: 0L) <= System.currentTimeMillis() } }
                    }
                    ?.map { video ->
                        SEpisode.create().apply {
                            episode_number = "${video.season}.${video.number}".toFloat()
                            url = "/stream/series/${video.id}.json"
                            date_upload = video.firstAired?.let { parseDate(it) } ?: 0L
                            name = "S${video.season.toString().trim()}:E${video.number} - ${video.name}"
                            scanlator = (video.firstAired?.let { parseDate(it) } ?: 0L)
                                .takeIf { it > System.currentTimeMillis() }
                                ?.let { "Upcoming" }
                                ?: ""
                        }
                    }
                    ?.sortedWith(
                        compareBy<SEpisode> { it.name.substringAfter("S").substringBefore(":").toInt() }
                            .thenBy { it.name.substringAfter("E").substringBefore(" -").toInt() },
                    )
                    .orEmpty().reversed()
            }

            "movie" -> {
                // Handle movie response
                listOf(
                    SEpisode.create().apply {
                        episode_number = 1.0F
                        url = "/stream/movie/${episodeList.meta.id}.json"
                        name = "Movie"
                    },
                ).reversed()
            }

            else -> emptyList()
        }
    }
    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val mainURL = buildString {
            append("$baseUrl/")

            val appendQueryParam: (String, Set<String>?) -> Unit = { key, values ->
                values?.takeIf { it.isNotEmpty() }?.let {
                    append("$key=${it.filter(String::isNotBlank).joinToString(",")}|")
                }
            }

            appendQueryParam("providers", preferences.getStringSet(PREF_PROVIDER_KEY, PREF_PROVIDERS_DEFAULT))
            appendQueryParam("language", preferences.getStringSet(PREF_LANG_KEY, PREF_LANG_DEFAULT))
            appendQueryParam("qualityfilter", preferences.getStringSet(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT))

            val sortKey = preferences.getString(PREF_SORT_KEY, "quality")
            appendQueryParam("sort", sortKey?.let { setOf(it) })

            val token = preferences.getString(PREF_TOKEN_KEY, null)
            val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

            when {
                token.isNullOrBlank() && debridProvider != "none" -> {
                    handler.post {
                        context.let {
                            Toast.makeText(
                                it,
                                "Kindly input the debrid token in the extension settings.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    throw UnsupportedOperationException()
                }
                !token.isNullOrBlank() && debridProvider != "none" -> append("$debridProvider=$token|")
            }
            append(episode.url)
        }.removeSuffix("|")
        return GET(mainURL)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body.string()
        val streamList = json.decodeFromString<StreamDataTorrent>(responseString)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "none")

        val animeTrackers = """http://nyaa.tracker.wf:7777/announce,
            http://anidex.moe:6969/announce,http://tracker.anirena.com:80/announce,
            udp://tracker.uw0.xyz:6969/announce,
            http://share.camoe.cn:8080/announce,
            http://t.nyaatracker.com:80/announce,
            udp://47.ip-51-68-199.eu:6969/announce,
            udp://9.rarbg.me:2940,
            udp://9.rarbg.to:2820,
            udp://exodus.desync.com:6969/announce,
            udp://explodie.org:6969/announce,
            udp://ipv4.tracker.harry.lu:80/announce,
            udp://open.stealth.si:80/announce,
            udp://opentor.org:2710/announce,
            udp://opentracker.i2p.rocks:6969/announce,
            udp://retracker.lanta-net.ru:2710/announce,
            udp://tracker.cyberia.is:6969/announce,
            udp://tracker.dler.org:6969/announce,
            udp://tracker.ds.is:6969/announce,
            udp://tracker.internetwarriors.net:1337,
            udp://tracker.openbittorrent.com:6969/announce,
            udp://tracker.opentrackr.org:1337/announce,
            udp://tracker.tiny-vps.com:6969/announce,
            udp://tracker.torrent.eu.org:451/announce,
            udp://valakas.rollo.dnsabr.com:2710/announce,
            udp://www.torrent.eu.org:451/announce
        """.trimIndent()

        return streamList.streams?.map { stream ->
            val urlOrHash =
                if (debridProvider == "none") {
                    val trackerList = animeTrackers.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("&tr=")
                    "magnet:?xt=urn:btih:${stream.infoHash}&dn=${stream.infoHash}&tr=$trackerList&index=${stream.fileIdx}"
                } else stream.url ?: ""
            Video(urlOrHash, ((stream.name?.replace("Torrentio\n", "") ?: "") + "\n" + stream.title), urlOrHash)
        }.orEmpty()
    }

    override fun List<Video>.sort(): List<Video> {
        val isDub = preferences.getBoolean(IS_DUB_KEY, IS_DUB_DEFAULT)
        val isEfficient = preferences.getBoolean(IS_EFFICIENT_KEY, IS_EFFICIENT_DEFAULT)

        return sortedWith(
            compareBy(
                { Regex("\\[(.+?) download]").containsMatchIn(it.quality) },
                { isDub && !it.quality.contains("dubbed", true) },
                { isEfficient && !arrayOf("hevc", "265", "av1").any { q -> it.quality.contains(q, true) } },
            ),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Debrid provider
        ListPreference(screen.context).apply {
            key = PREF_DEBRID_KEY
            title = "Debrid Provider"
            entries = PREF_DEBRID_ENTRIES
            entryValues = PREF_DEBRID_VALUES
            setDefaultValue("none")
            summary = "Choose 'None' for Torrent. If you select a Debrid provider, enter your token key. No token key is needed if 'None' is selected."

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        // Token
        EditTextPreference(screen.context).apply {
            key = PREF_TOKEN_KEY
            title = "Token"
            setDefaultValue(PREF_TOKEN_DEFAULT)
            summary = PREF_TOKEN_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).trim().ifBlank { PREF_TOKEN_DEFAULT }
                    Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)

        // Provider
        MultiSelectListPreference(screen.context).apply {
            key = PREF_PROVIDER_KEY
            title = "Enable/Disable Providers"
            entries = PREF_PROVIDERS
            entryValues = PREF_PROVIDERS_VALUE
            setDefaultValue(PREF_PROVIDERS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        // Exclude Qualities
        MultiSelectListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Exclude Qualities/Resolutions"
            entries = PREF_QUALITY
            entryValues = PREF_QUALITY_VALUE
            setDefaultValue(PREF_QUALITY_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        // Priority foreign language
        MultiSelectListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Priority foreign language"
            entries = PREF_LANG
            entryValues = PREF_LANG_VALUE
            setDefaultValue(PREF_LANG_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        // Sorting
        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Sorting"
            entries = PREF_SORT_ENTRIES
            entryValues = PREF_SORT_VALUES
            setDefaultValue("quality")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = UPCOMING_EP_KEY
            title = "Show Upcoming Episodes"
            setDefaultValue(UPCOMING_EP_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_DUB_KEY
            title = "Dubbed Video Priority"
            setDefaultValue(IS_DUB_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = IS_EFFICIENT_KEY
            title = "Efficient Video Priority"
            setDefaultValue(IS_EFFICIENT_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Codec: (HEVC / x265)  & AV1. High-quality video with less data usage."
        }.also(screen::addPreference)

        // JustWatch Settings

        // Region
        ListPreference(screen.context).apply {
            key = PREF_REGION_KEY
            title = "Catalogue Region"
            entries = PREF_REGION_ENTRIES
            entryValues = PREF_REGION_VALUES
            setDefaultValue(PREF_REGION_DEFAULT)
            summary = "Region based catalogue recommendation."

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        // Poster and Titles Language
        ListPreference(screen.context).apply {
            key = PREF_JW_LANG_KEY
            title = "Poster and Titles Language"
            entries = PREF_JW_LANG_ENTRIES
            entryValues = PREF_JW_LANG_VALUES
            setDefaultValue(PREF_JW_LANG_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        // Token
        private const val PREF_TOKEN_KEY = "token"
        private const val PREF_TOKEN_DEFAULT = ""
        private const val PREF_TOKEN_SUMMARY = "Exclusive to Debrid providers; not intended for Torrents."

        // Debrid
        private const val PREF_DEBRID_KEY = "debrid_provider"
        private val PREF_DEBRID_ENTRIES = arrayOf(
            "None",
            "RealDebrid",
            "Premiumize",
            "AllDebrid",
            "DebridLink",
            "Offcloud",
        )
        private val PREF_DEBRID_VALUES = arrayOf(
            "none",
            "realdebrid",
            "premiumize",
            "alldebrid",
            "debridlink",
            "offcloud",
        )

        // Sort
        private const val PREF_SORT_KEY = "sorting_link"
        private val PREF_SORT_ENTRIES = arrayOf(
            "By quality then seeders",
            "By quality then size",
            "By seeders",
            "By size",
        )
        private val PREF_SORT_VALUES = arrayOf(
            "quality",
            "qualitysize",
            "seeders",
            "size",

        )

        // Provider
        private const val PREF_PROVIDER_KEY = "provider_selection"
        private val PREF_PROVIDERS = arrayOf(
            "YTS",
            "EZTV",
            "RARBG",
            "1337x",
            "ThePirateBay",
            "KickassTorrents",
            "TorrentGalaxy",
            "MagnetDL",
            "HorribleSubs",
            "NyaaSi",
            "TokyoTosho",
            "AniDex",
            "🇷🇺 Rutor",
            "🇷🇺 Rutracker",
            "🇵🇹 Comando",
            "🇵🇹 BluDV",
            "🇫🇷 Torrent9",
            "🇪🇸 MejorTorrent",
            "🇲🇽 Cinecalidad",
        )
        private val PREF_PROVIDERS_VALUE = arrayOf(
            "yts",
            "eztv",
            "rarbg",
            "1337x",
            "thepiratebay",
            "kickasstorrents",
            "torrentgalaxy",
            "magnetdl",
            "horriblesubs",
            "nyaasi",
            "tokyotosho",
            "anidex",
            "rutor",
            "rutracker",
            "comando",
            "bludv",
            "torrent9",
            "mejortorrent",
            "cinecalidad",
        )

        private val PREF_DEFAULT_PROVIDERS_VALUE = arrayOf(
            "yts",
            "eztv",
            "rarbg",
            "1337x",
            "thepiratebay",
            "kickasstorrents",
            "torrentgalaxy",
            "magnetdl",
            "horriblesubs",
            "nyaasi",
            "tokyotosho",
            "anidex",
        )
        private val PREF_PROVIDERS_DEFAULT = PREF_DEFAULT_PROVIDERS_VALUE.toSet()

        // Qualities/Resolutions
        private const val PREF_QUALITY_KEY = "quality_selection"
        private val PREF_QUALITY = arrayOf(
            "BluRay REMUX",
            "HDR/HDR10+/Dolby Vision",
            "Dolby Vision",
            "4k",
            "1080p",
            "720p",
            "480p",
            "Other (DVDRip/HDRip/BDRip...)",
            "Screener",
            "Cam",
            "Unknown",
        )
        private val PREF_QUALITY_VALUE = arrayOf(
            "brremux",
            "hdrall",
            "dolbyvision",
            "4k",
            "1080p",
            "720p",
            "480p",
            "other",
            "scr",
            "cam",
            "unknown",
        )

        private val PREF_DEFAULT_QUALITY_VALUE = arrayOf(
            "720p",
            "480p",
            "other",
            "scr",
            "cam",
            "unknown",
        )

        private val PREF_QUALITY_DEFAULT = PREF_DEFAULT_QUALITY_VALUE.toSet()

        // Qualities/Resolutions
        private const val PREF_LANG_KEY = "lang_selection"
        private val PREF_LANG = arrayOf(
            "🇯🇵 Japanese",
            "🇷🇺 Russian",
            "🇮🇹 Italian",
            "🇵🇹 Portuguese",
            "🇪🇸 Spanish",
            "🇲🇽 Latino",
            "🇰🇷 Korean",
            "🇨🇳 Chinese",
            "🇹🇼 Taiwanese",
            "🇫🇷 French",

            "🇩🇪 German",
            "🇳🇱 Dutch",
            "🇮🇳 Hindi",
            "🇮🇳 Telugu",
            "🇮🇳 Tamil",
            "🇵🇱 Polish",
            "🇱🇹 Lithuanian",
            "🇱🇻 Latvian",
            "🇪🇪 Estonian",
            "🇨🇿 Czech",

            "🇸🇰 Slovakian",
            "🇸🇮 Slovenian",
            "🇭🇺 Hungarian",
            "🇷🇴 Romanian",
            "🇧🇬 Bulgarian",
            "🇷🇸 Serbian",
            "🇭🇷 Croatian",
            "🇺🇦 Ukrainian",
            "🇬🇷 Greek",
            "🇩🇰 Danish",

            "🇫🇮 Finnish",
            "🇸🇪 Swedish",
            "🇳🇴 Norwegian",
            "🇹🇷 Turkish",
            "🇸🇦 Arabic",
            "🇮🇷 Persian",
            "🇮🇱 Hebrew",
            "🇻🇳 Vietnamese",
            "🇮🇩 Indonesian",
            "🇲🇾 Malay",

            "🇹🇭 Thai",
        )
        private val PREF_LANG_VALUE = arrayOf(
            "japanese",
            "russian",
            "italian",
            "portuguese",
            "spanish",
            "latino",
            "korean",
            "chinese",
            "taiwanese",
            "french",

            "german",
            "dutch",
            "hindi",
            "telugu",
            "tamil",
            "polish",
            "lithuanian",
            "latvian",
            "estonian",
            "czech",

            "slovakian",
            "slovenian",
            "hungarian",
            "romanian",
            "bulgarian",
            "serbian",
            "croatian",
            "ukrainian",
            "greek",
            "danish",

            "finnish",
            "swedish",
            "norwegian",
            "turkish",
            "arabic",
            "persian",
            "hebrew",
            "vietnamese",
            "indonesian",
            "malay",

            "thai",

        )

        private val PREF_LANG_DEFAULT = setOf<String>()

        private const val UPCOMING_EP_KEY = "upcoming_ep"
        private const val UPCOMING_EP_DEFAULT = true

        private const val IS_DUB_KEY = "dubbed"
        private const val IS_DUB_DEFAULT = false

        private const val IS_EFFICIENT_KEY = "efficient"
        private const val IS_EFFICIENT_DEFAULT = false

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }

        // JustWatch settings
        // Region
        private const val PREF_REGION_KEY = "jw_region"
        private val PREF_REGION_ENTRIES = arrayOf(
            "Albania", "Algeria", "Androrra", "Angola", "Antigua and Barbuda", "Argentina", "Australia", "Austria", "Azerbaijan", "Bahamas", "Bahrain", "Barbados", "Belarus", "Belgium", "Belize", "Bermuda", "Bolivia", "Bosnia and Herzegovina", "Brazil", "Bulgaria", "Burkina Faso", "Cameroon", "Canada", "Cape Verde", "Chad", "Chile", "Colombia", "Costa Rica", "Croatia", "Cuba", "Cyprus", "Czech Republic", "DR Congo", "Denmark", "Dominican Republic", "Ecuador", "Egypt", "El Salvador", "Equatorial Guinea", "Estonia", "Fiji", "Finland", "France", "French Guiana", "French Polynesia", "Germany", "Ghana", "Gibraltar", "Greece", "Guatemala", "Guernsey", "Guyana", "Honduras", "Hong Kong", "Hungary", "Iceland", "India", "Indonesia", "Iraq", "Ireland", "Israel", "Italy", "Ivory Coast", "Jamaica", "Japan", "Jordan", "Kenya", "Kosovo", "Kuwait", "Latvia", "Lebanon", "Libya", "Liechtenstein", "Lithuania", "Luxembourg", "Macedonia", "Madagascar", "Malawi", "Malaysia", "Mali", "Malta", "Mauritius", "Mexico", "Moldova", "Monaco", "Montenegro", "Morocco", "Mozambique", "Netherlands", "New Zealand", "Nicaragua", "Niger", "Nigeria", "Norway", "Oman", "Pakistan", "Palestine", "Panama", "Papua New Guinea", "Paraguay", "Peru", "Philippines", "Poland", "Portugal", "Qatar", "Romania", "Russia", "Saint Lucia", "San Marino", "Saudi Arabia", "Senegal", "Serbia", "Seychelles", "Singapore", "Slovakia", "Slovenia", "South Africa", "South Korea", "Spain", "Sweden", "Switzerland", "Taiwan", "Tanzania", "Thailand", "Trinidad and Tobago", "Tunisia", "Turkey", "Turks and Caicos Islands", "Uganda", "Ukraine", "United Arab Emirates", "United Kingdom", "United States", "Uruguay", "Vatican City", "Venezuela", "Yemen", "Zambia", "Zimbabwe",
        )
        private val PREF_REGION_VALUES = arrayOf(
            "AL", "DZ", "AD", "AO", "AG", "AR", "AU", "AT", "AZ", "BS", "BH", "BB", "BY", "BE", "BZ", "BM", "BO", "BA", "BR", "BG", "BF", "CM", "CA", "CV", "TD", "CL", "CO", "CR", "HR", "CU", "CY", "CZ", "CD", "DK", "DO", "EC", "EG", "SV", "GQ", "EE", "FJ", "FI", "FR", "GF", "PF", "DE", "GH", "GI", "GR", "GT", "GG", "GY", "HN", "HK", "HU", "IS", "IN", "ID", "IQ", "IE", "IL", "IT", "CI", "JM", "JP", "JO", "KE", "XK", "KW", "LV", "LB", "LY", "LI", "LT", "LU", "MK", "MG", "MW", "MY", "ML", "MT", "MU", "MX", "MD", "MC", "ME", "MA", "MZ", "NL", "NZ", "NI", "NE", "NG", "NO", "OM", "PK", "PS", "PA", "PG", "PY", "PE", "PH", "PL", "PT", "QA", "RO", "RU", "LC", "SM", "SA", "SN", "RS", "SC", "SG", "SK", "SI", "ZA", "KR", "ES", "SE", "CH", "TW", "TZ", "TH", "TT", "TN", "TR", "TC", "UG", "UA", "AE", "UK", "US", "UY", "VA", "VE", "YE", "ZM", "ZW",
        )
        private const val PREF_REGION_DEFAULT = "US"

        // JustWatch language in Poster, Titles
        private const val PREF_JW_LANG_KEY = "jw_lang"
        private val PREF_JW_LANG_ENTRIES = arrayOf(
            "Arabic", "Azerbaijani", "Belarusian", "Bulgarian", "Bosnian", "Catalan", "Czech", "German", "Greek", "English", "English (U.S.A.)", "Spanish", "Spanish (Spain)", "Spanish (Latinamerican)", "Estonian", "Finnish", "French", "French (Canada)", "Hebrew", "Croatian", "Hungarian", "Icelandic", "Italian", "Japanese", "Korean", "Lithuanian", "Latvian", "Macedonian", "Maltese", "Polish", "Portuguese", "Portuguese (Portugal)", "Portuguese (Brazil)", "Romanian", "Russian", "Slovakian", "Slovenian", "Albanian", "Serbian", "Swedish", "Swahili", "Turkish", "Ukrainian", "Urdu", "Chinese",
        )
        private val PREF_JW_LANG_VALUES = arrayOf(
            "ar", "az", "be", "bg", "bs", "ca", "cs", "de", "el", "en", "en-US", "es", "es-ES", "es-LA", "et", "fi", "fr", "fr-CA", "he", "hr", "hu", "is", "it", "ja", "ko", "lt", "lv", "mk", "mt", "pl", "pt", "pt-PT", "pt-BR", "ro", "ru", "sk", "sl", "sq", "sr", "sv", "sw", "tr", "uk", "ur", "zh",

        )
        private const val PREF_JW_LANG_DEFAULT = "en"
    }
}
