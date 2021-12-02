package eu.kanade.tachiyomi.animeextension.ar.faselhd

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class FASELHD : ParsedAnimeHttpSource() {

    override val name = "فاصل اعلاني"

    override val baseUrl = "https://www.faselhd.pro"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Popular Anime

    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    // Episodes

    private fun seasonsNextPageSelector(seasonNumber: Int) = "div#seasonList div.col-xl-2:nth-child($seasonNumber)" // "div.List--Seasons--Episodes > a:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        var seasonNumber = 1
        fun addEpisodes(document: Document) {
            document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
            document.select(seasonsNextPageSelector(seasonNumber)).firstOrNull()?.let {
                seasonNumber++
                addEpisodes(client.newCall(GET("https://www.faselhd.pro/?p=" + it.select("div.seasonDiv").attr("data-href"), headers)).execute().asJsoup())
            }
        }

        addEpisodes(response.asJsoup())
        return episodes
    }


    override fun episodeListSelector() = "div.epAll a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.text()
        episode.episode_number = element.text().replace("الحلقة ", "").toFloat()
        return episode
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("src")
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "script:containsData(quality)"

    private fun videosFromElement(element: Element): List<Video> {
        val data = element.data().substringAfter("if (quality == \"auto\")").substringBefore(";")
        val sources = data.split("link = ").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringAfter("\"").substringBeforeLast("\";").replace("\\/", "/").replace("\"", "")
            val size = "Auto"
            val video = Video(src, size, src, null)
            videoList.add(video)
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img, img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img, img").attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun searchAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {

                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                }
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.title.h1").text()
        anime.genre = document.select("span:contains(تصنيف) > a, span:contains(مستوى) > a").joinToString(", ") { it.text() }
        anime.thumbnail_url = document.select("div.posterImg img.poster").attr("src")
        anime.description = document.select("div.singleDesc p").text()
        anime.status = parseStatus(document.select("span:contains(حالة)").text().replace("حالة ", "").replace("المسلسل : ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "مستمر" -> SAnime.ONGOING
            // "Completed" -> SAnime.COMPLETED
            else -> SAnime.COMPLETED
        }
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page")

    override fun latestUpdatesSelector(): String = "div#postList div.col-xl-2 a"

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList())
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("تصنيف المسلسلات", vals)

    private fun getGenreList() = arrayOf(
        Pair("مسلسلات الأنمي", "anime"),
        Pair("جميع المسلسلات", "series"),
        Pair("الاعلي مشاهدة", "series_top_views"),
        Pair("الاعلي تقييما IMDB", "series_top_imdb"),
        Pair("المسلسلات القصيرة", "short_series"),
        Pair("المسلسلات الاسيوية", "asian-series"),
        Pair("المسلسلات الاسيوية الاعلي مشاهدة", "asian_top_views"),
        Pair("الانمي الاعلي مشاهدة", "anime_top_views")
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
