package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.annotation.SuppressLint
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.ceil
import kotlin.math.floor

class Jellyfin(private val suffix: String) : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Jellyfin$suffix"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            },
        )

        // Install the all-trusting trust manager
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        // Create an ssl socket factory with our all-trusting manager
        val sslSocketFactory = sslContext.socketFactory

        return network.client.newBuilder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }.build()
    }

    override val client by lazy {
        if (preferences.getBoolean("preferred_trust_all_certs", false)) {
            getUnsafeOkHttpClient()
        } else {
            network.client
        }.newBuilder()
            .dns(Dns.SYSTEM)
            .build()
    }

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
                if (username != "demo") return null
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

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        return client.newCall(popularAnimeRequest(page))
            .awaitSuccess()
            .use { response ->
                popularAnimeParsePage(response, page)
            }
    }

    override fun popularAnimeRequest(page: Int): Request {
        require(parentId.isNotEmpty()) { "Select library in the extension settings." }
        val startIndex = (page - 1) * 20

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", apiKey)
            addQueryParameter("StartIndex", startIndex.toString())
            addQueryParameter("Limit", "20")
            addQueryParameter("Recursive", "true")
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter("includeItemTypes", "Movie,Season,BoxSet")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("ParentId", parentId)
            addQueryParameter("EnableImageTypes", "Primary")
        }

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

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        return client.newCall(latestUpdatesRequest(page))
            .awaitSuccess()
            .use { response ->
                latestUpdatesParsePage(response, page)
            }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        require(parentId.isNotEmpty()) { "Select library in the extension settings." }
        val startIndex = (page - 1) * 20

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", apiKey)
            addQueryParameter("StartIndex", startIndex.toString())
            addQueryParameter("Limit", "20")
            addQueryParameter("Recursive", "true")
            addQueryParameter("SortBy", "DateCreated,SortName")
            addQueryParameter("SortOrder", "Descending")
            addQueryParameter("includeItemTypes", "Movie,Season,BoxSet")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("ParentId", parentId)
            addQueryParameter("EnableImageTypes", "Primary")
        }

        return GET(url.toString())
    }

    private fun latestUpdatesParsePage(response: Response, page: Int) = animeParse(response, page)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun searchAnimeParse(response: Response) = throw Exception("Not used")

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        require(parentId.isNotEmpty()) { "Select library in the extension settings." }
        val startIndex = (page - 1) * 5

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", apiKey)
            addQueryParameter("StartIndex", startIndex.toString())
            addQueryParameter("Limit", "5")
            addQueryParameter("Recursive", "true")
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter("IncludeItemTypes", "Series,Movie,BoxSet")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("EnableImageTypes", "Primary")
            addQueryParameter("ParentId", parentId)
            addQueryParameter("SearchTerm", query)
        }

        val items = client.newCall(
            GET(url.build().toString(), headers = headers),
        ).execute().parseAs<ItemsResponse>()

        val movieList = items.Items.filter { it.Type == "Movie" }
        val nonMovieList = items.Items.filter { it.Type != "Movie" }

        val animeList = getAnimeFromMovie(movieList) + nonMovieList.flatMap {
            getAnimeFromId(it.Id)
        }

        return AnimesPage(animeList, 5 * page < items.TotalRecordCount)
    }

    private fun getAnimeFromMovie(movieList: List<ItemsResponse.Item>): List<SAnime> {
        return movieList.map {
            SAnime.create().apply {
                title = it.Name
                thumbnail_url = "$baseUrl/Items/${it.Id}/Images/Primary?api_key=$apiKey"
                url = LinkData(
                    "/Users/$userId/Items/${it.Id}?api_key=$apiKey",
                    it.Id,
                    it.Id,
                ).toJsonString()
            }
        }
    }

    private fun getAnimeFromId(id: String): List<SAnime> {
        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", apiKey)
            addQueryParameter("Recursive", "true")
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter("includeItemTypes", "Movie,Series,Season")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("EnableImageTypes", "Primary")
            addQueryParameter("ParentId", id)
        }

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

        val url = "$baseUrl/Users/$userId/Items/$infoId".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", apiKey)
            addQueryParameter("fields", "Studios")
        }

        return GET(url.toString())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val info = response.parseAs<ItemsResponse.Item>()

        val anime = SAnime.create()

        if (info.Genres != null) anime.genre = info.Genres.joinToString(", ")

        if (!info.Studios.isNullOrEmpty()) {
            anime.author = info.Studios.mapNotNull { it.Name }.joinToString(", ")
        } else if (info.SeriesStudio != null) anime.author = info.SeriesStudio

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

        if (info.Type == "Movie") {
            anime.status = SAnime.COMPLETED
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
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "Movie ${parsed.Name}"
                    episode_number = 1.0F
                },
            )
        } else {
            val parsed = response.parseAs<ItemsResponse>()

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
        val id = response.parseAs<ItemsResponse.Item>().Id

        val parsed = client.newCall(
            GET("$baseUrl/Items/$id/PlaybackInfo?userId=$userId&api_key=$apiKey"),
        ).execute().parseAs<SessionResponse>()

        val subtitleList = mutableListOf<Track>()
        val externalSubtitleList = mutableListOf<Track>()

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
                                        externalSubtitleList.add(0, Track(subUrl, media.DisplayTitle!!))
                                    }
                                    subtitleList.add(0, Track(subUrl, media.DisplayTitle!!))
                                } catch (e: Error) {
                                    subIndex = media.Index
                                }
                            } else {
                                if (media.IsExternal) {
                                    externalSubtitleList.add(Track(subUrl, media.DisplayTitle!!))
                                }
                                subtitleList.add(Track(subUrl, media.DisplayTitle!!))
                            }
                        } else {
                            if (media.IsExternal) {
                                externalSubtitleList.add(Track(subUrl, media.DisplayTitle!!))
                            }
                            subtitleList.add(Track(subUrl, media.DisplayTitle!!))
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
                videoList.add(Video(url, "Source", url, subtitleTracks = externalSubtitleList))

                return videoList.reversed()
            } else {
                val url = "$baseUrl/videos/$id/main.m3u8".toHttpUrl().newBuilder().apply {
                    addQueryParameter("api_key", apiKey)
                    addQueryParameter("VideoCodec", "h264")
                    addQueryParameter("AudioCodec", "aac,mp3")
                    addQueryParameter("AudioStreamIndex", audioIndex.toString())
                    subIndex?.let { addQueryParameter("SubtitleStreamIndex", it.toString()) }
                    addQueryParameter("VideoCodec", "h264")
                    addQueryParameter("VideoCodec", "h264")
                    addQueryParameter(
                        "VideoBitrate",
                        quality.videoBitrate.toString(),
                    )
                    addQueryParameter(
                        "AudioBitrate",
                        quality.audioBitrate.toString(),
                    )
                    addQueryParameter("PlaySessionId", parsed.PlaySessionId)
                    addQueryParameter("TranscodingMaxAudioChannels", "6")
                    addQueryParameter("RequireAvc", "false")
                    addQueryParameter("SegmentContainer", "ts")
                    addQueryParameter("MinSegments", "1")
                    addQueryParameter("BreakOnNonKeyFrames", "true")
                    addQueryParameter("h264-profile", "high,main,baseline,constrainedbaseline")
                    addQueryParameter("h264-level", "51")
                    addQueryParameter("h264-deinterlace", "true")
                    addQueryParameter("TranscodeReasons", "VideoCodecNotSupported,AudioCodecNotSupported,ContainerBitrateExceedsLimit")
                }

                videoList.add(Video(url.toString(), quality.description, url.toString(), subtitleTracks = subtitleList))
            }
        }

        val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
        videoList.add(Video(url, "Source", url, subtitleTracks = externalSubtitleList))

        return videoList.reversed()
    }

    // ============================= Utilities ==============================

    private fun animeParse(response: Response, page: Int): AnimesPage {
        val items = response.parseAs<ItemsResponse>()

        val animeList = items.Items.flatMap { item ->
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
                    listOf(anime)
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
                    listOf(anime)
                }
                "BoxSet" -> {
                    val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
                        addQueryParameter("api_key", apiKey)
                        addQueryParameter("Recursive", "true")
                        addQueryParameter("SortBy", "SortName")
                        addQueryParameter("SortOrder", "Ascending")
                        addQueryParameter("includeItemTypes", "Movie,Series,Season")
                        addQueryParameter("ImageTypeLimit", "1")
                        addQueryParameter("ParentId", item.Id)
                        addQueryParameter("EnableImageTypes", "Primary")
                    }

                    val response = client.newCall(
                        GET(url.build().toString(), headers = headers),
                    ).execute()
                    animeParse(response, page).animes
                }
                else -> emptyList()
            }
        }

        return AnimesPage(animeList, 20 * page < items.TotalRecordCount)
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

        val trustCertificatePref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_trust_all_certs"
            title = "Trust all certificates"
            summary = "Requires app restart to take effect."
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        screen.addPreference(trustCertificatePref)
    }

    private abstract class MediaLibPreference(context: Context) : ListPreference(context) {
        abstract fun reload()
    }

    private fun medialibPreference(screen: PreferenceScreen) =
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
        }.apply { reload() }

    private fun getSummary(isPassword: Boolean, value: String, placeholder: String) = when {
        isPassword && value.isNotEmpty() || !isPassword && value.isEmpty() -> placeholder
        else -> value
    }

    private fun PreferenceScreen.editTextPreference(key: String, title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String, mediaLibPref: MediaLibPreference): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = key
            this.title = title
            summary = getSummary(isPassword, value, placeholder)
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
                    summary = getSummary(isPassword, newValueString, placeholder)
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
