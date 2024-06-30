package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.text.StringSubstitutor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

class Jellyfin(private val suffix: String) : ConfigurableAnimeSource, AnimeHttpSource(), UnmeteredSource {
    internal val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val displayName by lazy { preferences.getString(PREF_CUSTOM_LABEL_KEY, suffix)!!.ifBlank { suffix } }
    private val userId by lazy { preferences.userId.ifBlank { authInterceptor.updateCredentials().second } }
    private val username by lazy { preferences.username }
    private val password by lazy { preferences.password }

    override val baseUrl by lazy { preferences.getString(HOSTURL_KEY, HOSTURL_DEFAULT)!! }

    override val lang = "all"

    override val name by lazy { "Jellyfin ($displayName)" }

    override val supportsLatest = true

    override val id by lazy {
        val key = "jellyfin" + (if (suffix == "1") "" else " ($suffix)") + "/all/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    private val authInterceptor by lazy { AuthInterceptor(preferences, network.client, baseUrl) }

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(authInterceptor)
            .dns(Dns.SYSTEM)
            .build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val parentId = preferences.mediaLib
        require(parentId.isNotEmpty()) { "Select library in the extension settings." }
        val startIndex = (page - 1) * SEASONS_FETCH_LIMIT

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("StartIndex", startIndex.toString())
            addQueryParameter("Limit", SEASONS_FETCH_LIMIT.toString())
            addQueryParameter("Recursive", "true")
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter("IncludeItemTypes", "Movie,Season,BoxSet")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("ParentId", parentId)
            addQueryParameter("EnableImageTypes", "Primary")
        }.build()

        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("StartIndex")!!.toInt() / SEASONS_FETCH_LIMIT + 1
        val splitCollections = preferences.splitCollections
        val data = response.parseAs<ItemListDto>()

        val animeList = data.items.flatMap {
            if (it.type == "BoxSet" && splitCollections) {
                val url = popularAnimeRequest(page).url.newBuilder().apply {
                    setQueryParameter("ParentId", it.id)
                }.build()

                popularAnimeParse(
                    client.newCall(GET(url)).execute(),
                ).animes
            } else {
                listOf(it.toSAnime(baseUrl, userId))
            }
        }
        val hasNextPage = SEASONS_FETCH_LIMIT * page < data.totalRecordCount

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = popularAnimeRequest(page).url.newBuilder().apply {
            setQueryParameter("SortBy", "DateCreated,SortName")
            setQueryParameter("SortOrder", "Descending")
        }.build()

        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = popularAnimeRequest(page).url.newBuilder().apply {
            // Search for series, rather than seasons, since season names can just be "Season 1"
            setQueryParameter("IncludeItemTypes", "Movie,Series")
            setQueryParameter("Limit", SERIES_FETCH_LIMIT.toString())
            setQueryParameter("SearchTerm", query)
        }.build()

        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("StartIndex")!!.toInt() / SERIES_FETCH_LIMIT + 1
        val data = response.parseAs<ItemListDto>()

