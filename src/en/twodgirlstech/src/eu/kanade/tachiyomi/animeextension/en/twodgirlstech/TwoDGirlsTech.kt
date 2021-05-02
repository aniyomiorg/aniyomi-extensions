package eu.kanade.tachiyomi.animeextension.en.twodgirlstech

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.AnimesPage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SAnime
import eu.kanade.tachiyomi.source.model.SEpisode
import eu.kanade.tachiyomi.source.online.ParsedAnimeHttpSource
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TwoDGirlsTech : ParsedAnimeHttpSource() {

    override val name = "2dgirlstech"

    override val baseUrl = "https://2dgirls.tech"

    override val lang = "en"

    override val supportsLatest = false

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return Observable.just(runBlocking { get(page) })
    }

    suspend fun get(page: Int): AnimesPage {
        var hasNextPage = true
        val request = GET("$baseUrl/api/popular/$page")
        val arrayListDetails: ArrayList<SAnime> = ArrayList()
        return suspendCoroutine<AnimesPage> { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    throw e
                }
                override fun onResponse(call: Call, response: Response) {
                    val strResponse = response.body()!!.string()
                    // creating json object
                    val json = JSONObject(strResponse)
                    // creating json array
                    val jsonArrayInfo: JSONArray = json.getJSONArray("results")
                    val size: Int = jsonArrayInfo.length()
                    for (i in 0 until size) {
                        val anime = SAnime.create()
                        val jsonObjectDetail: JSONObject = jsonArrayInfo.getJSONObject(i)
                        anime.title = jsonObjectDetail.getString("title")
                        anime.thumbnail_url = jsonObjectDetail.getString("image")
                        runBlocking { setDetails(anime, jsonObjectDetail.getString("id")) }
                        anime.setUrlWithoutDomain("/api/detailshtml/" + jsonObjectDetail.getString("id"))
                        arrayListDetails.add(anime)
                    }
                    hasNextPage = (arrayListDetails.isNotEmpty())
                    continuation.resume(AnimesPage(arrayListDetails, hasNextPage))
                }
            })
        }
    }

    fun setDetails(anime: SAnime, id: String) {
        val request = GET("$baseUrl/api/details/$id")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                throw e
            }
            override fun onResponse(call: Call, response: Response) {
                val strResponse = response.body()!!.string()
                // creating json object
                val json = JSONObject(strResponse)
                // creating json array
                val jsonArrayInfo: JSONArray = json.getJSONArray("results")
                val size: Int = jsonArrayInfo.length()
                for (i in 0..size - 1) {
                    val jsonObjectDetail: JSONObject = jsonArrayInfo.getJSONObject(i)
                    anime.genre = jsonObjectDetail.getString("genres")
                    when (jsonObjectDetail.getString("status").replace("\\s".toRegex(), "")) {
                        "Ongoing" -> anime.status = SAnime.ONGOING
                        "Completed" -> anime.status = SAnime.COMPLETED
                    }
                    anime.description = jsonObjectDetail.getString("summary")
                }
            }
        })
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: FilterList): Observable<AnimesPage> {
        return Observable.just(runBlocking { getSearch(page, query) })
    }

    suspend fun getSearch(page: Int, query: String): AnimesPage {
        var hasNextPage = true
        val request = GET("$baseUrl/api/search/$query/$page")
        val arrayListDetails: ArrayList<SAnime> = ArrayList()
        return suspendCoroutine<AnimesPage> { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    throw e
                }
                override fun onResponse(call: Call, response: Response) {
                    val strResponse = response.body()!!.string()
                    // creating json object
                    val json = JSONObject(strResponse)
                    // creating json array
                    val jsonArrayInfo: JSONArray = json.getJSONArray("results")
                    val size: Int = jsonArrayInfo.length()
                    for (i in 0..size - 1) {
                        val anime = SAnime.create()
                        val jsonObjectDetail: JSONObject = jsonArrayInfo.getJSONObject(i)
                        anime.title = jsonObjectDetail.getString("title")
                        anime.thumbnail_url = jsonObjectDetail.getString("image")
                        runBlocking { setDetails(anime, jsonObjectDetail.getString("id")) }
                        anime.setUrlWithoutDomain("/api/detailshtml/" + jsonObjectDetail.getString("id"))
                        arrayListDetails.add(anime)
                    }
                    hasNextPage = (arrayListDetails.isNotEmpty())
                    continuation.resume(AnimesPage(arrayListDetails, hasNextPage))
                }
            })
        }
    }
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return Observable.just(anime)
    }

    override fun episodeListSelector() = "div[id^=episode-]"

    override fun episodeFromElement(element: Element): SEpisode {
        Log.i("episodefromelement", element.toString())
        val id = element.id().split(":").last()
        val episodeNumber = element.id().split("episode-").last().split(":").first()
        val episode = SEpisode.create()
        episode.episode_number = episodeNumber.toFloat()
        episode.name = "Episode $episodeNumber"
        episode.url = "/api/watchinghtml/$id/$episodeNumber"
        return episode
    }

    override fun episodeLinkSelector() = "body"

    override fun linkFromElement(element: Element): String {
        Log.i("linkfromelement", element.toString())
        var url = ""
        val json = JSONObject(element.text())
        val jsonArrayInfo: JSONArray = json.getJSONArray("links")
        val size: Int = jsonArrayInfo.length()
        for (i in 0..size - 1) {
            val jsonObjectDetail: JSONObject = jsonArrayInfo.getJSONObject(i)
            if (jsonObjectDetail.getString("src").startsWith("https://storage.googleapis"))
                url = jsonObjectDetail.getString("src")
            Log.i("bruhh", url)
        }
        return url
    }

    override fun pageListParse(document: Document): List<Page> {
        val titleWords: Sequence<String>
        val altTextWords: Sequence<String>
        val interactiveText = listOf(
            "To experience the", "interactive version of this comic,",
            "open it in WebView/browser."
        )
            .joinToString(separator = "%0A")
            .replace(" ", "%20")

        // transforming filename from info.0.json isn't guaranteed to work, stick to html
        // if an HD image is available it'll be the srcset attribute
        // if img tag is empty then it is an interactive comic viewable only in browser
        val image = document.select("div#comic img").let {
            when {
                it == null || it.isEmpty() -> baseAltTextUrl + interactiveText + baseAltTextPostUrl
                it.hasAttr("srcset") -> it.attr("abs:srcset").substringBefore(" ")
                else -> it.attr("abs:src")
            }
        }

        // create a text image for the alt text
        document.select("div#comic img").let {
            titleWords = it.attr("alt").splitToSequence(" ")
            altTextWords = it.attr("title").splitToSequence(" ")
        }

        val builder = StringBuilder()
        var count = 0

        for (i in titleWords) {
            if (count != 0 && count.rem(7) == 0) {
                builder.append("%0A")
            }
            builder.append(i).append("+")
            count++
        }
        builder.append("%0A%0A")

        var charCount = 0

        for (i in altTextWords) {
            if (charCount > 25) {
                builder.append("%0A")
                charCount = 0
            }
            builder.append(i).append("+")
            charCount += i.length + 1
        }

        return listOf(Page(0, "", image), Page(1, "", baseAltTextUrl + builder.toString() + baseAltTextPostUrl))
    }

    override fun imageUrlRequest(page: Page) = GET(page.url)

    override fun imageUrlParse(document: Document) = throw Exception("Not used")

    override fun popularAnimeSelector(): String = throw Exception("Not used")

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun searchAnimeNextPageSelector(): String? = throw Exception("Not used")

    override fun searchAnimeSelector(): String = throw Exception("Not used")

    override fun popularAnimeRequest(page: Int): Request = throw Exception("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: FilterList): Request = throw Exception("Not used")

    override fun popularAnimeNextPageSelector(): String? = throw Exception("Not used")

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    companion object {
        const val baseAltTextUrl = "https://fakeimg.pl/1500x2126/ffffff/000000/?text="
        const val baseAltTextPostUrl = "&font_size=42&font=museo"
    }
}
