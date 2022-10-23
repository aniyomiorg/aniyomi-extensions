package eu.kanade.tachiyomi.animeextension.en.kickassanime

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors.GogoCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
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
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
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

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }

    // Add non working server names here
    private val deadServers = listOf(
        "BETA-SERVER", "BETASERVER1", "BETASERVER3", "DEVSTREAM",
        "THETA-ORIGINAL-V4", "DAILYMOTION", "KICKASSANIME1"
    )

    private val workingServers = arrayOf(
        "StreamSB", "PINK-BIRD", "Doodstream", "MAVERICKKI",
        "BETAPLAYER", "Vidstreaming", "SAPPHIRE-DUCK", "KICKASSANIMEV2", "ORIGINAL-QUALITY-V2"
    )

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
                date_upload = parseDate(item.jsonObject["createddate"]!!.jsonPrimitive.content)
            }
        }
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DateFormatter.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    override fun latestUpdatesParse(response: Response) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val data = getAppdata(response.asJsoup())
        val episode = data["episode"]!!.jsonObject
        var link = episode["link1"]!!.jsonPrimitive.content
        // check if link1 is not blank (link2-4 doesn't work), if so check external servers for gogo links
        if (link.isBlank()) {
            for (li in data["ext_servers"]!!.jsonArray) {
                if (li.jsonObject["name"]!!.jsonPrimitive.content == "Vidcdn") {
                    link = li.jsonObject["link"]!!.jsonPrimitive.content
                    break
                }
            }
        }
        if (link.isBlank()) return listOf()
        val videoList = mutableListOf<Video>()

        when {
            link.contains("gogoplay4.com") -> {
                videoList.addAll(
                    extractGogoVideo(link)
                )
            }
            link.contains("betaplayer.life") -> {
                var url = decode(link).substringAfter("data=").substringBefore("&vref")
                if (url.startsWith("https").not()) {
                    url = "https:$url"
                }
                videoList.addAll(
                    extractBetaVideo(url, "BETAPLAYER")
                )
            }
            else -> {
                val resp = client.newCall(GET(link)).execute()
                val sources = getVideoSource(resp.asJsoup())

                sources.forEach { source ->
                    when (source.jsonObject["name"]!!.jsonPrimitive.content) {
                        in deadServers -> {}
                        "BETAPLAYER" -> {
                            videoList.addAll(
                                extractBetaVideo(
                                    source.jsonObject["src"]!!.jsonPrimitive.content,
                                    source.jsonObject["name"]!!.jsonPrimitive.content
                                )
                            )
                        }
                        "KICKASSANIMEV2", "ORIGINAL-QUALITY-V2" -> {
                            videoList.addAll(
                                extractKickasssVideo(
                                    source.jsonObject["src"]!!.jsonPrimitive.content,
                                    source.jsonObject["name"]!!.jsonPrimitive.content
                                )
                            )
                        }
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
            }
        }
        return videoList
    }

    private fun extractVideo(serverLink: String, server: String): List<Video> {
        val playlist = mutableListOf<Video>()
        val subsList = mutableListOf<Track>()
        val data: MutableList<Pair<String, Headers>>

        if (server == "MAVERICKKI") {
            val apiLink = serverLink.replace("embed", "api/source")
            // for some reason the request to the api is only working reliably this way
            val apiResponse = client.newCall(GET(apiLink, headers)).execute().asJsoup().text()
            val json = Json.decodeFromString<JsonObject>(apiResponse)
            val uri = Uri.parse(serverLink)

            json["subtitles"]!!.jsonArray.forEach {
                val subLang = it.jsonObject["name"]!!.jsonPrimitive.content
                val subUrl = "${uri.scheme}://${uri.host}" + it.jsonObject["src"]!!.jsonPrimitive.content
                try {
                    subsList.add(Track(subUrl, subLang))
                } catch (e: Error) {}
            }
            data = mutableListOf(Pair("${uri.scheme}://${uri.host}" + json["hls"]!!.jsonPrimitive.content, headers))
        } else {
            val playlistInterceptor = MasterPlaylistInterceptor()
            val kickAssClient = client.newBuilder().addInterceptor(playlistInterceptor).build()
            kickAssClient.newCall(GET(serverLink, headers)).execute()
            data = playlistInterceptor.playlist
        }

        data.forEach { (videoLink, headers) ->
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
                    try {
                        playlist.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subsList, headers = headers))
                    } catch (e: Error) {
                        playlist.add(Video(videoUrl, quality, videoUrl, headers = headers))
                    }
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

    private fun extractKickasssVideo(serverLink: String, server: String): List<Video> {
        val url = serverLink.replace("embed.php", "pref.php")
        val document = client.newCall(GET(url)).execute().asJsoup()
        val scripts = document.getElementsByTag("script")
        var playlistArray = JsonArray(arrayListOf())
        for (element in scripts) {
            if (element.data().contains("document.write")) {
                val pattern = Pattern.compile(".*atob\\(\"(.*)\"\\)")
                val matcher = pattern.matcher(element.data())
                if (matcher.find()) {
                    val player = Base64.decode(matcher.group(1)!!.toString(), Base64.DEFAULT).toString(Charsets.UTF_8)
                    val playerPattern = Pattern.compile(".*setup\\(\\{sources:\\[(.*)\\]")
                    val playerMatcher = playerPattern.matcher(player)
                    if (playerMatcher.find()) {
                        val playlistString = "[" + playerMatcher.group(1)!!.toString() + "]"
                        playlistArray = json.decodeFromString(playlistString)
                    }
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

    private fun extractGogoVideo(link: String): List<Video> {
        var url = decode(link).substringAfter("data=").substringBefore("&vref")
        if (url.startsWith("https").not()) {
            url = "https:$url"
        }
        val videoList = mutableListOf<Video>()
        val document = client.newCall(GET(url)).execute().asJsoup()

        // Vidstreaming:
        videoList.addAll(GogoCdnExtractor(network.client, json).videosFromUrl(url))
        // Doodstream mirror:
        document.select("div#list-server-more > ul > li.linkserver:contains(Doodstream)")
            .firstOrNull()?.attr("data-video")
            ?.let { videoList.addAll(DoodExtractor(client).videosFromUrl(it)) }
        // StreamSB mirror:
        document.select("div#list-server-more > ul > li.linkserver:contains(StreamSB)")
            .firstOrNull()?.attr("data-video")
            ?.let { videoList.addAll(StreamSBExtractor(client).videosFromUrl(it, headers)) }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        val server = preferences.getString("preferred_server", "MAVERICKKI")

        if (quality != null || server != null) {
            val qualityList = mutableListOf<Video>()
            if (quality != null) {
                var preferred = 0
                for (video in this) {
                    if (video.quality.contains(quality)) {
                        qualityList.add(preferred, video)
                        preferred++
                    } else {
                        qualityList.add(video)
                    }
                }
            } else {
                qualityList.addAll(this)
            }
            val newList = mutableListOf<Video>()
            if (server != null) {
                var preferred = 0
                for (video in qualityList) {
                    if (video.quality.contains(server)) {
                        newList.add(preferred, video)
                        preferred++
                    } else {
                        newList.add(video)
                    }
                }
            } else {
                newList.addAll(qualityList)
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?q=${encode(query.trim())}")
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
            json.decodeFromString<JsonArray>(ani["alternate"].toString().replace("\"\"", "[]")).let { altArray ->
                if (altArray.isEmpty().not()) {
                    anime.description = when {
                        anime.description.isNullOrBlank() -> altName + altArray.joinToString { it.jsonPrimitive.content }
                        else -> anime.description + "\n\n$altName" + altArray.joinToString { it.jsonPrimitive.content }
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
        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = workingServers
            entryValues = workingServers
            setDefaultValue("MAVERICKKI")
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
        screen.addPreference(serverPref)
    }

    private fun encode(input: String): String = java.net.URLEncoder.encode(input, "utf-8")

    private fun decode(input: String): String = java.net.URLDecoder.decode(input, "utf-8")
}