        val animeList = data.items.flatMap { series ->
            val seasonsUrl = popularAnimeRequest(1).url.newBuilder().apply {
                setQueryParameter("ParentId", series.id)
                removeAllQueryParameters("StartIndex")
                removeAllQueryParameters("Limit")
            }.build()

            val seasonsData = client.newCall(
                GET(seasonsUrl),
            ).execute().parseAs<ItemListDto>()

            seasonsData.items.map { it.toSAnime(baseUrl, userId) }
        }
        val hasNextPage = SERIES_FETCH_LIMIT * page < data.totalRecordCount

        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        if (!anime.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")
        return GET(anime.url)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<ItemDto>()
        val infoData = if (preferences.useSeriesData && data.seriesId != null) {
            val url = response.request.url.let { url ->
                url.newBuilder().apply {
                    removePathSegment(url.pathSize - 1)
                    addPathSegment(data.seriesId)
                }.build()
            }

            client.newCall(
                GET(url),
            ).execute().parseAs<ItemDto>()
        } else {
            data
        }

        return infoData.toSAnime(baseUrl, userId)
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        if (!anime.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")
        val httpUrl = anime.url.toHttpUrl()
        val itemId = httpUrl.pathSegments[3]
        val fragment = httpUrl.fragment!!

        val url = when {
            fragment.startsWith("seriesId") -> {
                httpUrl.newBuilder().apply {
                    encodedPath("/")
                    addPathSegment("Shows")
                    addPathSegment(fragment.split(",").last())
                    addPathSegment("Episodes")
                    addQueryParameter("seasonId", httpUrl.pathSegments.last())
                    addQueryParameter("userId", userId)
                    addQueryParameter("Fields", "Overview,MediaSources,DateCreated")
                }.build()
            }
            fragment.startsWith("boxSet") -> {
                httpUrl.newBuilder().apply {
                    removePathSegment(3)
                    addQueryParameter("Recursive", "true")
                    addQueryParameter("SortBy", "SortName")
                    addQueryParameter("SortOrder", "Ascending")
                    addQueryParameter("IncludeItemTypes", "Movie,Season,BoxSet,Series")
                    addQueryParameter("ParentId", itemId)
                    addQueryParameter("Fields", "DateCreated")
                }.build()
            }
            fragment.startsWith("series") -> {
                httpUrl.newBuilder().apply {
                    encodedPath("/")
                    addPathSegment("Shows")
                    addPathSegment(itemId)
                    addPathSegment("Episodes")
                    addQueryParameter("Fields", "DateCreated")
                }.build()
            }
            else -> {
                httpUrl.newBuilder().apply {
                    addQueryParameter("Fields", "DateCreated")
                }.build()
            }
        }

        return GET(url)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val httpUrl = response.request.url
        val episodeList = if (httpUrl.fragment == "boxSet") {
            val data = response.parseAs<ItemListDto>()
            val animeList = data.items.map {
                it.toSAnime(baseUrl, userId)
            }.sortedByDescending { it.title }
            animeList.flatMap {
                client.newCall(episodeListRequest(it))
                    .execute()
                    .let { res ->
                        episodeListParse(res, "${it.title} - ")
                    }
            }
        } else {
            episodeListParse(response, "")
        }

        return episodeList
    }

    private fun episodeListParse(response: Response, prefix: String): List<SEpisode> {
        val itemList = if (response.request.url.pathSize > 3) {
            listOf(response.parseAs<ItemDto>())
        } else {
            response.parseAs<ItemListDto>().items
        }

        val extraDetails = preferences.getEpDetails
        val episodeTemplate = preferences.episodeTemplate

        return itemList.map {
            it.toSEpisode(baseUrl, userId, prefix, extraDetails, episodeTemplate)
        }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        if (!episode.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")
        return GET(episode.url)
    }

    override fun videoListParse(response: Response): List<Video> {
        val id = response.parseAs<ItemDto>().id
        val apiKey = preferences.apiKey

        val sessionData = client.newCall(
            GET("$baseUrl/Items/$id/PlaybackInfo?userId=$userId"),
        ).execute().parseAs<SessionDto>()

        val videoList = mutableListOf<Video>()
        val subtitleList = mutableListOf<Track>()
        val externalSubtitleList = mutableListOf<Track>()

        val prefAudioLang = preferences.getAudioPref
        val prefSubLang = preferences.getSubPref
        val subBurn = preferences.subBurn
        var audioTrackIndex = 1
        var subtitleTrackIndex: Int? = null
        var width = 1920
        var height = 1080
        var bitRate = Long.MAX_VALUE

        sessionData.mediaSources.first().mediaStreams.forEach { media ->
            when (media.type) {
                "Video" -> {
                    width = media.width!!
                    height = media.height!!
                    bitRate = media.bitRate!!
                }
                "Subtitle" -> {
                    if (media.supportsExternalStream) {
                        val subtitleUrl = "$baseUrl/Videos/$id/$id/Subtitles/${media.index}/0/Stream.${media.codec}?api_key=$apiKey"
                        if (media.isExternal) {
                            externalSubtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                        }
                        subtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                    }
                    if (media.language == prefSubLang) {
                        subtitleTrackIndex = media.index
                    }
                }
                "Audio" -> {
                    if (media.language == prefAudioLang) {
                        audioTrackIndex = media.index
                    }
                }
            }
        }

        val videoBitrate = bitRate.formatBytes().replace("B", "b")
        val staticUrl = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
        val staticVideo = Video(bitRate.toString(), "Source - ${videoBitrate}ps", staticUrl, subtitleTracks = externalSubtitleList)

        if (!sessionData.mediaSources.first().supportsTranscoding) {
            return listOf(staticVideo)
        }

        Constants.QUALITIES_LIST.reversed().forEach { quality ->
            if (width < quality.width && height < quality.height) {
                videoList.add(staticVideo)

                return videoList.reversed()
            } else {
                val url = "$baseUrl/videos/$id/main.m3u8".toHttpUrl().newBuilder().apply {
                    addQueryParameter("api_key", apiKey)
                    addQueryParameter("VideoCodec", "h264")
                    addQueryParameter("AudioCodec", "aac,mp3")
                    addQueryParameter("AudioStreamIndex", audioTrackIndex.toString())
                    if (subBurn) {
                        subtitleTrackIndex?.let {
                            addQueryParameter("SubtitleStreamIndex", it.toString())
                        }
                    }
                    addQueryParameter("VideoBitrate", quality.videoBitrate.toString())
                    addQueryParameter("AudioBitrate", quality.audioBitrate.toString())
                    addQueryParameter("PlaySessionId", sessionData.playSessionId)
                    addQueryParameter("TranscodingMaxAudioChannels", "6")
                    addQueryParameter("RequireAvc", "false")
                    addQueryParameter("SegmentContainer", "ts")
                    addQueryParameter("MinSegments", "1")
                    addQueryParameter("BreakOnNonKeyFrames", "true")
                    addQueryParameter("h264-profile", "high,main,baseline,constrainedbaseline")
                    addQueryParameter("h264-level", "51")
                    addQueryParameter("h264-deinterlace", "true")
                    addQueryParameter("TranscodeReasons", "VideoCodecNotSupported,AudioCodecNotSupported,ContainerBitrateExceedsLimit")
                }.build().toString()

                videoList.add(
                    Video(quality.videoBitrate.toString(), quality.description, url, subtitleTracks = subtitleList),
                )
            }
        }

        videoList.add(staticVideo)

        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getQuality

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.url.toInt() },
            ),
        ).reversed()
    }

    // ============================= Utilities ==============================

    companion object {
        private const val SEASONS_FETCH_LIMIT = 20
        private const val SERIES_FETCH_LIMIT = 5
        private val ALLOWED_LIBRARIES = listOf(
            "unknown",
            "movies",
            "tvshows",
            "homevideos",
            "boxsets",
            "playlists",
            "folders",
        )

        const val APIKEY_KEY = "api_key"
        const val USERID_KEY = "user_id"

        internal const val EXTRA_SOURCES_COUNT_KEY = "extraSourcesCount"
        internal const val EXTRA_SOURCES_COUNT_DEFAULT = "3"
        private val EXTRA_SOURCES_ENTRIES = (1..10).map { it.toString() }.toTypedArray()

        private const val PREF_CUSTOM_LABEL_KEY = "pref_label"

        private const val HOSTURL_KEY = "host_url"
        private const val HOSTURL_DEFAULT = ""

        const val USERNAME_KEY = "username"
        const val USERNAME_DEFAULT = ""

        const val PASSWORD_KEY = "password"
        const val PASSWORD_DEFAULT = ""

        private const val MEDIALIB_KEY = "library_pref"
        private const val MEDIALIB_DEFAULT = ""

        private const val PREF_EPISODE_NAME_TEMPLATE_KEY = "pref_episode_name_template"
        private const val PREF_EPISODE_NAME_TEMPLATE_DEFAULT = "{type} {number} - {title}"

        private const val PREF_EP_DETAILS_KEY = "pref_episode_details_key"
        private val PREF_EP_DETAILS = arrayOf("Overview", "Runtime", "Size")
        private val PREF_EP_DETAILS_DEFAULT = emptySet<String>()

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "Source"

        private const val PREF_AUDIO_KEY = "preferred_audioLang"
        private const val PREF_AUDIO_DEFAULT = "jpn"

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_DEFAULT = "eng"

        private const val PREF_BURN_SUB_KEY = "pref_burn_subs"
        private const val PREF_BURN_SUB_DEFAULT = false

        private const val PREF_INFO_TYPE = "preferred_meta_type"
        private const val PREF_INFO_DEFAULT = false

        private const val PREF_SPLIT_COLLECTIONS_KEY = "preferred_split_col"
        private const val PREF_SPLIT_COLLECTIONS_DEFAULT = false

        private val SUBSTITUTE_VALUES = hashMapOf(
            "title" to "",
            "type" to "",
            "typeShort" to "",
            "seriesTitle" to "",
            "seasonTitle" to "",
            "number" to "",
            "createdDate" to "",
            "releaseDate" to "",
            "size" to "",
            "sizeBytes" to "",
            "runtime" to "",
            "runtimeS" to "",
        )
        private val STRING_SUBSTITUTOR = StringSubstitutor(SUBSTITUTE_VALUES, "{", "}").apply {
            isEnableUndefinedVariableException = true
        }

        private const val LOG_TAG = "Jellyfin"
    }

    // ============================ Preferences =============================

    private val SharedPreferences.userId
        get() = getString(USERID_KEY, "")!!

    private val SharedPreferences.mediaLib
        get() = getString(MEDIALIB_KEY, MEDIALIB_DEFAULT)!!

    private val SharedPreferences.episodeTemplate
        get() = getString(PREF_EPISODE_NAME_TEMPLATE_KEY, PREF_EPISODE_NAME_TEMPLATE_DEFAULT)!!

    private val SharedPreferences.getEpDetails
        get() = getStringSet(PREF_EP_DETAILS_KEY, PREF_EP_DETAILS_DEFAULT)!!

    private val SharedPreferences.getQuality
        get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private val SharedPreferences.getAudioPref
        get() = getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)!!

    private val SharedPreferences.getSubPref
        get() = getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.subBurn
        get() = getBoolean(PREF_BURN_SUB_KEY, PREF_BURN_SUB_DEFAULT)

    private val SharedPreferences.useSeriesData
        get() = getBoolean(PREF_INFO_TYPE, PREF_INFO_DEFAULT)

    private val SharedPreferences.splitCollections
        get() = getBoolean(PREF_SPLIT_COLLECTIONS_KEY, PREF_SPLIT_COLLECTIONS_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fetchMediaLibraries()

        if (suffix == "1") {
            ListPreference(screen.context).apply {
                key = EXTRA_SOURCES_COUNT_KEY
                title = "Number of sources"
                summary = "Number of jellyfin sources to create. There will always be at least one Jellyfin source."
                entries = EXTRA_SOURCES_ENTRIES
                entryValues = EXTRA_SOURCES_ENTRIES

                setDefaultValue(EXTRA_SOURCES_COUNT_DEFAULT)
                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    true
                }
            }.also(screen::addPreference)
        }

        screen.addEditTextPreference(
            title = "Source display name",
            default = suffix,
            summary = displayName.ifBlank { "Here you can change the source displayed suffix" },
            key = PREF_CUSTOM_LABEL_KEY,
            restartRequired = true,
        )

        screen.addEditTextPreference(
            title = "Address",
            default = HOSTURL_DEFAULT,
            summary = baseUrl.ifBlank { "The server address" },
            dialogMessage = "The address must not end with a forward slash.",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.toHttpUrlOrNull() != null && !it.endsWith("/") },
            validationMessage = "The URL is invalid, malformed, or ends with a slash",
            key = HOSTURL_KEY,
            restartRequired = true,
        ) { preferences.clearApiKey() }

        screen.addEditTextPreference(
            title = "Username",
            default = USERNAME_DEFAULT,
            summary = username.ifBlank { "The user account name" },
            key = USERNAME_KEY,
            restartRequired = true,
        ) { preferences.clearApiKey() }

        screen.addEditTextPreference(
            title = "Password",
            default = PASSWORD_DEFAULT,
            summary = if (password.isBlank()) "The user account password" else "•".repeat(password.length),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            key = PASSWORD_KEY,
            restartRequired = true,
        ) { preferences.clearApiKey() }

        ListPreference(screen.context).apply {
            key = MEDIALIB_KEY
            title = "Select media library"
            summary = buildString {
                if (mediaLibraries.isEmpty()) {
                    append("Exit and enter the settings menu to load options.")
                } else {
                    append("Selected: %s")
                }
            }
            entries = mediaLibraries.map { it.first }.toTypedArray()
            entryValues = mediaLibraries.map { it.second }.toTypedArray()
            setDefaultValue("")
        }.also(screen::addPreference)

        screen.addEditTextPreference(
            key = PREF_EPISODE_NAME_TEMPLATE_KEY,
            title = "Episode title format",
            summary = "Customize how episode names appear",
            inputType = InputType.TYPE_CLASS_TEXT,
            default = PREF_EPISODE_NAME_TEMPLATE_DEFAULT,
            dialogMessage = """
            |Supported placeholders:
            |- {title}: Episode name
            |- {type}: Type, 'Episode' for episodes and 'Movie' for movies
            |- {typeShort}: Type, 'Ep.' for episodes and 'Movie' for movies
            |- {seriesTitle}: Series name
            |- {seasonTitle}: Season name
            |- {number}: Episode number
            |- {createdDate}: Episode creation date
            |- {releaseDate}: Episode release date
            |- {size}: Episode file size (formatted)
            |- {sizeBytes}: Episode file size (in bytes)
            |- {runtime}: Episode runtime (formatted)
            |- {runtimeS}: Episode runtime (in seconds)
            |If you wish to place some text between curly brackets, place the escape character "$"
            |before the opening curly bracket, e.g. ${'$'}{series}.
            """.trimMargin(),
            validate = {
                try {
                    STRING_SUBSTITUTOR.replace(it)
                    true
                } catch (e: IllegalArgumentException) {
                    false
                }
            },
            validationMessage = "Invalid episode title format",
        )

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EP_DETAILS_KEY
            title = "Additional details for episodes"
            summary = "Show additional details about an episode in the scanlator field"
            entries = PREF_EP_DETAILS
            entryValues = PREF_EP_DETAILS
            setDefaultValue(PREF_EP_DETAILS_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            summary = "Preferred quality. 'Source' means no transcoding."
            entries = arrayOf("Source") + Constants.QUALITIES_LIST.map { it.description }
            entryValues = arrayOf("Source") + Constants.QUALITIES_LIST.map { it.description }
            setDefaultValue(PREF_QUALITY_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred transcoding audio language"
            summary = "Preferred audio when transcoding. Does not affect 'Source' quality."
            entries = Constants.LANG_ENTRIES
            entryValues = Constants.LANG_VALUES
            setDefaultValue(PREF_AUDIO_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = "Preferred transcoding subtitle language"
            summary = "Preferred subtitle when transcoding. Does not affect 'Source' quality."
            entries = Constants.LANG_ENTRIES
            entryValues = Constants.LANG_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_BURN_SUB_KEY
            title = "Burn in subtitles"
            summary = "Burn in subtitles when transcoding. Does not affect 'Source' quality."
            setDefaultValue(PREF_BURN_SUB_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_INFO_TYPE
            title = "Retrieve metadata from series"
            summary = "Enable this to retrieve metadata from series instead of season when applicable."
            setDefaultValue(PREF_INFO_DEFAULT)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SPLIT_COLLECTIONS_KEY
            title = "Split collections"
            summary = "Split each item in a collection into its own entry"
            setDefaultValue(PREF_SPLIT_COLLECTIONS_DEFAULT)
        }.also(screen::addPreference)
    }

    private var mediaLibraries = emptyList<Pair<String, String>>()
    private var fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
    private var fetchFiltersAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    private enum class FetchFilterStatus {
        NOT_FETCHED,
        FETCHING,
        FETCHED,
    }

    private fun fetchMediaLibraries() {
        if (baseUrl.isBlank() || fetchFilterStatus != FetchFilterStatus.NOT_FETCHED || fetchFiltersAttempts > 3) {
            return
        }

        fetchFilterStatus = FetchFilterStatus.FETCHING
        fetchFiltersAttempts++

        scope.launch {
            try {
                mediaLibraries = client.newCall(
                    GET("$baseUrl/Users/$userId/Items"),
                ).execute().parseAs<ItemListDto>().items.filter {
                    it.collectionType in ALLOWED_LIBRARIES
                }.map {
                    Pair(it.name, it.id)
                }
                fetchFilterStatus = FetchFilterStatus.FETCHED
            } catch (e: Exception) {
                fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
                Log.e(LOG_TAG, "Failed to fetch media libraries", e)
            }
        }
    }
}
