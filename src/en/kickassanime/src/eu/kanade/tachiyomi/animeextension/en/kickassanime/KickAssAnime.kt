package eu.kanade.tachiyomi.animeextension.en.kickassanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
                setUrlWithoutDomain(item.jsonObject["slug"].toString().trim('"').substringBefore("/episode"))
                thumbnail_url = "$baseUrl/uploads/" + item.jsonObject["poster"].toString().trim('"')
                title = item.jsonObject["name"].toString().trim('"')
            }
        }
        return AnimesPage(animes, true)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = getJson(response.asJsoup(), "appData")
        val episodeList = data["episodes"]!!.jsonArray
        return episodeList.map { item ->
            SEpisode.create().apply {
                url = item.jsonObject["slug"].toString().trim('"')
                episode_number = item.jsonObject["num"].toString().trim('"').toFloat()
                name = item.jsonObject["epnum"].toString().trim('"')
            }
        }
    }

    override fun latestUpdatesParse(response: Response) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val data = getJson(response.asJsoup(), "appData")
        val link1 = data["episode"]!!.jsonObject["link1"].toString().trim('"')
        val sources = getJson(client.newCall(GET(link1)).execute().asJsoup(), "sources").jsonArray
        val videoList = mutableListOf<Video>()

        sources[0].jsonObject["src"]?.let { videoList.addAll(extractVideo(it.toString().trim('"'), "PINK-BIRD")) }

        sources[1].jsonObject["src"]?.let { videoList.addAll(extractVideo(it.toString().trim('"'), "SAPPHIRE-DUCK")) }
        return videoList
    }

    private fun extractVideo(serverlink: String, server: String): List<Video> {
        val data = getVideoSource(client.newCall(GET(serverlink)).execute().asJsoup())
        var videoLink = String()
        if (server == "PINK-BIRD") {
            data.forEach { document ->
                if (!document.select("source").isEmpty()) {
                    videoLink = document.select("source").attr("src").toString()
                }
            }
        } else {
            data.forEach { document ->
                val pattern = Pattern.compile(".*file: \"(.*)\"")
                val matcher = pattern.matcher(document.data())
                if (matcher.find()) {
                    videoLink = matcher.group(1)!!.toString()
                }
            }
        }

        val headers = Headers.headersOf("referer", "https://kaast1.com/")
        val masterPlaylist = client.newCall(GET(videoLink, headers)).execute().body!!.string()
        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p $server"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, headers = headers)
            }
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
        val data = getJson(response.asJsoup(), "appData")
        val animeList = data["animes"]!!.jsonArray
        val animes = animeList.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.jsonObject["slug"].toString().trim('"'))
                thumbnail_url = "$baseUrl/uploads/" + item.jsonObject["poster"].toString().trim('"')
                title = item.jsonObject["name"].toString().trim('"')
            }
        }
        return AnimesPage(animes, false)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        val appData = getJson(response.asJsoup(), "appData")
        if (appData.isEmpty().not()) {
            val ani = appData["anime"]!!.jsonObject
            anime.title = ani["name"].toString().trim('"')
            anime.genre = ani["genres"]!!.jsonArray.joinToString { it.jsonObject["name"].toString().trim('"') }
            anime.description = ani["description"].toString().trim('"')
            anime.status = parseStatus(ani["status"].toString().trim('"'))

            val altName = "Other name(s): "
            ani["alternate"]!!.jsonArray.let { jsonArray ->
                if (jsonArray.isEmpty().not()) {
                    anime.description = when {
                        anime.description.isNullOrBlank() -> altName + jsonArray.joinToString { it.toString().trim('"') }
                        else -> anime.description + "\n\n$altName" + jsonArray.joinToString { it.toString().trim('"') }
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

    private fun getJson(document: Document, variable: String): JsonObject {
        val scripts = document.getElementsByTag("script")
        if (variable == "appData") {
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
        } else if (variable == "sources") {
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
        }
        return json.decodeFromString("{}")
    }

    private fun getVideoSource(document: Document): List<Document> {
        val scripts = document.getElementsByTag("script")
        val decodedScripts = mutableListOf<Document>()
        for (element in scripts) {
            if (element.data().contains("document.write")) {
                val pattern = Pattern.compile(".*Base64\\.decode\\(\"(.*)\"\\)")
                val matcher = pattern.matcher(element.data())
                if (matcher.find()) {
                    val decoded = Base64.decode(matcher.group(1)!!.toString(), Base64.DEFAULT).toString()
                    decodedScripts.add(Jsoup.parse(decoded))
                }
            }
        }
        return decodedScripts
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
