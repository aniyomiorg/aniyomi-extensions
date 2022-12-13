package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
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
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Jellyfin : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Jellyfin"

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl = JFConstants.getPrefHostUrl(preferences)
    private val apiKey = JFConstants.getPrefApiKey(preferences)

    // TODO: dont hardcode this
    private val userId = "d40091e38ffe426a9dedc7aa1297d0fa"

    private fun log(obj: Any, name: String, inObj: Any) {
        Log.i("JF_$name", "INPUT: ${inObj::class} - $inObj \n${obj::class} - $obj")
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = throw Exception("not used")

    override fun popularAnimeRequest(page: Int): Request {
        val parentId = preferences.getString("library_pref", "")
        val url = "$baseUrl/Users/$userId/Items?api_key=$apiKey&SortBy=SortName&SortOrder=Ascending&includeItemTypes=Season,Movie&Recursive=true&ImageTypeLimit=1&EnableImageTypes=Primary%252CBackdrop%252CBanner%252CThumb&StartIndex=0&Limit=100&ParentId=$parentId"
        return GET(url)
    }

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = AnimeParse(response)

        // Currently sorts by name
        animesList.sortBy { it.title }

        return AnimesPage(animesList, hasNextPage)
    }

    override fun popularAnimeNextPageSelector(): String = throw Exception("not used")

    // Episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val json = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }

        val episodeList = mutableListOf<SEpisode>()

        // Is movie
        if (json!!.containsKey("Type")) {
            val episode = SEpisode.create()
            val id = json["Id"]!!.jsonPrimitive.content

            episode.episode_number = 1.0F
            episode.name = json["Name"]!!.jsonPrimitive.content

            episode.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
            episodeList.add(episode)
        } else {
            val items = json?.get("Items")!!

            for (item in 0 until items.jsonArray.size) {

                val episode = SEpisode.create()
                val jsonObj = JsonObject(items.jsonArray[item].jsonObject)

                val id = jsonObj["Id"]!!.jsonPrimitive.content

                if (jsonObj["IndexNumber"] == null) {
                    episode.episode_number = 0.0F
                } else {
                    episode.episode_number = jsonObj["IndexNumber"]!!.jsonPrimitive.float
                }
                episode.name = "${episode.episode_number} ${jsonObj["Name"]!!.jsonPrimitive.content}"

                episode.setUrlWithoutDomain("/Users/$userId/Items/$id?api_key=$apiKey")
                episodeList.add(episode)
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun AnimeParse(response: Response): Pair<MutableList<SAnime>, Boolean> {
        val items = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }?.get("Items")!!

        val animesList = mutableListOf<SAnime>()

        if (items != null) {
            for (item in 0 until items.jsonArray.size) {
                val anime = SAnime.create()
                val jsonObj = JsonObject(items.jsonArray[item].jsonObject)

                if (jsonObj["Type"]!!.jsonPrimitive.content == "Season") {
                    val seasonId = jsonObj["Id"]!!.jsonPrimitive.content
                    var seriesId = jsonObj["SeriesId"]!!.jsonPrimitive.content

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

        return Pair(animesList, false)
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val item = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }
        val id = item?.get("Id")!!.jsonPrimitive.content

        val sessionResponse = client.newCall(
            GET(
                "$baseUrl/Items/$id/PlaybackInfo?userId=$userId&api_key=$apiKey"
            )
        ).execute()
        val sessionJson = sessionResponse.body?.let { Json.decodeFromString<JsonObject>(it.string()) }
        val sessionId = sessionJson?.get("PlaySessionId")!!.jsonPrimitive.content
        val mediaStreams = sessionJson?.get("MediaSources")!!.jsonArray[0].jsonObject["MediaStreams"]?.jsonArray

        val subtitleList = mutableListOf<Track>()

        val prefSub = preferences.getString(JFConstants.PREF_SUB_KEY, "eng")
        val prefAudio = preferences.getString(JFConstants.PREF_AUDIO_KEY, "jpn")

        var audioIndex = 1
        var width = 1920
        var height = 1080

        // Get subtitle streams and audio index
        if (mediaStreams != null) {
            for (media in mediaStreams) {
                if (media.jsonObject["Type"]!!.jsonPrimitive.content == "Subtitle") {
                    val subUrl = "$baseUrl/Videos/$id/$id/Subtitles/${media.jsonObject["Index"]!!.jsonPrimitive.int}/0/Stream.${media.jsonObject["Codec"]!!.jsonPrimitive.content}?api_key=$apiKey"
                    // TODO: add ttf files in media attachment (if possible)
                    val lang = media.jsonObject["Language"]
                    if (lang != null) {
                        if (lang.jsonPrimitive.content == prefSub) {
                            subtitleList.add(
                                0, Track(subUrl, "${media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content}")
                            )
                        } else {
                            subtitleList.add(
                                Track(subUrl, "${media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content}")
                            )
                        }
                    } else {
                        subtitleList.add(
                            Track(subUrl, "${media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content}")
                        )
                    }
                }

                if (media.jsonObject["Type"]!!.jsonPrimitive.content == "Audio") {
                    val lang = media.jsonObject["Language"]
                    if (lang != null) {
                        if (lang.jsonPrimitive.content == prefAudio) {
                            audioIndex = media.jsonObject["Index"]!!.jsonPrimitive.int
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
            if (width < quality[0] as Int && height < quality[1] as Int) {
                val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
                videoList.add(Video(url, "Best", url))

                return videoList.reversed()
            } else {
                val url = "$baseUrl/videos/$id/main.m3u8?VideoCodec=h264&AudioCodec=aac,mp3&AudioStreamIndex=$audioIndex&${quality[2]}&PlaySessionId=$sessionId&api_key=$apiKey&TranscodingMaxAudioChannels=6&RequireAvc=false&Tag=27d6b71bb94bfec39b606555e84c6bfe&SegmentContainer=ts&MinSegments=1&BreakOnNonKeyFrames=True&h264-profile=high,main,baseline,constrainedbaseline&h264-level=51&h264-deinterlace=true&TranscodeReasons=VideoCodecNotSupported,AudioCodecNotSupported,ContainerBitrateExceedsLimit"
                videoList.add(Video(url, quality[3] as String, url, subtitleTracks = subtitleList))
            }
        }

        val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
        videoList.add(Video(url, "Best", url))

        return videoList.reversed()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun searchAnimeNextPageSelector(): String = throw Exception("not used")

    override fun searchAnimeSelector(): String = throw Exception("not used")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = AnimeParse(response)

        // Currently sorts by name
        animesList.sortBy { it.title }

        return AnimesPage(animesList, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            val searchResponse = client.newCall(
                GET("$baseUrl/Users/$userId/Items?api_key=$apiKey&searchTerm=$query&Limit=2&Recursive=true&IncludeItemTypes=Series,Movie")
            ).execute()

            val jsonArr = searchResponse.body?.let { Json.decodeFromString<JsonObject>(it.string()) }?.get("Items")

            if (jsonArr == buildJsonArray { }) {
                return GET("$baseUrl/Users/$userId/Items?api_key=$apiKey&SortBy=SortName&SortOrder=Ascending&includeItemTypes=Season,Movie&Recursive=true&ImageTypeLimit=1&EnableImageTypes=Primary%252CBackdrop%252CBanner%252CThumb&StartIndex=0&Limit=100&ParentId=$userId")
            }

            val firstItem = jsonArr!!.jsonArray[0]
            val id = firstItem.jsonObject["Id"]!!.jsonPrimitive.content

            GET("$baseUrl/Users/$userId/Items?api_key=$apiKey&SortBy=SortName&SortOrder=Ascending&includeItemTypes=Season,Movie&Recursive=true&ImageTypeLimit=1&EnableImageTypes=Primary%252CBackdrop%252CBanner%252CThumb&StartIndex=0&Limit=100&ParentId=$id")
        } else {
            // TODO: Filters

            val url = "$baseUrl/Users/$userId/Items".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> url.addQueryParameter("GenreIds", filter.toUriPart())
                    else -> {}
                }
            }

            for (paramPair in JFConstants.DEFAULT_PARAMS) {
                url.addEncodedQueryParameter(paramPair.first, paramPair.second)
            }
            url.addEncodedQueryParameter("api_key", apiKey)
            url.addEncodedQueryParameter("SortOrder", "Ascending")

            GET(url.toString())
        }
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        val infoArr = anime.url.split("/").toTypedArray()

        Log.i("AnimeDetInfo", infoArr.toString())

        val id = if (infoArr[1] == "Users") {
            infoArr[4].split("?").toTypedArray()[0]
        } else {
            infoArr[2]
        }

        Log.i("AnimeDetUrl", "$baseUrl/Users/$userId/Items/$id?api_key=$apiKey&fields=%5B%27DateCreated%27%2C+%27Studios%27%5D")
        return GET("$baseUrl/Users/$userId/Items/$id?api_key=$apiKey&fields=%5B%27DateCreated%27%2C+%27Studios%27%5D")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val item = Json.decodeFromString<JsonObject>(document.text())

        val anime = SAnime.create()

        anime.author = if (item["Studios"]!!.jsonArray.isEmpty()) {
            ""
        } else {
            item["Studios"]!!.jsonArray[0].jsonObject["Name"]!!.jsonPrimitive.content
        }

        anime.description = item["Overview"]?.let {
            Jsoup.parse(it.jsonPrimitive.content.replace("<br>", "br2n")).text().replace("br2n", "\n")
        } ?: ""

        anime.title = item["OriginalTitle"]?.let { it!!.jsonPrimitive.content } ?: item["Name"]!!.jsonPrimitive.content

        if (item["Genres"]!!.jsonArray.isEmpty()) {
            anime.genre = ""
        } else {
            val genres = mutableListOf<String>()

            for (genre in 0 until item["Genres"]?.jsonArray?.size!!) {
                genres.add(
                    item["Genres"]?.jsonArray!![genre]?.jsonPrimitive?.content
                )
            }
            anime.genre = genres.joinToString(separator = ", ")
        }

        anime.status = item["Status"]?.let {
            if (it.jsonPrimitive.content == "Ended") SAnime.COMPLETED else SAnime.COMPLETED
        } ?: SAnime.UNKNOWN

        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request {
        val parentId = preferences.getString("library_pref", "")
        return GET("$baseUrl/Users/$userId/Items?api_key=$apiKey&SortBy=DateCreated&SortOrder=Descending&IncludeItemTypes=Season,Movie&Recursive=true&ImageTypeLimit=1&EnableImageTypes=Primary%252CBackdrop%252CBanner%252CThumb&StartIndex=0&Limit=100&ParentId=$parentId")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = AnimeParse(response)
        return AnimesPage(animesList, hasNextPage)
    }

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filters - not implemented yet

    /*
    override fun getFilterList(): AnimeFilterList {
        AnimeFilterList(
            AnimeFilter.Header("NOTE: Ignored if using text search!"),
            AnimeFilter.Separator(),
            GenreFilter(getGenreList())
        )
    }
    */

    private class GenreFilter(vals: List<Pair<String, String>>) : UriPartFilter("Genres", vals)

    private fun getGenreList(): List<Pair<String, String>> {
        /*
        val genreArray = mutableListOf<Pair<String, String>>()

        val genresResponse = client.newCall(
            GET("$baseUrl/Genres?api_key=$apiKey", cache = CacheControl.FORCE_NETWORK)
        ).execute()

        val jsonArr = genresResponse.body?.let { Json.decodeFromString<JsonObject>(it.string()) }?.get("Items")

        Log.i("GenreJsonArr", jsonArr.toString())

        if (jsonArr != buildJsonArray { }) {
            for (item in 0 until jsonArr!!.jsonArray.size) {
                val jsonObj = JsonObject(jsonArr!!.jsonArray[item].jsonObject)

                Log.i("GenreJsonObj", jsonObj.toString())

                genreArray.add(
                    Pair(
                        jsonObj["Name"]!!.jsonPrimitive.content,
                        jsonObj["Id"]!!.jsonPrimitive.content
                    )
                )
            }
        }

        Log.i("GenreArrayFirstT", genreArray[0].first)
        return genreArray
         */

        return listOf(
            Pair("Action", "ce06903d834d2c3417e0889dd4049f3b"),
            Pair("Adventure", "51cec9645b896084d12b259acd05ccb1"),
            Pair("Anime", "f89b4d4d7733020ed2721d8fec37f26c"),
            Pair("Comedy", "08d31605d366d63a7a924f944b4417f1"),
            Pair("Drama", "090eac6e9de4fe1fbc194e5b96691277"),
            Pair("Ecchi", "d7b8e7321af8c279341caeced90a1bb1"),
            Pair("Fantasy", "a30dcc65be22eb3c21c03f7c1c7a57d1"),
            Pair("Horror", "8b9bd9a3eddad02f2b759b4938fdd0b8"),
            Pair("Mahou Shoujo", "f36fb684438bbf8c5b80c7ecea1b932f"),
            Pair("Mystery", "d3a0ead52489743e5a68704142092c71"),
            Pair("Psychological", "13fcb116f8048f20769597c91946932f"),
            Pair("Romance", "1ffc72e19987e5fa4047c6a6870646cf"),
            Pair("Sci-Fi", "d3bf560475125eed829c435f3d8329e3"),
            Pair("Slice of Life", "345780dc39f3b1fa11e4776c97a79ad5"),
            Pair("Supernatural", "68c458f4829f530c664a01e748e010ae"),
            Pair("Thriller", "4936f5b1a6f84f0b0aa2657368a5b364")
        )
    }

    open class UriPartFilter(displayName: String, private val vals: List<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.APIKEY_TITLE, JFConstants.APIKEY_DEFAULT, apiKey, true, ""
            )
        )
        screen.addPreference(
            screen.editTextPreference(
                JFConstants.HOSTURL_TITLE, JFConstants.HOSTURL_DEFAULT, baseUrl, false, ""
            )
        )
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
        val mediaLibPref = ListPreference(screen.context).apply {
            key = "library_pref"
            title = "Media Library"
            summary = "%s"

            if (apiKey == "" || userId == "" || baseUrl == "") {
                this.setEnabled(false)
                this.title = "Please Set Host url, API key, and User first"
            } else {
                this.setEnabled(true)
                this.title = title
            }

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
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(mediaLibPref)
    }

    private fun PreferenceScreen.editTextPreference(title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String): EditTextPreference {
        return EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value.ifEmpty { placeholder }
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Restart Aniyomi to apply new settings.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    Log.e("Jellyfin", "Error setting preference.", e)
                    false
                }
            }
        }
    }
}
