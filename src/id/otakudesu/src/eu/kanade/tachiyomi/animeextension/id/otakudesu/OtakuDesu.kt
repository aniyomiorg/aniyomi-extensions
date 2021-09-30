package eu.kanade.tachiyomi.animeextension.id.otakudesu

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception
import java.text.SimpleDateFormat

class OtakuDesu : ParsedAnimeHttpSource() {

    override val name = "OtakuDesu"

    override val baseUrl = "https://otakudesu.moe"

    override val lang = "id"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val zing = document.select("div.infozingle")
        val status = parseStatus(zing.select("p:nth-child(6) > span").text().replace("Status: ", ""))
        anime.title = zing.select("p:nth-child(1) > span").text().replace("Judul: ", "")
        anime.genre = zing.select("p:nth-child(11) > span").text().replace("Genre: ", "")
        anime.status = status
        anime.artist = zing.select("p:nth-child(10) > span").text()
        anime.author = zing.select("p:nth-child(4) > span").text()

// others

// Jap title
        anime.description = document.select("p:nth-child(2) > span").text()
// Score
        anime.description = anime.description + "\n" + document.select("p:nth-child(3) > span").text()
// Total Episode
        anime.description = anime.description + "\n" + document.select("p:nth-child(7) > span").text()

// Genre
        anime.description = anime.description + "\n\n\nSynopsis: \n" + document.select("div.sinopc > p").joinToString("\n\n") { it.text() }

        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("span > a").attr("href"))
        episode.episode_number = getNumberFromEpsString(element.select("span > a").text()).toFloat()
        episode.name = element.select("span > a").text()
        episode.date_upload = reconstructDate(element.select("span.zeebr").text())

        return episode
    }
    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    private fun reconstructDate(Str: String): Long {
        val newStr = Str.replace("Januari", "Jan").replace("Februari", "Feb").replace("Maret", "Mar").replace("April", "Apr").replace("Mei", "May").replace("Juni", "Jun").replace("Juli", "Jul").replace("Agustus", "Aug").replace("September", "Sep").replace("Oktober", "Oct").replace("November", "Nov").replace("Desember", "Dec")

        val pattern = SimpleDateFormat("d MMM yyyy")
        return pattern.parse(newStr.replace(",", " "))!!.time
    }

    override fun episodeListSelector(): String = "#venkonten > div.venser > div:nth-child(8) > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.thumb > a").first().attr("href"))
        anime.thumbnail_url = element.select("div.thumb > a > div.thumbz > img").first().attr("src")
        anime.title = element.select("div.thumb > a > div.thumbz > h2").text()
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "a.next.page-numbers"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ongoing-anime/page/$page")

    override fun latestUpdatesSelector(): String = "div.detpost"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.thumb > a").first().attr("href"))
        anime.thumbnail_url = element.select("div.thumb > a > div.thumbz > img").first().attr("src")
        anime.title = element.select("div.thumb > a > div.thumbz > h2").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/complete-anime/page/$page")

    override fun popularAnimeSelector(): String = "div.detpost"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("h2 > a").first().attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.select("h2 > a").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
//        val filterList = if (filters.isEmpty()) getFilterList() else filters /* eta for v2 /*
//        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter /* eta for v2 /*
        return GET("$baseUrl/?s=$query&post_type=anime")
    }

    override fun searchAnimeSelector(): String = "#venkonten > div > div.venser > div > div > ul > li"

    override fun videoFromElement(element: Element): Video {
        val iframe = client.newCall(GET(element.attr("src"))).execute()
        val res = iframe.asJsoup()
        val source = res.select("#mediaplayer > source")

        return if (!source.isNullOrEmpty()) {
            val url = source.attr("src")
            Video(url, "360p", url, null)
        } else {
            val htmlbody = res.body().html()
            val pattern1 = htmlbody.substringAfter("\"file\":").substringBefore("\",\"").replace("\"", "")
            val pattern2 = htmlbody.substringAfter("'file':").substringBefore("','").replace("'", "")
            when {
                htmlbody.contains("\"file\":") -> Video(pattern1, "360p", pattern1, null)
                htmlbody.contains("'file':") -> Video(pattern2, "360p", pattern2, null)
                else -> throw Exception("couldn't find stream link,you can download it manually from the web")
            }
        }
    }

    override fun videoListSelector(): String = "#pembed > div > iframe"

    override fun videoUrlParse(document: Document) = throw Exception("not used")
}
