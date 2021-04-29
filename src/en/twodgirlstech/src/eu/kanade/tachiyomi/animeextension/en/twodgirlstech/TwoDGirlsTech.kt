package eu.kanade.tachiyomi.animeextension.en.twodgirlstech

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
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TwoDGirlsTech : ParsedAnimeHttpSource() {

    override val name = "2dgirlstech"

    override val baseUrl = "https://2dgirls.tech/"

    override val lang = "en"

    override val supportsLatest = false

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return Observable.just(runBlocking { get(page) })
    }

    suspend fun get(page: Int): AnimesPage {
        var hasNextPage = true
        val request = GET(baseUrl + "api/popular/" + page)
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
                    for (i in 0 until(size - 1)) {
                        val anime = SAnime.create()
                        val jsonObjectDetail: JSONObject = jsonArrayInfo.getJSONObject(i)
                        anime.url = "https://2dgirls.tech/api/details/" + jsonObjectDetail.getString("id")
                        anime.title = jsonObjectDetail.getString("title")
                        anime.thumbnail_url = jsonObjectDetail.getString("image")
                        anime.setUrlWithoutDomain("api/details/" + jsonObjectDetail.getString("id"))
                        anime.artist = "Randall Munroe"
                        anime.author = "Randall Munroe"
                        anime.status = SAnime.ONGOING
                        anime.description = "A webcomic of romance, sarcasm, math and language"
                        arrayListDetails.add(anime)
                    }
                    hasNextPage = (arrayListDetails.isNotEmpty())
                    continuation.resume(AnimesPage(arrayListDetails, hasNextPage))
                }
            })
        }
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: FilterList): Observable<AnimesPage> = Observable.just(AnimesPage(emptyList(), false))

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = fetchPopularAnime(1)
        .map { it.animes.first().apply { initialized = true } }

    override fun episodeListSelector() = "div#middleContainer.box a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.url = element.attr("href")
        val number = episode.url.removeSurrounding("/")
        episode.episode_number = number.toFloat()
        episode.name = number + " - " + element.text()
        episode.date_upload = element.attr("title").let {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(it)?.time ?: 0L
        }
        return episode
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
        const val thumbnailUrl = "https://fakeimg.pl/550x780/ffffff/6E7B91/?text=xkcd&font=museo"
        const val baseAltTextUrl = "https://fakeimg.pl/1500x2126/ffffff/000000/?text="
        const val baseAltTextPostUrl = "&font_size=42&font=museo"
    }
}
