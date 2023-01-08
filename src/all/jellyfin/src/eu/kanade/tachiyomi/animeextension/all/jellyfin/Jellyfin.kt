package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.ceil
import kotlin.math.floor

class Jellyfin : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Jellyfin"

    override val lang = "all"

    override val supportsLatest = true

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

    // Popular Anime (is currently sorted by name instead of e.g. ratings)

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
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("EnableImageTypes", "Primary")

        return GET(url.toString())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val (list, hasNext) = animeParse(response)
        return AnimesPage(
            list.sortedBy { it.title },
            hasNext,
        )
    }

    // Episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val json = Json.decodeFromString<JsonObject>(response.body!!.string())

        val episodeList = mutableListOf<SEpisode>()

        // Is movie
        if (json.containsKey("Type")) {
            val episode = SEpisode.create()
            val id = json["Id"]!!.jsonPrimitive.content

            episode.episode_number = 1.0F
            episode.name = "Movie: " + json["Name"]!!.jsonPrimitive.content

            episode.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
            episodeList.add(episode)
        } else {
            val items = json["Items"]!!.jsonArray

            for (item in items) {

                val episode = SEpisode.create()
                val jsonObj = item.jsonObject

                val id = jsonObj["Id"]!!.jsonPrimitive.content

                val epNum = if (jsonObj["IndexNumber"] == null) {
                    null
                } else {
                    jsonObj["IndexNumber"]!!.jsonPrimitive.float
                }
                if (epNum != null) {
                    episode.episode_number = epNum
                    val formattedEpNum = if (floor(epNum) == ceil(epNum)) {
                        epNum.toInt().toString()
                    } else {
                        epNum.toString()
                    }
                    episode.name = "Episode $formattedEpNum: " + jsonObj["Name"]!!.jsonPrimitive.content
                } else {
                    episode.episode_number = 0F
                    episode.name = jsonObj["Name"]!!.jsonPrimitive.content
                }

                episode.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
                episodeList.add(episode)
            }
        }

        return episodeList.reversed()
    }

    private fun animeParse(response: Response): AnimesPage {
        val items = Json.decodeFromString<JsonObject>(response.body!!.string())["Items"]?.jsonArray

        val animesList = mutableListOf<SAnime>()

        if (items != null) {
            for (item in items) {
                val anime = SAnime.create()
                val jsonObj = item.jsonObject

                if (jsonObj["Type"]!!.jsonPrimitive.content == "Season") {
                    val seasonId = jsonObj["Id"]!!.jsonPrimitive.content
                    val seriesId = jsonObj["SeriesId"]!!.jsonPrimitive.content

                    anime.setUrlWithoutDomain("/Shows/$seriesId/Episodes?api_key=$apiKey&SeasonId=$seasonId")

                    // Virtual if show doesn't have any sub-folders, i.e. no seasons
                    if (jsonObj["LocationType"]!!.jsonPrimitive.content == "Virtual") {
                        anime.title = jsonObj["SeriesName"]!!.jsonPrimitive.content
                        anime.thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
                    } else {
                        anime.title = jsonObj["SeriesName"]!!.jsonPrimitive.content + " " + jsonObj["Name"]!!.jsonPrimitive.content
                        anime.thumbnail_url = "$baseUrl/Items/$seasonId/Images/Primary?api_key=$apiKey"
                    }

                    // If season doesn't have image, fallback to series image
                    if (jsonObj["ImageTags"].toString() == "{}") {
                        anime.thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
                    }
                } else if (jsonObj["Type"]!!.jsonPrimitive.content == "Movie") {
                    val id = jsonObj["Id"]!!.jsonPrimitive.content

                    anime.title = jsonObj["Name"]!!.jsonPrimitive.content
                    anime.thumbnail_url = "$baseUrl/Items/$id/Images/Primary?api_key=$apiKey"

                    anime.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
                } else {
                    continue
                }

                animesList.add(anime)
            }
        }

        val hasNextPage = (items?.size?.compareTo(20) ?: -1) >= 0
        return AnimesPage(animesList, hasNextPage)
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val item = Json.decodeFromString<JsonObject>(response.body!!.string())
        val id = item["Id"]!!.jsonPrimitive.content

        val sessionResponse = client.newCall(
            GET(
                "$baseUrl/Items/$id/PlaybackInfo?userId=$userId&api_key=$apiKey"
            )
        ).execute()
        val sessionJson = Json.decodeFromString<JsonObject>(sessionResponse.body!!.string())
        val sessionId = sessionJson["PlaySessionId"]!!.jsonPrimitive.content
        val mediaStreams = sessionJson["MediaSources"]!!.jsonArray[0].jsonObject["MediaStreams"]?.jsonArray

        val subtitleList = mutableListOf<Track>()

        val prefSub = preferences.getString(JFConstants.PREF_SUB_KEY, "eng")!!
        val prefAudio = preferences.getString(JFConstants.PREF_AUDIO_KEY, "jpn")!!

        var audioIndex = 1
        var subIndex: Int? = null
        var width = 1920
        var height = 1080

        // Get subtitle streams and audio index
        if (mediaStreams != null) {
            for (media in mediaStreams) {
                val index = media.jsonObject["Index"]!!.jsonPrimitive.int
                val codec = media.jsonObject["Codec"]!!.jsonPrimitive.content
                val lang = media.jsonObject["Language"]
                val supportsExternalStream = media.jsonObject["SupportsExternalStream"]!!.jsonPrimitive.boolean

                val type = media.jsonObject["Type"]!!.jsonPrimitive.content
                if (type == "Subtitle" && supportsExternalStream) {
                    val subUrl = "$baseUrl/Videos/$id/$id/Subtitles/$index/0/Stream.$codec?api_key=$apiKey"
                    // TODO: add ttf files in media attachment (if possible)
                    val title = media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content
                    if (lang != null) {
                        if (lang.jsonPrimitive.content == prefSub) {
                            try {
                                subtitleList.add(0, Track(subUrl, title))
                            } catch (e: Error) {
                                subIndex = index
                            }
                        } else {
                            try {
                                subtitleList.add(Track(subUrl, title))
                            } catch (_: Error) {}
                        }
                    } else {
                        try {
                            subtitleList.add(Track(subUrl, title))
                        } catch (_: Error) {}
                    }
                } else if (type == "Subtitle") {
                    if (lang != null) {
                        if (lang.jsonPrimitive.content == prefSub) {
                            subIndex = index
                        }
                    }
                }

                if (type == "Audio") {
                    if (lang != null) {
                        if (lang.jsonPrimitive.content == prefAudio) {
                            audioIndex = index
                        }
                    }
                }

                if (media.jsonObject["Type"]!!.jsonPrimitive.content == "Video") {
                    width = media.jsonObject["Width"]!!.jsonPrimitive.int
                    height = media.jsonObject["Height"]!!.jsonPrimitive.int
                }
            }
        }

        // Loop over qualities
        for (quality in JFConstants.QUALITIES_LIST) {
            if (width < quality.width && height < quality.height) {
                val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
                videoList.add(Video(url, "Best", url))

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
                    "VideoBitrate", quality.videoBitrate.toString()
                )
                url.addQueryParameter(
                    "AudioBitrate", quality.audioBitrate.toString()
                )
                url.addQueryParameter("PlaySessionId", sessionId)
                url.addQueryParameter("TranscodingMaxAudioChannels", "6")
                url.addQueryParameter("RequireAvc", "false")
                url.addQueryParameter("SegmentContainer", "ts")
                url.addQueryParameter("MinSegments", "1")
                url.addQueryParameter("BreakOnNonKeyFrames", "true")
                url.addQueryParameter("h264-profile", "high,main,baseline,constrainedbaseline")
                url.addQueryParameter("h264-level", "51")
                url.addQueryParameter("h264-deinterlace", "true")
                url.addQueryParameter("TranscodeReasons", "VideoCodecNotSupported,AudioCodecNotSupported,ContainerBitrateExceedsLimit")

                try {
                    videoList.add(Video(url.toString(), quality.description, url.toString(), subtitleTracks = subtitleList))
                } catch (_: Error) {
                    videoList.add(Video(url.toString(), quality.description, url.toString()))
                }
            }
        }

        val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
        videoList.add(Video(url, "Best", url))

        return videoList.reversed()
    }

    // search

    override fun searchAnimeParse(response: Response) = animeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (parentId.isEmpty()) {
            throw Exception("Select library in the extension settings.")
        }
        if (query.isBlank()) {
            throw Exception("Search query blank")
        }
        val startIndex = (page - 1) * 20

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("StartIndex", startIndex.toString())
        url.addQueryParameter("Limit", "20")
        url.addQueryParameter("Recursive", "true")
        url.addQueryParameter("SortBy", "SortName")
        url.addQueryParameter("SortOrder", "Ascending")
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("EnableImageTypes", "Primary")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("SearchTerm", query)

        return GET(url.toString())
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        val infoArr = anime.url.split("/").toTypedArray()

        val id = if (infoArr[1] == "Users") {
            infoArr[4].split("?").toTypedArray()[0]
        } else {
            infoArr[2]
        }

        val url = "$baseUrl/Users/$userId/Items/$id".toHttpUrl().newBuilder()

        url.addQueryParameter("api_key", apiKey)
        url.addQueryParameter("fields", "Studios")

        return GET(url.toString())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val item = Json.decodeFromString<JsonObject>(response.body!!.string())

        val anime = SAnime.create()

        anime.author = if (item["Studios"]!!.jsonArray.isEmpty()) {
            ""
        } else {
            item["Studios"]!!.jsonArray[0].jsonObject["Name"]!!.jsonPrimitive.content
        }

        anime.description = item["Overview"]?.let {
            Jsoup.parse(it.jsonPrimitive.content.replace("<br>", "br2n")).text().replace("br2n", "\n")
        } ?: ""

        if (item["Genres"]!!.jsonArray.isEmpty()) {
            anime.genre = ""
        } else {
            val genres = mutableListOf<String>()

            for (genre in item["Genres"]!!.jsonArray) {
                genres.add(genre.jsonPrimitive.content)
            }
            anime.genre = genres.joinToString()
        }

        anime.status = item["Status"]?.let {
            if (it.jsonPrimitive.content == "Ended") SAnime.COMPLETED else SAnime.UNKNOWN
        } ?: SAnime.UNKNOWN

        if (item["Type"]!!.jsonPrimitive.content == "Season") {
            val seasonId = item["Id"]!!.jsonPrimitive.content
            val seriesId = item["SeriesId"]!!.jsonPrimitive.content

            anime.setUrlWithoutDomain("/Shows/$seriesId/Episodes?api_key=$apiKey&SeasonId=$seasonId")

            // Virtual if show doesn't have any sub-folders, i.e. no seasons
            if (item["LocationType"]!!.jsonPrimitive.content == "Virtual") {
                anime.title = item["SeriesName"]!!.jsonPrimitive.content
                anime.thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
            } else {
                anime.title = item["SeriesName"]!!.jsonPrimitive.content + " " + item["Name"]!!.jsonPrimitive.content
                anime.thumbnail_url = "$baseUrl/Items/$seasonId/Images/Primary?api_key=$apiKey"
            }

            // If season doesn't have image, fallback to series image
            if (item["ImageTags"].toString() == "{}") {
                anime.thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
            }
        } else if (item["Type"]!!.jsonPrimitive.content == "Movie") {
            val id = item["Id"]!!.jsonPrimitive.content

            anime.title = item["Name"]!!.jsonPrimitive.content
            anime.thumbnail_url = "$baseUrl/Items/$id/Images/Primary?api_key=$apiKey"

            anime.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
        }

        return anime
    }

    // Latest

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
        url.addQueryParameter("includeItemTypes", "Movie,Series,Season")
        url.addQueryParameter("ImageTypeLimit", "1")
        url.addQueryParameter("ParentId", parentId)
        url.addQueryParameter("EnableImageTypes", "Primary")

        return GET(url.toString())
    }

    override fun latestUpdatesParse(response: Response) = animeParse(response)

    // Filters - not used

    // settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mediaLibPref = medialibPreference(screen)
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.HOSTURL_KEY, JFConstants.HOSTURL_TITLE, JFConstants.HOSTURL_DEFAULT, baseUrl, false, "", mediaLibPref
            )
        )
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.USERNAME_KEY, JFConstants.USERNAME_TITLE, "", username, false, "", mediaLibPref
            )
        )
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.PASSWORD_KEY, JFConstants.PASSWORD_TITLE, "", password, true, "••••••••", mediaLibPref
            )
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
                                    GET("$baseUrl/Users/$userId/Items?api_key=$apiKey")
                                ).execute()
                                val mediaJson = mediaLibsResponse.body?.let { Json.decodeFromString<JsonObject>(it.string()) }?.get("Items")?.jsonArray

                                val entriesArray = mutableListOf<String>()
                                val entriesValueArray = mutableListOf<String>()

                                if (mediaJson != null) {
                                    for (media in mediaJson) {
                                        entriesArray.add(media.jsonObject["Name"]!!.jsonPrimitive.content)
                                        entriesValueArray.add(media.jsonObject["Id"]!!.jsonPrimitive.content)
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
