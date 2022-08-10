package eu.kanade.tachiyomi.animeextension.en.kickassanime

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
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
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern

@ExperimentalSerializationApi
class KickAssAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "KickAssAnime"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://www2.kickassanime.ro")!! }

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/get_anime_list/all/$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val data = responseObject["data"]!!.jsonArray
        val animes = data.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.jsonObject["slug"]!!.jsonPrimitive.content.substringBefore("/episode"))
                thumbnail_url = "$baseUrl/uploads/" + item.jsonObject["poster"]!!.jsonPrimitive.content
                title = item.jsonObject["name"]!!.jsonPrimitive.content
            }
        }
        return AnimesPage(animes, true)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = getAppdata(response.asJsoup())
        val anime = data["anime"]!!.jsonObject
        val episodeList = anime["episodes"]!!.jsonArray
        return episodeList.map { item ->
            SEpisode.create().apply {
                url = item.jsonObject["slug"]!!.jsonPrimitive.content
                episode_number = item.jsonObject["num"]!!.jsonPrimitive.float
                name = item.jsonObject["epnum"]!!.jsonPrimitive.content
            }
        }
    }

    override fun latestUpdatesParse(response: Response) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val data = getAppdata(response.asJsoup())
        val episode = data["episode"]!!.jsonObject
        val link1 = episode["link1"]!!.jsonPrimitive.content
        val resp = client.newCall(GET(link1)).execute()
        val sources = getVideoSource(resp.asJsoup())
        val videoList = mutableListOf<Video>()

        sources.forEach { source ->
            when (source.jsonObject["name"]!!.jsonPrimitive.content) {
                "BETAPLAYER" -> {
                    videoList.addAll(
                        extractBetaVideo(
                            source.jsonObject["src"]!!.jsonPrimitive.content,
                            source.jsonObject["name"]!!.jsonPrimitive.content
                        )
                    )
                }
                "BETASERVER3" -> {}
                else -> {
                    videoList.addAll(
                        extractVideo(
                            source.jsonObject["src"]!!.jsonPrimitive.content,
                            source.jsonObject["name"]!!.jsonPrimitive.content
                        )
                    )
                }
            }
        }
        return videoList
    }

    private fun extractVideo(serverLink: String, server: String): List<Video> {
        val playlistInterceptor = MasterPlaylistInterceptor()
        val kickAssClient = client.newBuilder().addInterceptor(playlistInterceptor).build()
        kickAssClient.newCall(GET(serverLink)).execute()
        val data = playlistInterceptor.playlist
        val playlist = mutableListOf<Video>()
        val subsList = mutableListOf<Track>()

        if (server == "MAVERICKKI") {
            val subLink = serverLink.replace("embed", "api/source")
            val subResponse = Jsoup.connect(subLink).ignoreContentType(true).execute().body()
            val json = Json.decodeFromString<JsonObject>(subResponse)
            json["subtitles"]!!.jsonArray.forEach {
                val subLang = it.jsonObject["name"]!!.jsonPrimitive.content
                val uri = Uri.parse(serverLink)
                val subUrl = "${uri.scheme}://${uri.host}" + it.jsonObject["src"]!!.jsonPrimitive.content
                try {
                    subsList.add(Track(subUrl, subLang))
                } catch (e: Error) {}
            }
        }

        data.forEach { playlistPair ->
            val (videoLink, headers) = playlistPair
            val masterPlaylist = client.newCall(GET(videoLink, headers)).execute().body!!.string()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
                .split("#EXT-X-STREAM-INF:").map {
                    val quality = it.substringAfter("RESOLUTION=").split(",")[0].split("\n")[0].substringAfter("x") + "p $server" +
                        if (subsList.size > 0) { " (Toggleable Sub Available)" } else { "" }
                    var videoUrl = it.substringAfter("\n").substringBefore("\n")
                    if (videoUrl.startsWith("https").not()) {
                        val pos = videoLink.lastIndexOf('/') + 1
                        videoUrl = videoLink.substring(0, pos) + videoUrl
                    }
                    playlist.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subsList, headers = headers))
                }
        }
        return playlist
    }

    private fun extractBetaVideo(serverLink: String, server: String): List<Video> {
        val headers = Headers.headersOf("referer", "https://kaast1.com/")
        val document = client.newCall(GET(serverLink, headers)).execute().asJsoup()
        val scripts = document.getElementsByTag("script")
        var playlistArray = JsonArray(arrayListOf())
        for (element in scripts) {
            if (element.data().contains("window.files")) {
                val pattern = Pattern.compile(".*JSON\\.parse\\('(.*)'\\)")
                val matcher = pattern.matcher(element.data())
                if (matcher.find()) {
                    playlistArray = json.decodeFromString(matcher.group(1)!!.toString())
                }
                break
            }
        }
        val playlist = mutableListOf<Video>()
        playlistArray.forEach {
            val quality = it.jsonObject["label"]!!.jsonPrimitive.content + " $server"
            val videoUrl = it.jsonObject["file"]!!.jsonPrimitive.content
            playlist.add(
                Video(videoUrl, quality, videoUrl, headers = headers)
            )
        }
        return playlist
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")

        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?q=${encode(query)}")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = getAppdata(response.asJsoup())
        val animeList = data["animes"]!!.jsonArray
        val animes = animeList.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.jsonObject["slug"]!!.jsonPrimitive.content)
                thumbnail_url = "$baseUrl/uploads/" + item.jsonObject["poster"]!!.jsonPrimitive.content
                title = item.jsonObject["name"]!!.jsonPrimitive.content
            }
        }
        return AnimesPage(animes, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        val appData = getAppdata(response.asJsoup())
        if (appData.isEmpty().not()) {
            val ani = appData["anime"]!!.jsonObject
            anime.title = ani["name"]!!.jsonPrimitive.content
            anime.genre = ani["genres"]!!.jsonArray.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }
            anime.description = JSONUtil.unescape(ani["description"]!!.jsonPrimitive.content)
            anime.status = parseStatus(ani["status"]!!.jsonPrimitive.content)

            val altName = "Other name(s): "
            ani["alternate"]!!.jsonArray.let { jsonArray ->
                if (jsonArray.isEmpty().not()) {
                    anime.description = when {
                        anime.description.isNullOrBlank() -> altName + jsonArray.joinToString { it.jsonPrimitive.content }
                        else -> anime.description + "\n\n$altName" + jsonArray.joinToString { it.jsonPrimitive.content }
                    }
                }
            }
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun getAppdata(document: Document): JsonObject {
        val scripts = document.getElementsByTag("script")

        for (element in scripts) {
            if (element.data().contains("appData")) {
                val pattern = Pattern.compile(".*appData = (.*) \\|\\|")
                val matcher = pattern.matcher(element.data())
                if (matcher.find()) {
                    return json.decodeFromString(matcher.group(1)!!.toString())
                }
                break
            }
        }
        return json.decodeFromString("")
    }

    private fun getVideoSource(document: Document): JsonArray {
        val scripts = document.getElementsByTag("script")
        for (element in scripts) {
            if (element.data().contains("sources")) {
                val pattern = Pattern.compile(".*var sources = (.*);")
                val matcher = pattern.matcher(element.data())
                if (matcher.find()) {
                    return json.decodeFromString(matcher.group(1)!!.toString())
                }
                break
            }
        }

        return json.decodeFromString("")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("kickassanime.ro")
            entryValues = arrayOf("https://www2.kickassanime.ro")
            setDefaultValue("https://www2.kickassanime.ro")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
    }

    private fun encode(input: String): String = java.net.URLEncoder.encode(input, "utf-8")
}
