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
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

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
    // d40091e38ffe426a9dedc7aa1297d0fa

    private val userId = "9db271c2c4a74fcd8320f8fa32d63e85"

    // b434bd24836c87d7ed200dcf350c0a2a
    private val parentId = "bc3a7d1d3dac1f10e170387270df67fa"

    private fun log(obj: Any, name: String, inObj: Any) {
        Log.i("JF_$name", "INPUT: ${inObj::class} - $inObj \n${obj::class} - $obj")
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = throw Exception("not used")

    override fun popularAnimeRequest(page: Int): Request {
        Log.i("JELLYFINURL", "$baseUrl/Users/$userId/Items?api_key=$apiKey&SortBy=SortName&SortOrder=Ascending&IncludeItemTypes=Season&Recursive=true&ImageTypeLimit=1&EnableImageTypes=Primary%252CBackdrop%252CBanner%252CThumb&StartIndex=0&Limit=100&ParentId=$parentId")
        return GET("$baseUrl/Users/$userId/Items?api_key=$apiKey&SortBy=SortName&SortOrder=Ascending&IncludeItemTypes=Season&Recursive=true&ImageTypeLimit=1&EnableImageTypes=Primary%252CBackdrop%252CBanner%252CThumb&StartIndex=0&Limit=100&ParentId=$parentId")
    }

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = AnimeParse(response)

        // Currently sorts by name, TODO: change to "CommunityRating" and get that working with seasons
        animesList.sortBy { it.title }

        return AnimesPage(animesList, hasNextPage)
    }

    override fun popularAnimeNextPageSelector(): String = throw Exception("not used")

    // Episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val items = response.body?.let { Json.decodeFromString<JsonObject>(it.string()) }?.get("Items")!!

        val episodeList = mutableListOf<SEpisode>()

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

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

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

                    if (media.jsonObject["Language"]!!.jsonPrimitive.content == prefSub) {
                        subtitleList.add(
                            0, Track(subUrl, "${media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content}")
                        )
                    } else {
                        subtitleList.add(
                            Track(subUrl, "${media.jsonObject["DisplayTitle"]!!.jsonPrimitive.content}")
                        )
                    }
                }

                if (media.jsonObject["Type"]!!.jsonPrimitive.content == "Audio") {
                    if (media.jsonObject["Language"]!!.jsonPrimitive.content == prefAudio) {
                        audioIndex = media.jsonObject["Index"]!!.jsonPrimitive.int
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
                Log.i("JELLYFINSTREAMURL", url)
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

    // search - not implemented yet

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("article a").attr("href"))
        anime.title = element.select("h2.Title").text()
        anime.thumbnail_url = "https:" + element.select("div.Image figure img").attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.nav-links a:last-child"

    override fun searchAnimeSelector(): String = "ul.MovieList li"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/category/".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
                }
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.split("/").toTypedArray()[2]
        return GET("$baseUrl/Users/$userId/Items/$id?api_key=$apiKey&fields=%5B%27DateCreated%27%2C+%27Studios%27%5D")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val item = Json.decodeFromString<JsonObject>(document.text())

        val anime = SAnime.create()

        val studiosArr = item["Studios"]?.jsonArray
        if (studiosArr != null) {
            anime.author = studiosArr[0].jsonObject["Name"]!!.jsonPrimitive.content
        }

        anime.description = item["Overview"]!!.jsonPrimitive.content
        anime.title = item["OriginalTitle"]!!.jsonPrimitive.content
        anime.genre = item["Genres"]?.jsonArray?.joinToString(", ")
        anime.status = if (item["Status"]?.jsonPrimitive?.content == "Ended") SAnime.COMPLETED else SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/Users/$userId/Items?api_key=$apiKey&SortBy=DateCreated&SortOrder=Descending&IncludeItemTypes=Season&Recursive=true&ImageTypeLimit=1&EnableImageTypes=Primary%252CBackdrop%252CBanner%252CThumb&StartIndex=0&Limit=100&ParentId=b434bd24836c87d7ed200dcf350c0a2a")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val (animesList, hasNextPage) = AnimeParse(response)
        return AnimesPage(animesList, hasNextPage)
    }

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filters - not implemented yet

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList())
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Genres", vals)

    private fun getGenreList() = arrayOf(
        Pair("Action & Adventure", "action-adventure"),
        Pair("Adventure", "aventure"),
        Pair("Animation", "animation"),
        Pair("Comedy", "comedy"),
        Pair("Crime", "crime"),
        Pair("Disney", "disney"),
        Pair("Drama", "drama"),
        Pair("Family", "family"),
        Pair("Fantasy", "fantasy"),
        Pair("History", "fistory"),
        Pair("Horror", "horror"),
        Pair("Kids", "kids"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Reality", "reality"),
        Pair("Romance", "romance"),
        Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
        Pair("Science Fiction", "science-fiction"),
        Pair("Thriller", "thriller"),
        Pair("War", "war"),
        Pair("War & Politics", "war-politics"),
        Pair("Western", "western")
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
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

    // utils

    private fun AnimeParse(response: Response): Pair<MutableList<SAnime>, Boolean> {
        val items = Json.decodeFromString<JsonObject>(response.body!!.string())["Items"]!!

        val animesList = mutableListOf<SAnime>()

        for (item in 0 until items.jsonArray.size) {
            val anime = SAnime.create()
            val jsonObj = JsonObject(items.jsonArray[item].jsonObject)

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

            animesList.add(anime)
        }

        return Pair(animesList, false)
    }
}
