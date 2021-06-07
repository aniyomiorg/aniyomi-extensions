package eu.kanade.tachiyomi.animeextension.en.hanime

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Link
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class Hanime : AnimeHttpSource() {

    override val name = "hanime.tv"

    override val baseUrl = ""

    override val lang = "en"

    override val supportsLatest = true

    private fun searchRequestBody(query: String, page: Int, filters: AnimeFilterList): RequestBody {
        var filterString = ""
        for (filter in filters.list) {
            filterString += if (filters.lastIndexOf(filter) == filters.lastIndex) {
                "\"${filter.name}\""
            } else {
                "\"${filter.name}\","
            }
        }
        return """
            {"search_text": "$query",
            "tags": [$filterString],
            "tags_mode":"AND",
            "brands": [],
            "blacklist": [],
            "order_by": "likes",
            "ordering": "desc",
            "page": ${page - 1}}
        """.trimIndent().toRequestBody("application/json".toMediaType())
    }
    private val popularRequestHeaders = Headers.headersOf("authority", "search.htv-services.com", "accept", "application/json, text/plain, */*", "content-type", "application/json;charset=UTF-8")

    override fun popularAnimeRequest(page: Int): Request = POST("https://search.htv-services.com/", popularRequestHeaders, searchRequestBody("", page, AnimeFilterList()))

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parseSearchJson(responseString)
    }
    fun parseSearchJson(jsonLine: String?): AnimesPage {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val jObject: JsonObject = jElement.asJsonObject
        val nbPages = jObject.get("nbPages").asInt
        val page = jObject.get("page").asInt
        val hasNextPage = page < nbPages - 1
        val arrayString = jObject.get("hits").asString
        val array = JsonParser.parseString(arrayString)
        val jObjectb: JsonArray = array.asJsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in jObjectb) {
            val anime = SAnime.create()
            anime.title = item.asJsonObject.get("name").asString
            anime.thumbnail_url = item.asJsonObject.get("cover_url").asString
            anime.setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + item.asJsonObject.get("slug").asString)
            anime.author = item.asJsonObject.get("brand").asString
            anime.description = item.asJsonObject.get("description").asString.replace("<p>", "").replace("</p>", "")
            anime.status = SAnime.COMPLETED
            val tags = item.asJsonObject.get("tags").asJsonArray
            anime.genre = tags.joinToString(", ") { it.asString }
            anime.initialized = true
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = POST("https://search.htv-services.com/", popularRequestHeaders, searchRequestBody(query, page, filters))

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parseSearchJson(responseString)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        return POST("https://search.htv-services.com/", popularRequestHeaders, searchRequestBody(anime.title, 1, AnimeFilterList()))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val arrayString = jObject.get("hits").asString
        val array = JsonParser.parseString(arrayString)
        val jObjectb: JsonArray = array.asJsonArray
        val item = jObjectb[0]
        val anime = SAnime.create()
        anime.title = item.asJsonObject.get("name").asString
        anime.thumbnail_url = item.asJsonObject.get("cover_url").asString
        anime.setUrlWithoutDomain("https://hanime.tv/videos/hentai/" + item.asJsonObject.get("slug").asString)
        anime.author = item.asJsonObject.get("brand").asString
        anime.description = item.asJsonObject.get("description").asString.replace("<p>", "").replace("</p>", "")
        anime.status = SAnime.COMPLETED
        val tags = item.asJsonObject.get("tags").asJsonArray
        anime.genre = tags.joinToString(", ") { it.asString }
        anime.initialized = true
        return anime
    }

    override fun episodeLinkParse(response: Response): List<Link> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val server = jObject.get("videos_manifest").asJsonObject.get("servers").asJsonArray[0].asJsonObject
        val streams = server.get("streams").asJsonArray
        val linkList = mutableListOf<Link>()
        for (stream in streams) {
            if (stream.asJsonObject.get("kind").asString != "premium_alert") {
                linkList.add(Link(stream.asJsonObject.get("url").asString, stream.asJsonObject.get("height").asString + "p"))
            }
        }
        return linkList
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("https://hw.hanime.tv/api/v8/video?id=$slug")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val episode = SEpisode.create()
        episode.date_upload = jObject.asJsonObject.get("hentai_video").asJsonObject.get("released_at_unix").asLong * 1000
        episode.name = jObject.asJsonObject.get("hentai_video").asJsonObject.get("name").asString
        episode.url = response.request.url.toString()
        episode.episode_number = 1F
        return listOf(episode)
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
