package eu.kanade.tachiyomi.animeextension.all.onepace

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.net.URLEncoder

open class Onepace(override val lang: String, override val name: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val baseUrl = "https://www.zippyshare.com/rest/public/getTree?user=onepace&ident=kbvatgfc&id=%23"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = mutableListOf<SAnime>()
        val document = client.newCall(GET(baseUrl)).execute().asJsoup()
        val responseJson = json.decodeFromString<JsonObject>(document.select("body").text().dropLast(1).drop(1))
        val childrenJson = responseJson["children"]?.jsonArray
        // 0 = eng, 1 = sp, 2 = fr
        val langId = when (lang) {
            "es" -> 1
            "en" -> 0
            "fr" -> 2
            else -> 0
        }
        val langAnJson = childrenJson!![langId].jsonObject["children"]!!.jsonArray
        langAnJson.forEach {
            val anName = it.jsonObject["text"].toString().replace("\"", "")
            val anId = it.jsonObject["li_attr"]!!.jsonObject["ident"].toString().replace("\"", "")
            val anStatus = if (anName.contains("Completo")) SAnime.COMPLETED else SAnime.ONGOING
            val thumUrl = thumAnimeParser(anName)
            animes.add(
                SAnime.create().apply {
                    title = anName
                    status = anStatus
                    url = "https://www.zippyshare.com/onepace/$anId/dir.html"
                    thumbnail_url = thumUrl
                }
            )
        }

        return AnimesPage(animes, false)
    }

    private fun thumAnimeParser(animeName: String): String {
        val document = client.newCall(GET("https://onepace.net/_next/data/BM0nGdjN96o4xOSQR37x8/es/watch.json")).execute().asJsoup()
        val jsonResponse = json.decodeFromString<JsonObject>(document.body().text())["pageProps"]!!
        val arcsJson = jsonResponse.jsonObject["arcs"]!!.jsonArray
        arcsJson.forEach {
            val thumId = it.jsonObject["images"]!!.jsonArray[0].jsonObject["src"].toString().replace("\"", "")
            val langTitle = it.jsonObject["translations"]!!.jsonArray
            langTitle.forEach { j ->
                val langCode = j.jsonObject["language"]!!.jsonObject["code"]
                if (langCode.toString().replace("\"", "") == lang) {
                    val title = j.jsonObject["title"].toString().replace("\"", "")
                    if (animeName.lowercase().contains(title.lowercase())) return "https://onepace.net/_next/image?url=%2Fimages%2Farcs%2F$thumId&w=828&q=75"
                }
            }
        }
        return ""
    }

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    override fun popularAnimeSelector() = throw Exception("not used")

    override fun popularAnimeFromElement(element: Element) = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val Realurl = response.request.url.toString().substringAfter("%23")
        val jsoup = client.newCall(GET(Realurl)).execute().asJsoup()
        jsoup.select("table.listingplikow tbody tr.filerow.even").forEach {
            val epName = it.select("td.cien a.name").text().replace(".mp4", "")
            val epNum = epName.substringAfter("][").substringBefore("]").replace("-", ".").replace(",", ".").toFloat()
            val epUrl = it.select("td.cien a.name").attr("href")
            episodes.add(
                SEpisode.create().apply {
                    name = epName
                    url = epUrl
                    episode_number = epNum
                }
            )
        }

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        // This is a shit but work
        val realUrl = "https:" + response.request.url.toString().substringAfter("%23")
        val hostUrl = realUrl.substringBefore("/v/")
        val document = client.newCall(GET(realUrl)).execute().asJsoup()
        val timeId = document.selectFirst("script:containsData(var a = function())").data().substringAfter("+(").substringBefore("%1000")
        val videoId = realUrl.substringAfter("/v/").substringBefore("/file")
        val videoName = document.selectFirst("div.center div font+font").text()
        val videoUrl = "$hostUrl/d/$videoId/${(timeId.toInt() % 1000) + 11}/${URLEncoder.encode(videoName)}"
        return listOf(Video(videoUrl, "ZippyShare", videoUrl, null))
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeSelector() = throw Exception("not used")

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun animeDetailsParse(document: Document) = throw Exception("ඞ")

    override fun setupPreferenceScreen(screen: PreferenceScreen) = throw Exception("not used")
}
