package eu.kanade.tachiyomi.animeextension.en.genoanime

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GenoAnime : ParsedAnimeHttpSource() {

    override val name = "Genoanime"
    override val baseUrl = "https://www.genoanime.com"
    override val lang = "en"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    private val weserv = "https://images.weserv.nl/?w=400&q=60&url="

    // Popular Anime
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=top_rated&page=$page")

    override fun popularAnimeSelector(): String = "div.trending__product div.col-lg-10 div.row div.col-lg-3.col-6"
    override fun popularAnimeNextPageSelector(): String =
        "div.text-center a i.fa.fa-angle-double-right"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val tempurl =
            "$baseUrl/" + element.select("div.product__item a").attr("href").removePrefix("./")
        anime.setUrlWithoutDomain(tempurl)
        anime.title = element.select("div.product__item__text h5 a:nth-of-type(2)").first().text()
        val thumburl =
            element.select("div.product__item__pic").attr("data-setbg").removePrefix("./")
        anime.thumbnail_url = "$weserv$baseUrl/$thumburl"
        return anime
    }

    // Latest Anime
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=latest&page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    // Search Anime
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val formBody = FormBody.Builder()
            .add("anime", query)
            .build()
        val newHeaders = headersBuilder()
            .set("Content-Length", formBody.contentLength().toString())
            .set("Content-Type", formBody.contentType().toString())
            .build()
        return POST("$baseUrl/data/searchdata.php", newHeaders, formBody)
    }

    override fun searchAnimeSelector(): String = "div.col-lg-3"
    override fun searchAnimeNextPageSelector(): String =
        "div.text-center.product__pagination a.search-page i.fa.fa-angle-double-left"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val tempurl = "$baseUrl/" + element.select("a").attr("href").removePrefix("./")
        val thumburl =
            element.select("div.product__item div.product__item__pic.set-bg").attr("data-setbg")
                .removePrefix("./")
        anime.setUrlWithoutDomain(tempurl)
        anime.title = element.select("div.product__item__text h5 a:nth-of-type(2)").text()
        anime.thumbnail_url = "$weserv$baseUrl/$thumburl"
        return anime
    }

    // Episode
    override fun episodeListSelector() = "div.anime__details__episodes div.tab-pane a"
    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.select("a").text()
        val episodeNumberString = element.text().removePrefix("Ep ")
        episode.episode_number = episodeNumberString.toFloat()
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    // Video
    override fun videoUrlParse(document: Document): String = throw Exception("Not used.")
    override fun videoListSelector() =
        "section.details.spad div.container div.row:nth-of-type(1) div.col-lg-12:nth-of-type(1)"

    override fun videoFromElement(element: Element): Video {
        val baaseurl = element.select("div#video iframe#iframe-to-load").attr("src")
        Log.d(name, "BaseUrl: $baaseurl")
        if (baaseurl.contains("https://genoanime.com/doodplayer.php")) {
            val baseurl = videoidgrab(element.select("div#video iframe#iframe-to-load").attr("src"))
            Log.d(name, "Dood True: $baseurl")
            val a = doodUrlParse(baseurl)
            Log.d(name, "Dood parsed: $a")
            return Video(
                baseurl,
                "Doodstream",
                a,
                null,
                Headers.headersOf("Referer", baseurl)
            )
        } else {
            Log.d(name, "Dood False: " + element.select("video source").attr("src"))
            return Video(
                element.select("video source").attr("src"),
                "Unknown quality",
                element.select("video source").attr("src"),
                null,
                Headers.headersOf("Referer", baseUrl),
            )
        }
    }

    // Anime window
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val thumburl =
            document.select("div.anime__details__pic").attr("data-setbg").removePrefix("./")
        anime.thumbnail_url = "$baseUrl/$thumburl"
        anime.title = document.select("div.anime__details__title h3").text()
        anime.genre = document.select("div.col-lg-6.col-md-6:nth-of-type(1) ul li:nth-of-type(3)")
            .joinToString(", ") { it.text() }.replace("Genre:", "")
        anime.description = document.select("div.anime__details__title span").text()
        document.select("div.col-lg-6.col-md-6:nth-of-type(2) ul li:nth-of-type(2)").text()
            ?.also { statusText ->
                when {
                    statusText.contains("Ongoing", true) -> anime.status = SAnime.ONGOING
                    statusText.contains("Completed", true) -> anime.status = SAnime.COMPLETED
                    else -> anime.status = SAnime.UNKNOWN
                }
            }
//        if (anime.status == SAnime.ONGOING) {
//            val(aiiringat, epiisode, animeid) = next_ep_ween(anime.title)
//            Log.d("$name status.ONGOING", anime.title)
//            Log.d("$name airingat", aiiringat.toString())
//            anime.next_ep_wen = airingmsg(epiisode, aiiringat)
//        }
        return anime
    }

    // Custom Fun
    private fun doodUrlParse(url: String): String {
        val response = client.newCall(GET(url.replace("/e/", "/d/"))).execute()
        val content = response.body!!.string()
        val md5 = content.substringAfter("/download/").substringBefore("\"")
        var abc = doodreq(url, md5)
        while (abc.contains("""<b class="err">Security error</b>""")) {
            Log.d(name, "Dood bs. Trying again.")
            abc = doodreq(url, md5)
        }
        return abc
    }

    private fun doodreq(url: String, md5: String): String {
        return client.newCall(
            GET(
                "https://dood.ws/download/$md5",
                Headers.headersOf("referer", url)
            )
        ).execute().body!!.string().substringAfter("window.open('").substringBefore("\'")
    }

    private fun videoidgrab(url: String): String {
        Log.d(name, "given url: $url")
        val uwrl = """https://goload.one/streaming.php?id=${url.substringAfter("&vidid=")}"""
        Log.d(name, "golandUrl: $uwrl")
        val content = client.newCall(GET(uwrl)).execute().body!!.string().substringAfter("dood").substringBefore("\"")
        Log.d(name, "doodUrl: https://dood$content")
        return "https://dood$content"
    }
}

