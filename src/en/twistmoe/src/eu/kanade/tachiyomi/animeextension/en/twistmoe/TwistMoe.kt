package eu.kanade.tachiyomi.animeextension.en.twistmoe

import android.util.Log
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
import java.lang.Exception
import java.util.Date

class TwistMoe : AnimeHttpSource() {

    override val name = "twist.moe"

    override val baseUrl = "https://twist.moe"

    override val lang = "en"

    override val supportsLatest = false

    private val popularRequestHeaders =
        Headers.headersOf("x-access-token", "0df14814b9e590a1f26d3071a4ed7974", "referer", baseUrl)

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
            anime.thumbnail_url = "https://homepages.cae.wisc.edu/~ece533/images/cat.png"
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
        return anime
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return super.videoListRequest(episode)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val server = jObject.get("videos_manifest").asJsonObject.get("servers").asJsonArray[0].asJsonObject
        val streams = server.get("streams").asJsonArray
        val aes = AESDecrypt()
        val ivAndKey = aes.getIvAndKey("U2FsdGVkX19njUQXx448lKxE4wUQA8tH45sgjCYckbrdS15QHY3fW5ChD6UpcoackxmWn8/5Tk88yAAwSukKwKpfvI6rQ1ERxFcAspfBCj8U/IQYoE3gZy+Esgumt/Fz")
        val toDecode = aes.getToDecode("U2FsdGVkX19njUQXx448lKxE4wUQA8tH45sgjCYckbrdS15QHY3fW5ChD6UpcoackxmWn8/5Tk88yAAwSukKwKpfvI6rQ1ERxFcAspfBCj8U/IQYoE3gZy+Esgumt/Fz")
        Log.i("lol", aes.aesDecrypt(toDecode, ivAndKey.sliceArray(0..31), ivAndKey.sliceArray(32..47)))
        val linkList = mutableListOf<Video>()
        for (stream in streams) {
            if (stream.asJsonObject.get("kind").asString != "premium_alert") {
                linkList.add(Video(stream.asJsonObject.get("url").asString, stream.asJsonObject.get("height").asString + "p", stream.asJsonObject.get("url").asString, null))
            }
        }
        return linkList
    }

    override fun episodeListRequest(anime: SAnime): Request {
        // aes.unpad(aes.aesDecrypt(toDecode, ivAndKey.sliceArray(0..31), ivAndKey.sliceArray(32..47)))
        val slug = anime.url.substringAfter("/a/")
        return GET("https://api.twist.moe/api/anime/$slug/sources", popularRequestHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body!!.string()
        Log.i("lol_response", responseString)
        val array = JsonParser.parseString(responseString).asJsonArray
        val episodeList = mutableListOf<SEpisode>()
        for (entry in array) {
            try {
                Log.i("lol", entry.toString())
                val episode = SEpisode.create()
                episode.date_upload = Date.parse(entry.asJsonObject.get("updated_at").asString)
                episode.name = "Episode " + entry.asJsonObject.get("number").asNumber.toString()
                episode.episode_number = entry.asJsonObject.get("number").asFloat
                episode.url = response.request.url.toString() + "#${episode.episode_number}"
                episodeList.add(episode)
            } catch (e: Exception) {
                Log.i("lol_e", e.message!!)
            }
        }
        Log.i("lol", episodeList.lastIndex.toString())
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
