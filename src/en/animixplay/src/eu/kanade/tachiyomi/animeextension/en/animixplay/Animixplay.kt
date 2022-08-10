package eu.kanade.tachiyomi.animeextension.en.animixplay

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animixplay.extractors.GogoCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

@ExperimentalSerializationApi
class Animixplay : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animixplay"

    override val baseUrl = "https://animixplay.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    var nextPage = "99999999"
    var hasNextPage = true

    var latestNextDate = "3020-05-06 00:00:00"
    var latestHasNextPage = true

    override fun popularAnimeSelector(): String = throw Exception("not used")

    override fun popularAnimeRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("genre", "any")
            .add("minstr", nextPage)
            .add("orderby", "popular")
            .build()
        return POST("https://animixplay.to/api/search", headers, body = formBody)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val responseJson = json.decodeFromString<JsonObject>(document.select("body").text())
        nextPage = responseJson["last"]!!.jsonPrimitive.content
        hasNextPage = responseJson["more"]!!.jsonPrimitive.boolean
        val animeList = responseJson["result"]!!.jsonArray
        val animes = animeList.map { element ->
            popularAnimeFromElement(element.jsonObject)
        }

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeFromElement(element: Element) = throw Exception("not used")

    private fun popularAnimeFromElement(animeJson: JsonObject): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(animeJson["url"]!!.jsonPrimitive.content.substringBefore("/ep"))
        anime.thumbnail_url = animeJson["picture"]!!.jsonPrimitive.content
        anime.title = animeJson["title"]!!.jsonPrimitive.content
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = throw Exception("not used")

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        return if (response.request.url.toString().contains(".json")) {
            val document = response.asJsoup()
            val animeJson = json.decodeFromString<JsonObject>(document.select("body").text())
            val malId = animeJson["mal_id"]!!.jsonPrimitive.int
            episodesRequest(malId, document)
        } else {
            episodeFromResponse(response)
        }
    }

    private fun episodesRequest(malId: Int, document: Document): List<SEpisode> {
        // POST data
        val body = FormBody.Builder()
            .add("recomended", malId.toString())
            .build()
        val animeServersJson = json.decodeFromString<JsonObject>(
            client.newCall(
                POST(
                    "https://animixplay.to/api/search",
                    body = body,
                    headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().body!!.string()
        )
        val animeSubDubUrls = animeServersJson["data"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray
        val newList = mutableListOf<JsonElement>()
        var preferred = 0
        for (jsonObj in animeSubDubUrls) {
            if (jsonObj.toString().contains("dub")) {
                newList.add(preferred, jsonObj)
                preferred++
            } else {
                newList.add(jsonObj)
            }
        }
        newList.reverse()
        val urlEndpoint = newList[0].jsonObject["url"]!!.jsonPrimitive.content
        val episodesResponse = client.newCall(
            GET(
                baseUrl + urlEndpoint,
            )
        ).execute()
        return episodeFromResponse(episodesResponse)
    }
    private fun episodeFromResponse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeListJson = json.decodeFromString<JsonObject>(document.select("div#epslistplace").text())
        val url = response.request.url.toString()
        val episodeAvailable = episodeListJson["eptotal"]!!.jsonPrimitive.int
        val episodeList = mutableListOf<SEpisode>()

        for (i in 0 until episodeAvailable) {
            episodeList.add(episodeFromJsonElement(url, i))
        }
        return episodeList.reversed()
    }
    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun episodeFromJsonElement(url: String, number: Int): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain("$url/ep$number")
        episode.episode_number = number.toFloat() + 1F
        episode.name = "Episode ${number + 1}"
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeListJson = json.decodeFromString<JsonObject>(document.select("div#epslistplace").text())
        val epNo = response.request.url.toString().substringAfter("/ep")
        val serverUrl = "https:" + episodeListJson[epNo]!!.jsonPrimitive.content
        val serverPref = preferences.getString("preferred_server", "vrv")
        return if (serverPref!!.contains("gogo")) {
            GogoCdnExtractor(client, json).videosFromUrl(serverUrl)
        } else {
            vrvExtractor(serverUrl)
        }
    }

    private fun vrvExtractor(url: String): List<Video> {
        val id = url.split("?id=")[1].split("&")[0]
        val reqUrl = baseUrl + "/api/live" + encodeBase64(id + "LTXs3GrU8we9O" + encodeBase64(id))
        val redirectClient = client.newBuilder().followRedirects(true).build()
        val redirectUrlEncodedString = redirectClient.newCall(
            GET(
                reqUrl,
                headers
            )
        ).execute().request.url.fragment!!.substringBefore("#")
        val masterUrl = decodeBase64(redirectUrlEncodedString)
        return if (masterUrl.contains("gogo")) {
            parseCdnMasterPlaylist(masterUrl)
        } else {
            val masterPlaylist = client.newCall(GET(masterUrl, headers)).execute().body!!.string()
            val videosList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                videosList.add(Video(videoUrl, quality, videoUrl, headers = headers))
            }
            videosList
        }
    }
    private fun parseCdnMasterPlaylist(url: String): List<Video> {
        val videosList = mutableListOf<Video>()
        val masterUrlPrefix = url.substringBefore("/ep")
        val masterPlaylist = client.newCall(GET(url, headers)).execute().body!!.string()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
            val videoUrl = "$masterUrlPrefix/${it.substringAfter("\n").substringBefore("\r").substringBefore("\n")}"
            videosList.add(Video(videoUrl, quality, videoUrl, headers = headers))
        }
        return videosList
    }
    private fun encodeBase64(string: String): String {
        return Base64.encodeToString(string.toByteArray(), Base64.NO_PADDING)
    }
    private fun decodeBase64(string: String): String {
        return Base64.decode(string, Base64.DEFAULT).decodeToString()
    }
    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(responseJson["result"]!!.jsonPrimitive.content)
        val animeList = document.select("li")
        val animes = animeList.map {
            searchAnimeFromElement(it)
        }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div a").attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.select("p.name a").attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = throw Exception("not used")

    override fun searchAnimeSelector(): String = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val formData = FormBody.Builder()
            .addEncoded("q2", query)
            .addEncoded("root", "animixplay.to")
            .addEncoded("origin", "1")
            .build()
        return when {
            query.isNotBlank() -> POST("https://v1.ic5qwh28vwloyjo28qtg.workers.dev/", headers, formData)
            else -> GET("$baseUrl/?tab=popular")
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = if (!response.request.url.toString().contains(".json")) {
            getDocumentFromRequestUrl(response)
        } else {
            response.asJsoup()
        }
        return animeDetailsParse(document)
    }

    private fun getDocumentFromRequestUrl(response: Response): Document {
        val scriptData = response.asJsoup().select("script:containsData(var malid )").toString()
        val malId = scriptData.substringAfter("var malid = '").substringBefore("';")
        val url = "https://animixplay.to/assets/mal/$malId.json"
        return client.newCall(
            GET(
                url,
                headers
            )
        ).execute().asJsoup()
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val animeJson = json.decodeFromString<JsonObject>(document.select("body").text())
        anime.title = animeJson["title"]!!.jsonPrimitive.content
        anime.genre =
            animeJson["genres"]!!.jsonArray.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }
        anime.description = animeJson["synopsis"]!!.jsonPrimitive.content
        anime.status = parseStatus(animeJson["status"]!!.jsonPrimitive.content)
        val studiosArray = animeJson["studios"]!!.jsonArray
        if (studiosArray.isNotEmpty()) {
            anime.author =
                studiosArray[0].jsonObject["name"]!!.jsonPrimitive.content
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

    override fun latestUpdatesNextPageSelector(): String = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("genre", "any")
            .add("minstr", latestNextDate)
            .add("orderby", "latest")
            .build()
        return POST("$baseUrl/api/search", headers, body = formBody)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val responseJson = json.decodeFromString<JsonObject>(document.select("body").text())
        latestNextDate = responseJson["last"]!!.jsonPrimitive.content
        latestHasNextPage = responseJson["more"]!!.jsonPrimitive.boolean
        val animeList = responseJson["result"]!!.jsonArray
        val animes = animeList.map { element ->
            popularAnimeFromElement(element.jsonObject)
        }

        return AnimesPage(animes, latestHasNextPage)
    }

    override fun latestUpdatesSelector(): String = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Vrv/Cdn", "Gogo")
            entryValues = arrayOf("vrv", "gogo")
            setDefaultValue("vrv")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
    }
}
