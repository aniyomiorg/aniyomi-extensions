package eu.kanade.tachiyomi.animeextension.uk.ufdub

import android.net.Uri
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class UFDub : ParsedAnimeHttpSource() {

    override val lang = "uk"
    override val name = "UFDub"
    override val supportsLatest = true
    private val animeSelector = "div.short"
    private val nextPageSelector = "div.pagi-nav a"

    override val baseUrl = "https://ufdub.com/anime"
    private val baseUrlWithoutAnime = "https://ufdub.com"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        val someInfo = document.select("div.full-desc")

        anime.thumbnail_url = baseUrlWithoutAnime + document.select("div.f-poster img").attr("src")
        anime.title = document.select("h1.top-title").text()
        anime.description = document.select("div.full-text p").text()

        someInfo.select(".full-info div.fi-col-item")
            .forEach {
                    ele ->
                when (ele.select("span").text()) {
                    "Студія:" -> anime.author = ele.select("a").text()
                    "Жанр:" -> anime.genre = ele.select("a").text().replace(" ", ", ")
                }
            }
        return anime
    }

    // ============================== Popular ===============================

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("div.m-views").attr("data-link"))
        anime.thumbnail_url = baseUrlWithoutAnime + element.select("div.short-i > img").attr("src")
        anime.title = element.select("div.short-t-or").text()
        return anime
    }

    override fun popularAnimeNextPageSelector() = nextPageSelector

    override fun popularAnimeRequest(page: Int): Request {
        val body = FormBody.Builder()
            .add("dlenewssortby", "rating")
            .add("dledirection", "desc")
            .add("set_new_sort", "dle_sort_cat_12")
            .add("set_direction_sort", "dle_direction_cat_12")
            .build()
        return POST("$baseUrl/page/$page", body = body)
    }

    override fun popularAnimeSelector(): String = animeSelector

    // =============================== Latest ===============================

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/page/$page", headers)

    override fun latestUpdatesSelector() = animeSelector

    // =============================== Search ===============================

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = nextPageSelector

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("full_search", "1")
            .add("result_from", "1")
            .add("story", query)
            .build()
        return POST("$baseUrlWithoutAnime/index.php?do=search", body = body)
    }

    override fun searchAnimeSelector() = animeSelector

    // ============================== Episode ===============================

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animePage = response.asJsoup()

        // Get Player URL
        val playerURl = animePage.select("input[value*=https://video.ufdub.com]").attr("value")

        // Parse only player
        val player = client.newCall(GET(playerURl))
            .execute()
            .asJsoup().select("script").html()

        // Parse all episodes
        val regexUFDubEpisodes = """https:\/\/ufdub.com\/video\/VIDEOS\.php\?(.*?)'""".toRegex()
        val matchResult = regexUFDubEpisodes.findAll(player)

        // Add to SEpisode
        val episodeList = mutableListOf<SEpisode>()
        for (item: MatchResult in matchResult) {
            val parsedUrl = Uri.parse(item.value)

            val episode = SEpisode.create()

            episode.name = parsedUrl.getQueryParameter("Seriya")!!
            episode.url = item.value.dropLast(1) // Drop '
            episodeList.add(episode)
        }

        return episodeList.reversed()
    }

    // ============================ Video ===============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoUrl = client.newCall(GET(episode.url)).execute().request.url.toString().replace("dl=1", "raw=1")
        Log.d("fetchVideoList", videoUrl)
        val video = Video(videoUrl, "Quality", videoUrl)
        return listOf(video)
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
}
