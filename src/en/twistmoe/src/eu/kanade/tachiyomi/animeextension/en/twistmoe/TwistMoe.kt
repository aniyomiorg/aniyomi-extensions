package eu.kanade.tachiyomi.animeextension.en.twistmoe

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Date

class TwistMoe : AnimeHttpSource() {

    override val name = "twist.moe"

    override val baseUrl = "https://twist.moe"

    override val lang = "en"

    override val supportsLatest = false

    private val popularRequestHeaders =
        Headers.headersOf("x-access-token", "0df14814b9e590a1f26d3071a4ed7974")

    override fun popularAnimeRequest(page: Int): Request =
        GET("https://api.twist.moe/api/anime", popularRequestHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parseSearchJson(responseString)
    }

    private fun parseSearchJson(jsonLine: String?): AnimesPage {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val array: JsonArray = jElement.asJsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.asJsonObject.get("title").asString
            anime.setUrlWithoutDomain("$baseUrl/a/" + item.asJsonObject.get("slug").asJsonObject.get("slug").asString)
            anime.status = when (item.asJsonObject.get("ongoing").asInt) {
                0 -> SAnime.COMPLETED
                1 -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            animeList.add(anime)
        }
        return AnimesPage(animeList, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("https://api.twist.moe/api/anime", popularRequestHeaders)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parseSearchJson(responseString)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfter("/a/")
        return GET("https://api.twist.moe/api/anime/$slug", popularRequestHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val anime = SAnime.create()
        anime.title = jObject.get("title").asString
        anime.setUrlWithoutDomain("$baseUrl/a/" + jObject.get("slug").asJsonObject.get("slug").asString)
        anime.description = jObject.get("description").asString
        anime.status = when (jObject.get("ongoing").asInt) {
            0 -> SAnime.COMPLETED
            1 -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        anime.initialized = true
        return anime
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val server = jObject.get("videos_manifest").asJsonObject.get("servers").asJsonArray[0].asJsonObject
        val streams = server.get("streams").asJsonArray
        val linkList = mutableListOf<Video>()
        for (stream in streams) {
            if (stream.asJsonObject.get("kind").asString != "premium_alert") {
                linkList.add(Video(stream.asJsonObject.get("url").asString, stream.asJsonObject.get("height").asString + "p", stream.asJsonObject.get("url").asString, null))
            }
        }
        return linkList
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfter("/a/")
        return GET("https://api.twist.moe/api/anime/$slug/sources", popularRequestHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val array: JsonArray = jElement.asJsonArray
        val episodeList = mutableListOf<SEpisode>()
        for (entry in array) {
            val episode = SEpisode.create()
            episode.date_upload = Date.parse(entry.asJsonObject.get("updated_at").asString)
            episode.name = "Episode " + entry.asJsonObject.get("number").asString
            episode.url = entry.asJsonObject.get("source").asString
            episode.episode_number = entry.asJsonObject.get("number").asFloat
            episodeList.add(episode)
        }
        return episodeList
    }

    private fun latestSearchRequestBody(page: Int): RequestBody {
        return """
            {"search_text": "",
            "tags": [],
            "tags_mode":"AND",
            "brands": [],
            "blacklist": [],
            "order_by": "published_at_unix",
            "ordering": "desc",
            "page": ${page - 1}}
        """.trimIndent().toRequestBody("application/json".toMediaType())
    }

    override fun latestUpdatesRequest(page: Int): Request = POST("https://search.htv-services.com/", popularRequestHeaders, latestSearchRequestBody(page))

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parseSearchJson(responseString)
    }
}
