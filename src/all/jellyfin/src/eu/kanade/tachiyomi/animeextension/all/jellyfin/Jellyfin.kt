package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.ceil
import kotlin.math.floor

class Jellyfin : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Jellyfin"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client: OkHttpClient =
        network.client
            .newBuilder()
            .dns(Dns.SYSTEM)
            .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override var baseUrl = JFConstants.getPrefHostUrl(preferences)

    private var username = JFConstants.getPrefUsername(preferences)
    private var password = JFConstants.getPrefPassword(preferences)
    private var parentId = JFConstants.getPrefParentId(preferences)
    private var apiKey = JFConstants.getPrefApiKey(preferences)
    private var userId = JFConstants.getPrefUserId(preferences)

    init {
        login(false)
    }

    private fun login(new: Boolean, context: Context? = null): Boolean? {
        if (apiKey == null || userId == null || new) {
            baseUrl = JFConstants.getPrefHostUrl(preferences)
            username = JFConstants.getPrefUsername(preferences)
            password = JFConstants.getPrefPassword(preferences)
            if (username.isEmpty() || password.isEmpty()) {
                return null
            }
            val (newKey, newUid) = runBlocking {
                withContext(Dispatchers.IO) {
                    JellyfinAuthenticator(preferences, baseUrl, client)
                        .login(username, password)
                }
            }
            if (newKey != null && newUid != null) {
                apiKey = newKey
                userId = newUid
            } else {
                context?.let { Toast.makeText(it, "Login failed.", Toast.LENGTH_LONG).show() }
                return false
            }
        }
        return true
    }

    // ============================== Popular ===============================

    override fun popularAnimeParse(response: Response): AnimesPage = throw Exception("Not used")

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return client.newCall(popularAnimeRequest(page))
            .asObservableSuccess()
            .map { response ->
                popularAnimeParsePage(response, page)
            }
    }

    override fun popularAnimeRequest(page: Int): Request {
        if (parentId.isEmpty()) {
            throw Exception("Select library in the extension settings.")
        }
        val startIndex = (page - 1) * 20

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("StartIndex", startIndex.toString())
        url.addQueryParameter("Limit", "20")
        url.addQueryParameter("Recursive", "true")
        url.addQueryParameter("SortBy", "SortName")
        url.addQueryParameter("SortOrder", "Ascending")
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season,BoxSet")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("EnableImageTypes", "Primary")

        return GET(url.toString())
    }

    private fun popularAnimeParsePage(response: Response, page: Int): AnimesPage {
        val (list, hasNext) = animeParse(response, page)
        return AnimesPage(
            list.sortedBy { it.title },
            hasNext,
        )
    }

    // =============================== Latest ===============================

    override fun latestUpdatesParse(response: Response) = throw Exception("Not used")

    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        return client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { response ->
                latestUpdatesParsePage(response, page)
            }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        if (parentId.isEmpty()) {
            throw Exception("Select library in the extension settings.")
        }

        val startIndex = (page - 1) * 20

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("StartIndex", startIndex.toString())
        url.addQueryParameter("Limit", "20")
        url.addQueryParameter("Recursive", "true")
        url.addQueryParameter("SortBy", "DateCreated,SortName")
        url.addQueryParameter("SortOrder", "Descending")
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season,BoxSet")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("EnableImageTypes", "Primary")

        return GET(url.toString())
    }

    private fun latestUpdatesParsePage(response: Response, page: Int) = animeParse(response, page)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun searchAnimeParse(response: Response) = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        if (parentId.isEmpty()) {
            throw Exception("Select library in the extension settings.")
        }

        val animeList = mutableListOf<SAnime>()
        val startIndex = (page - 1) * 5

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("StartIndex", startIndex.toString())
        url.addQueryParameter("Limit", "5")
        url.addQueryParameter("Recursive", "true")
        url.addQueryParameter("SortBy", "SortName")
        url.addQueryParameter("SortOrder", "Ascending")
        url.addQueryParameter("includeItemTypes", "Movie,Series,BoxSet")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("EnableImageTypes", "Primary")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("SearchTerm", query)

        val response = client.newCall(
            GET(url.build().toString(), headers = headers),
        ).execute()
        val items = json.decodeFromString<ItemsResponse>(response.body.string())
        items.Items.forEach {
            animeList.addAll(
                getAnimeFromId(it.Id),
            )
        }

        return Observable.just(AnimesPage(animeList, 5 * page < items.TotalRecordCount))
    }

    private fun getAnimeFromId(id: String): List<SAnime> {
        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("Recursive", "true")
        url.addQueryParameter("SortBy", "SortName")
        url.addQueryParameter("SortOrder", "Ascending")
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("EnableImageTypes", "Primary")
        url.addQueryParameter("ParentId", id)

        val response = client.newCall(
            GET(url.build().toString()),
        ).execute()
        return animeParse(response, 0).animes
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val mediaId = json.decodeFromString<LinkData>(anime.url)

        val infoId = if (preferences.getBoolean("preferred_meta_type", false)) {
            mediaId.seriesId
        } else {
            mediaId.seasonId
        }

        val url = "$baseUrl/Users/$userId/Items/$infoId".toHttpUrl().newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("fields", "Studios")

        return GET(url.toString())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val info = json.decodeFromString<ItemsResponse.Item>(response.body.string())

        val anime = SAnime.create()

        if (info.Genres != null) anime.genre = info.Genres.joinToString(", ")
        if (info.SeriesStudio != null) anime.author = info.SeriesStudio

        anime.description = if (info.Overview != null) {
            Jsoup.parse(
                info.Overview
                    .replace("<br>\n", "br2n")
                    .replace("<br>", "br2n")
                    .replace("\n", "br2n"),
            ).text().replace("br2n", "\n")
        } else {
            ""
        }

        anime.title = if (info.SeriesName == null) {
            info.Name
        } else {
            "${info.SeriesName} ${info.Name}"
        }

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        return GET(baseUrl + mediaId.path, headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = if (response.request.url.toString().startsWith("$baseUrl/Users/")) {
            val parsed = json.decodeFromString<ItemsResponse.Item>(response.body.string())
            val episode = SEpisode.create()
            episode.episode_number = 1.0F
            episode.name = "Movie ${parsed.Name}"
            episode.setUrlWithoutDomain(response.request.url.toString().substringAfter(baseUrl))
            listOf(episode)
        } else {
            val parsed = json.decodeFromString<ItemsResponse>(response.body.string())

            parsed.Items.map { ep ->

                val namePrefix = if (ep.IndexNumber == null) {
                    ""
                } else {
                    val formattedEpNum = if (floor(ep.IndexNumber) == ceil(ep.IndexNumber)) {
                        ep.IndexNumber.toInt()
                    } else {
                        ep.IndexNumber.toFloat()
                    }
                    "Episode $formattedEpNum "
                }

                SEpisode.create().apply {
                    name = "$namePrefix${ep.Name}"
                    episode_number = ep.IndexNumber ?: 0F
                    url = "/Users/$userId/Items/${ep.Id}?api_key=$apiKey"
                }
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val id = json.decodeFromString<ItemsResponse.Item>(response.body.string()).Id

        val sessionResponse = client.newCall(
            GET("$baseUrl/Items/$id/PlaybackInfo?userId=$userId&api_key=$apiKey"),
        ).execute()
        val parsed = json.decodeFromString<SessionResponse>(sessionResponse.body.string())

        val subtitleList = mutableListOf<Track>()

        val prefSub = preferences.getString(JFConstants.PREF_SUB_KEY, "eng")!!
        val prefAudio = preferences.getString(JFConstants.PREF_AUDIO_KEY, "jpn")!!

        var audioIndex = 1
        var subIndex: Int? = null
        var width = 1920
        var height = 1080

        parsed.MediaSources.first().MediaStreams.forEach { media ->
            when (media.Type) {
                "Subtitle" -> {
                    if (media.SupportsExternalStream) {
                        val subUrl = "$baseUrl/Videos/$id/$id/Subtitles/${media.Index}/0/Stream.${media.Codec}?api_key=$apiKey"
                        if (media.Language != null) {
                            if (media.Language == prefSub) {
                                try {
                                    if (media.IsExternal) {
                                        subtitleList.add(0, Track(subUrl, media.DisplayTitle!!))
                                    }
                                } catch (e: Error) {
                                    subIndex = media.Index
                                }
                            } else {
                                if (media.IsExternal) {
                                    subtitleList.add(Track(subUrl, media.DisplayTitle!!))
                                }
                            }
                        } else {
                            if (media.IsExternal) {
                                subtitleList.add(Track(subUrl, media.DisplayTitle!!))
                            }
                        }
                    } else {
                        if (media.Language != null && media.Language == prefSub) {
                            subIndex = media.Index
                        }
                    }
                }
                "Audio" -> {
                    if (media.Language != null && media.Language == prefAudio) {
                        audioIndex = media.Index
                    }
                }
                "Video" -> {
                    width = media.Width!!
                    height = media.Height!!
                }
            }
        }

        // Loop over qualities
        JFConstants.QUALITIES_LIST.forEach { quality ->
            if (width < quality.width && height < quality.height) {
                val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
                videoList.add(Video(url, "Best", url, subtitleTracks = subtitleList))

                return videoList.reversed()
            } else {
                val url = "$baseUrl/videos/$id/main.m3u8".toHttpUrl().newBuilder()

                url.addQueryParameter("api_key", apiKey)
                url.addQueryParameter("VideoCodec", "h264")
                url.addQueryParameter("AudioCodec", "aac,mp3")
                url.addQueryParameter("AudioStreamIndex", audioIndex.toString())
                subIndex?.let { url.addQueryParameter("SubtitleStreamIndex", it.toString()) }
                url.addQueryParameter("VideoCodec", "h264")
                url.addQueryParameter("VideoCodec", "h264")
                url.addQueryParameter(
                    "VideoBitrate",
                    quality.videoBitrate.toString(),
                )
                url.addQueryParameter(
                    "AudioBitrate",
                    quality.audioBitrate.toString(),
                )
                url.addQueryParameter("PlaySessionId", parsed.PlaySessionId)
                url.addQueryParameter("TranscodingMaxAudioChannels", "6")
                url.addQueryParameter("RequireAvc", "false")
                url.addQueryParameter("SegmentContainer", "ts")
                url.addQueryParameter("MinSegments", "1")
                url.addQueryParameter("BreakOnNonKeyFrames", "true")
                url.addQueryParameter("h264-profile", "high,main,baseline,constrainedbaseline")
                url.addQueryParameter("h264-level", "51")
                url.addQueryParameter("h264-deinterlace", "true")
                url.addQueryParameter("TranscodeReasons", "VideoCodecNotSupported,AudioCodecNotSupported,ContainerBitrateExceedsLimit")

                videoList.add(Video(url.toString(), quality.description, url.toString(), subtitleTracks = subtitleList))
            }
        }

        val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
        videoList.add(Video(url, "Best", url))

        return videoList.reversed()
    }

    // ============================= Utilities ==============================

    private fun animeParse(response: Response, page: Int): AnimesPage {
        val items = json.decodeFromString<ItemsResponse>(response.body.string())
        val animesList = mutableListOf<SAnime>()

        items.Items.forEach { item ->
            val anime = SAnime.create()

            when (item.Type) {
                "Season" -> {
                    anime.setUrlWithoutDomain(
                        LinkData(
                            path = "/Shows/${item.SeriesId}/Episodes?SeasonId=${item.Id}&api_key=$apiKey",
                            seriesId = item.SeriesId!!,
                            seasonId = item.Id,
                        ).toJsonString(),
                    )
                    // Virtual if show doesn't have any sub-folders, i.e. no seasons
                    if (item.LocationType == "Virtual") {
                        anime.title = item.SeriesName!!
                        anime.thumbnail_url = "$baseUrl/Items/${item.SeriesId}/Images/Primary?api_key=$apiKey"
                    } else {
                        anime.title = "${item.SeriesName} ${item.Name}"
                        anime.thumbnail_url = "$baseUrl/Items/${item.Id}/Images/Primary?api_key=$apiKey"
                    }

                    // If season doesn't have image, fallback to series image
                    if (item.ImageTags.Primary == null) {
                        anime.thumbnail_url = "$baseUrl/Items/${item.SeriesId}/Images/Primary?api_key=$apiKey"
                    }
                    animesList.add(anime)
                }
                "Movie" -> {
                    anime.title = item.Name
                    anime.thumbnail_url = "$baseUrl/Items/${item.Id}/Images/Primary?api_key=$apiKey"
                    anime.setUrlWithoutDomain(
                        LinkData(
                            "/Users/$userId/Items/${item.Id}?api_key=$apiKey",
                            item.Id,
                            item.Id,
                        ).toJsonString(),
                    )
                    animesList.add(anime)
                }
                "BoxSet" -> {
                    val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder()

                    url.addQueryParameter("api_key", apiKey)
                    url.addQueryParameter("Recursive", "true")
                    url.addQueryParameter("SortBy", "SortName")
                    url.addQueryParameter("SortOrder", "Ascending")
                    url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
                    url.addQueryParameter("ImageTypeLimit", "1")
                    url.addQueryParameter("ParentId", item.Id)
                    url.addQueryParameter("EnableImageTypes", "Primary")

                    val response = client.newCall(
                        GET(url.build().toString(), headers = headers),
                    ).execute()
                    animesList.addAll(animeParse(response, page).animes)
                }
                else -> {
                    return@forEach
                }
            }
        }

        return AnimesPage(animesList, 20 * page < items.TotalRecordCount)
    }

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mediaLibPref = medialibPreference(screen)
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.HOSTURL_KEY,
                JFConstants.HOSTURL_TITLE,
                JFConstants.HOSTURL_DEFAULT,
                baseUrl,
                false,
                "",
                mediaLibPref,
            ),
        )
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.USERNAME_KEY,
                JFConstants.USERNAME_TITLE,
                "",
                username,
                false,
                "",
                mediaLibPref,
            ),
        )
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.PASSWORD_KEY,
                JFConstants.PASSWORD_TITLE,
                "",
                password,
                true,
                "••••••••",
                mediaLibPref,
            ),
        )
        screen.addPreference(mediaLibPref)
        val subLangPref = ListPreference(screen.context).apply {
            key = JFConstants.PREF_SUB_KEY
            title = JFConstants.PREF_SUB_TITLE
            entries = JFConstants.PREF_ENTRIES
            entryValues = JFConstants.PREF_VALUES
            setDefaultValue("eng")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(subLangPref)
        val audioLangPref = ListPreference(screen.context).apply {
            key = JFConstants.PREF_AUDIO_KEY
            title = JFConstants.PREF_AUDIO_TITLE
            entries = JFConstants.PREF_ENTRIES
            entryValues = JFConstants.PREF_VALUES
            setDefaultValue("jpn")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(audioLangPref)

        val metaTypePref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_meta_type"
            title = "Retrieve metadata from series"
            summary = """Enable this to retrieve metadata from series instead of season when applicable.""".trimMargin()
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        screen.addPreference(metaTypePref)
    }

    private abstract class MediaLibPreference(context: Context) : ListPreference(context) {
        abstract fun reload()
    }

    private fun medialibPreference(screen: PreferenceScreen) =
        (
            object : MediaLibPreference(screen.context) {
                override fun reload() {
                    this.apply {
                        key = JFConstants.MEDIALIB_KEY
                        title = JFConstants.MEDIALIB_TITLE
                        summary = "%s"

                        Thread {
                            try {
                                val mediaLibsResponse = client.newCall(
                                    GET("$baseUrl/Users/$userId/Items?api_key=$apiKey"),
                                ).execute()
                                val mediaJson = mediaLibsResponse.body.let { json.decodeFromString<ItemsResponse>(it.string()) }?.Items

                                val entriesArray = mutableListOf<String>()
                                val entriesValueArray = mutableListOf<String>()

                                if (mediaJson != null) {
                                    for (media in mediaJson) {
                                        entriesArray.add(media.Name)
                                        entriesValueArray.add(media.Id)
                                    }
                                }

                                entries = entriesArray.toTypedArray()
                                entryValues = entriesValueArray.toTypedArray()
                            } catch (ex: Exception) {
                                entries = emptyArray()
                                entryValues = emptyArray()
                            }
                        }.start()

                        setOnPreferenceChangeListener { _, newValue ->
                            val selected = newValue as String
                            val index = findIndexOfValue(selected)
                            val entry = entryValues[index] as String
                            parentId = entry
                            preferences.edit().putString(key, entry).commit()
                        }
                    }
                }
            }
            ).apply { reload() }

    private fun PreferenceScreen.editTextPreference(key: String, title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String, mediaLibPref: MediaLibPreference): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = key
            this.title = title
            summary = if ((isPassword && value.isNotEmpty()) || (!isPassword && value.isEmpty())) {
                placeholder
            } else {
                value
            }
            this.setDefaultValue(default)
            dialogTitle = title

            setOnBindEditTextListener {
                it.inputType = if (isPassword) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val newValueString = newValue as String
                    val res = preferences.edit().putString(key, newValueString).commit()
                    summary = if ((isPassword && newValueString.isNotEmpty()) || (!isPassword && newValueString.isEmpty())) {
                        placeholder
                    } else {
                        newValueString
                    }
                    val loginRes = login(true, context)
                    if (loginRes == true) {
                        mediaLibPref.reload()
                    }
                    res
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
}
