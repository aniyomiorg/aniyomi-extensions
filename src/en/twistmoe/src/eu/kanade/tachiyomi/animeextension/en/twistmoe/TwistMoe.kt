package eu.kanade.tachiyomi.animeextension.en.twistmoe

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.ArrayList

class TwistMoe : AnimeHttpSource() {

    override val name = "twist.moe"

    override val baseUrl = "https://twist.moe"

    override val lang = "en"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://twist.moe/")
    }

    private val requestHeaders =
        Headers.headersOf("x-access-token", "0df14814b9e590a1f26d3071a4ed7974", "referer", baseUrl)

    override fun popularAnimeRequest(page: Int): Request =
        GET("https://api.twist.moe/api/anime#$page", requestHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        val array = json.decodeFromString<JsonArray>(responseString)
        val list = mutableListOf<JsonElement>()
        array.toCollection(list)
        val page = response.request.url.fragment!!.toInt() - 1
        val start = page * 10
        val end = if (list.lastIndex > start + 9) start + 9 else list.lastIndex
        val range = start..end
        return AnimesPage(parseSearchJson(list.slice(range)), end != list.lastIndex)
    }

    private fun parseSearchJson(array: List<JsonElement>): List<SAnime> {
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$baseUrl/a/" + item.jsonObject["slug"]!!.jsonObject["slug"]!!.jsonPrimitive.content)
            anime.status = when (item.jsonObject["ongoing"]!!.jsonPrimitive.int) {
                0 -> SAnime.COMPLETED
                1 -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            animeList.add(anime)
        }
        return animeList
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("https://api.twist.moe/api/anime#query=$query;page=$page", requestHeaders)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        val array = json.decodeFromString<JsonArray>(responseString)
        val list = mutableListOf<JsonElement>()
        array.toCollection(list)
        val query = response.request.url.fragment!!
            .substringAfter("query=")
            .substringBeforeLast(";page=")
            .toLowerCase(Locale.ROOT)
        val toRemove = mutableListOf<JsonElement>()
        for (entry in list) {
            val title = entry.jsonObject["title"]!!.jsonPrimitive.content.toLowerCase(Locale.ROOT)
            val altTitle = try {
                entry.jsonObject["alt_title"]!!.jsonPrimitive.content.toLowerCase(Locale.ROOT)
            } catch (e: Exception) { "" }
            if (!(title.contains(query) || altTitle.contains(query))) toRemove.add(entry)
        }
        list.removeAll(toRemove)
        val page = response.request.url.fragment!!.substringAfterLast(";page=").toInt() - 1
        val start = page * 10
        val end = if (list.lastIndex > start + 9) start + 9 else list.lastIndex
        val range = start..end
        return AnimesPage(parseSearchJson(list.slice(range)), end != list.lastIndex)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfter("/a/")
        return GET("https://api.twist.moe/api/anime/$slug", requestHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body!!.string()
        val jObject = json.decodeFromString<JsonObject>(responseString)
        val anime = SAnime.create()
        anime.title = jObject["title"]!!.jsonPrimitive.content
        anime.setUrlWithoutDomain("$baseUrl/a/" + jObject["slug"]!!.jsonObject["slug"]!!.jsonPrimitive.content)
        anime.description = jObject["description"]!!.jsonPrimitive.content
        anime.status = when (jObject["ongoing"]!!.jsonPrimitive.int) {
            0 -> SAnime.COMPLETED
            1 -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        getCover(jObject, anime)
        return anime
    }

    private fun getCover(jObject: JsonObject, anime: SAnime) {
        try {
            val malID = try {
                jObject["mal_id"]!!.jsonPrimitive.contentOrNull
            } catch (e: Exception) {
                ""
            }
            if (malID != null) {
                val headers = Headers.Builder().apply {
                    add("Content-Type", "application/json")
                    add("Accept", "application/json")
                }.build()
                val bodyString = "{\"query\":\"query(\$id: Int){Media(type:ANIME,idMal:\$id){coverImage{large}}}\",\"variables\":{\"id\":$malID}}"
                val body = bodyString.toRequestBody("application/json".toMediaType())
                val coverResponse = client.newCall(POST("https://graphql.anilist.co", headers, body)).execute()
                val imageUrl = json.decodeFromString<JsonObject>(coverResponse.body!!.string())["data"]!!
                    .jsonObject["Media"]!!
                    .jsonObject["coverImage"]!!
                    .jsonObject["large"]!!.jsonPrimitive.content
                if (imageUrl.isNotEmpty()) anime.thumbnail_url = imageUrl
            } else {
                val query = anime.title
                val headers = Headers.Builder().apply {
                    add("Content-Type", "application/json")
                    add("Accept", "application/json")
                }.build()
                val bodyString = "{\"query\":\"query(\$query: String){Media(type:ANIME,search:\$query){coverImage{large}}}\",\"variables\":{\"query\":\"$query\"}}"
                val body = bodyString.toRequestBody("application/json".toMediaType())
                val coverResponse = client.newCall(POST("https://graphql.anilist.co", headers, body)).execute()
                val imageUrl = json.decodeFromString<JsonObject>(coverResponse.body!!.string())["data"]!!
                    .jsonObject["Media"]!!
                    .jsonObject["coverImage"]!!
                    .jsonObject["large"]!!.jsonPrimitive.content
                if (imageUrl.isNotEmpty()) anime.thumbnail_url = imageUrl
            }
        } catch (e: Exception) {
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, requestHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body!!.string()
        val array = json.decodeFromString<JsonArray>(responseString)
        val list = mutableListOf<JsonElement>()
        array.toCollection(list)
        val episodeNumber = response.request.url.fragment!!.toFloat()
        val videoList = mutableListOf<Video>()
        val aes = AESDecrypt()
        for (entry in list) {
            if (entry.jsonObject["number"]!!.jsonPrimitive.float == episodeNumber) {
                val source = entry.jsonObject["source"]!!.jsonPrimitive.content
                val ivAndKey = aes.getIvAndKey(source)
                val toDecode = aes.getToDecode(source)
                val url = "https://air-cdn.twist.moe" +
                    aes.unpad(aes.aesDecrypt(toDecode, ivAndKey.sliceArray(0..31), ivAndKey.sliceArray(32..47)))
                videoList.add(Video(url, "1080p", url))
            }
        }
        return videoList
    }

    override fun episodeListRequest(anime: SAnime): Request {
        // aes.unpad(aes.aesDecrypt(toDecode, ivAndKey.sliceArray(0..31), ivAndKey.sliceArray(32..47)))
        val slug = anime.url.substringAfter("/a/")
        return GET("https://api.twist.moe/api/anime/$slug/sources", requestHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body!!.string()
        val array = json.decodeFromString<JsonArray>(responseString)
        val list = mutableListOf<JsonElement>()
        array.toCollection(list)
        val episodeList = mutableListOf<SEpisode>()
        for (entry in list) {
            try {
                val episode = SEpisode.create()
                episode.date_upload = parseDate(entry.jsonObject["updated_at"]!!.jsonPrimitive.content)
                episode.name = "Episode " + entry.jsonObject["number"]!!.jsonPrimitive.content
                episode.episode_number = entry.jsonObject["number"]!!.jsonPrimitive.float
                episode.url = response.request.url.toString() + "#${episode.episode_number}"
                episodeList.add(episode)
            } catch (e: Exception) {
            }
        }
        return episodeList.reversed()
    }

    @SuppressLint("SimpleDateFormat")
    private fun parseDate(date: String): Long {
        val knownPatterns: MutableList<SimpleDateFormat> = ArrayList()
        knownPatterns.add(SimpleDateFormat("yyyy-MM-dd hh:mm:ss"))

        for (pattern in knownPatterns) {
            try {
                // Take a try
                return pattern.parse(date)!!.time
            } catch (e: Throwable) {
                // Loop on
            }
        }
        return System.currentTimeMillis()
    }

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")
}
