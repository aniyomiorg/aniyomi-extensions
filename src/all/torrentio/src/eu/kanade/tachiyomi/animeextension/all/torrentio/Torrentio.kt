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
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Torrentio : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Torrentio (Debird Only)"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val context = Injekt.get<Application>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val json: Json by injectLazy()

    // ============================== Anilist API Request ===================
    private fun makeGraphQLRequest(query: String, variables: String): Request {
        val requestBody = FormBody.Builder()
            .add("query", query)
            .add("variables", variables)
            .build()

        return POST("https://graphql.anilist.co", body = requestBody)
    }

    // ============================== Anilist Meta List ======================
    private fun anilistQuery(): String {
        return """
            query (${"$"}page: Int, ${"$"}perPage: Int, ${"$"}sort: [MediaSort], ${"$"}search: String) {
                Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                    pageInfo{
                        currentPage
                        hasNextPage
                    }
                    media(type: ANIME, sort: ${"$"}sort, search: ${"$"}search, status_in:[RELEASING,FINISHED]) {
                        id
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                        description
                        status
                        tags{
                            name
                        }
                        genres
                        studios {
                            nodes {
                                name
                            }
                        }
                    }
                }
            }
        """.trimIndent()
    }

    private fun anilistLastestQuery(): String {
        return """
            query (${"$"}page: Int, ${"$"}perPage: Int, ${"$"}sort: [AiringSort]) {
              Page(page: ${"$"}page, perPage: ${"$"}perPage) {
                pageInfo {
                  currentPage
                  hasNextPage
                }
                airingSchedules(
                  airingAt_greater: 0
                  airingAt_lesser: ${System.currentTimeMillis() / 1000 - 10000}
                  sort: ${"$"}sort
                ) {
                  media{
                    id
                    title {
                      romaji
                      english
                      native
                    }
                    coverImage {
                      extraLarge
                      large
                    }
                    description
                    status
                    tags {
                      name
                    }
                    genres
                    studios {
                      nodes {
                        name
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }

    private fun parseSearchJson(jsonLine: String?, isLatestQuery: Boolean = false): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = JSONObject(jsonData)

        val hasNextPage = jObject.getJSONObject("data").getJSONObject("Page").getJSONObject("pageInfo").getBoolean("hasNextPage")

        val mediaArray = if (isLatestQuery) {
            val airingSchedulesArray = jObject.getJSONObject("data").getJSONObject("Page").getJSONArray("airingSchedules")

            // Create a list to store media objects from airing schedules
            val mediaList = mutableListOf<JSONObject>()
            for (i in 0 until airingSchedulesArray.length()) {
                val airingSchedule = airingSchedulesArray.getJSONObject(i)
                val mediaObject = airingSchedule.getJSONObject("media")
                mediaList.add(mediaObject)
            }

            // Convert the list of media objects to JSONArray
            JSONArray(mediaList)
        } else {
            jObject.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
        }

        val animeList = mutableListOf<SAnime>()

        for (i in 0 until mediaArray.length()) {
            val item = mediaArray.getJSONObject(i)
            val anime = SAnime.create()

            anime.title = item.getJSONObject("title").getString("romaji")
            anime.thumbnail_url = item.getJSONObject("coverImage").getString("extraLarge")
            anime.setUrlWithoutDomain(item.getString("id"))
            anime.description = item.optString("description", "").replace("<br>", "\n")

            // Extracting and mapping status
            val statusString = item.getString("status")
            anime.status = when (statusString) {
                "RELEASING" -> SAnime.ONGOING
                "FINISHED" -> SAnime.COMPLETED
                // Add more status mappings as needed
                else -> SAnime.UNKNOWN
            }

            // Extracting tags
            val tagsArray = item.getJSONArray("tags")
            val tagsList = mutableListOf<String>()
            for (j in 0 until tagsArray.length()) {
                val tagObject = tagsArray.getJSONObject(j)
                tagsList.add(tagObject.getString("name"))
            }

            // Extracting genres
            val genresArray = item.getJSONArray("genres")
            val genresList = mutableListOf<String>()
            for (j in 0 until genresArray.length()) {
                genresList.add(genresArray.getString(j))
            }

            // Combining tags and genres
            val combinedGenres = tagsList + genresList
            anime.genre = combinedGenres.joinToString(", ")

            // Sorting genres in ascending order
            anime.genre = anime.genre?.split(", ")?.sorted()?.joinToString(", ")

            // Extracting studios
            val studiosArray = item.optJSONObject("studios")?.getJSONArray("nodes")
            val studiosList = mutableListOf<String>()
            studiosArray?.let {
                for (j in 0 until it.length()) {
                    val studioObject = it.getJSONObject(j)
                    studiosList.add(studioObject.getString("name"))
                }
            }
            anime.author = studiosList.joinToString(", ")
            anime.initialized = true
            animeList.add(anime)
        }

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "POPULARITY_DESC"
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData) }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "TIME_DESC"
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistLastestQuery(), variables)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData, true)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
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
        val variables = """
            {
                "page": $page,
                "perPage": 30,
                "sort": "POPULARITY_DESC",
                "search": "$query"
            }
        """.trimIndent()

        return makeGraphQLRequest(anilistQuery(), variables)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        return parseSearchJson(jsonData)
    }

    // =========================== Anime Details ============================

//    override fun animeDetailsRequest(anime: SAnime): Request {
//        throw UnsupportedOperationException()
//    }
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        return GET("https://anime-kitsu.strem.fun/meta/series/anilist%3A${anime.url}.json")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val jObject = JSONObject(responseString)

        val metaObject = jObject.getJSONObject("meta")
        val animeType = metaObject.getString("animeType")

        val episodeList = mutableListOf<SEpisode>()

        if (animeType.equals("movie", ignoreCase = true)) {
            // Handle movie response
            val title = metaObject.getString("name")
            val episode = SEpisode.create()
            episode.url = metaObject.getString("id").toString()
            episode.name = "Movie"
            episode.episode_number = 1.0F
            episodeList.add(episode)
        } else if (animeType.equals("tv", ignoreCase = true)) {
            // Handle TV show response
            val videosArray = metaObject.getJSONArray("videos")

            for (i in 0 until videosArray.length()) {
                val videoObject = videosArray.getJSONObject(i)

                val title = videoObject.getString("title")
                val released = videoObject.getString("released")
                val episodeNumber = videoObject.getInt("episode").toFloat()
                // val thumbnailUrl = videoObject.getString("thumbnail")

                val episode = SEpisode.create()
                episode.episode_number = episodeNumber
                episode.url = videoObject.getString("id").toString()
                episode.name = title
                episode.date_upload = parseReleaseDate(released)
                episodeList.add(episode)
            }
        }

        return episodeList.reversed()
    }

    // Function to parse the release date
    private fun parseReleaseDate(released: String): Long {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
        val date = inputFormat.parse(released)
        return date?.time ?: 0
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        var mainURL = "$baseUrl/"

        // link format : baseurl /providers=yts |language=hindi |qualityfilter=cam |realdebrid=token |sort=qualitysize  /stream/video/ id .json

        val providerSet = preferences.getStringSet(PREF_PROVIDER_KEY, PREF_PROVIDERS_DEFAULT)
        if (!providerSet.isNullOrEmpty()) {
            mainURL += "providers=${providerSet.filter { it.isNotBlank() }.joinToString(",")}|"
        }

        val langSet = preferences.getStringSet(PREF_LANG_KEY, PREF_LANG_DEFAULT)
        if (!langSet.isNullOrEmpty()) {
            mainURL += "language=${langSet.filter { it.isNotBlank() }.joinToString(",")}|"
        }

        val qualitySet = preferences.getStringSet(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        if (!qualitySet.isNullOrEmpty()) {
            mainURL += "qualityfilter=${ qualitySet.filter { it.isNotBlank() }.joinToString(",")}|"
        }

        val sortKey = preferences.getString(PREF_SORT_KEY, "quality")
        if (!sortKey.isNullOrBlank()) {
            mainURL += "sort=$sortKey|"
        }

        // Handle based on key and provider

        val token = preferences.getString(PREF_TOKEN_KEY, null)
        val debridProvider = preferences.getString(PREF_DEBIRD_KEY, null)

        if (token.isNullOrBlank() && debridProvider != "none") {
            handler.post {
                context.let {
                    Toast.makeText(
                        it,
                        "Kindly input the token in the extension settings.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            throw UnsupportedOperationException()
        } else if (debridProvider == "none" && token.isNullOrBlank()) {
            // ;_; If in future torrent link can be handled here. ;_;
            handler.post {
                context.let {
                    Toast.makeText(
                        it,
                        "Currently, Aniyomi does not support torrents. Please check back later for future support or consider using it with a Debrid account as an alternative.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            throw UnsupportedOperationException()
        } else if (!token.isNullOrBlank() && debridProvider != "none") {
            // Debrid Link
            mainURL += "$debridProvider=$token|"
        }
        mainURL = mainURL.trimEnd('|') + "/stream/video/${episode.url}.json"
        return GET(mainURL)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body.string()
        val jObject = JSONObject(responseString)
        val streamsArray = jObject.getJSONArray("streams")

        val videoList = mutableListOf<Video>()

        for (i in 0 until streamsArray.length()) {
            val streamObject = streamsArray.getJSONObject(i)

            val videoName = streamObject.getString("name").replace("\n", " ") + " | " + streamObject.getString("title")

            val video = Video(
                streamObject.getString("url"),
                videoName,
                streamObject.getString("url"),
            )

            videoList.add(video)
        }

        return videoList
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Debrid provider
        ListPreference(screen.context).apply {
            key = PREF_DEBIRD_KEY
            title = "Debird Provider"
            entries = PREF_DEBIRD_ENTRIES
            entryValues = PREF_DEBIRD_VALUES
            setDefaultValue("none")
            summary = "%s"

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
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        // Token
        private const val PREF_TOKEN_KEY = "token"
        private const val PREF_TOKEN_DEFAULT = "none"
        private const val PREF_TOKEN_SUMMARY = "For temporary uses. Updating the extension will erase this setting."

        // Debird
        private const val PREF_DEBIRD_KEY = "debrid_provider"
        private val PREF_DEBIRD_ENTRIES = arrayOf(
            "None",
            "RealDebrid",
            "Premiumize",
            "AllDebrid",
            "DebridLink",
            "Offcloud",
        )
        private val PREF_DEBIRD_VALUES = arrayOf(
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
    }
}

// link format : baseurl /providers=yts |language=hindi |qualityfilter=cam |realdebrid=123 |sort=qualitysize  /manifest.json
