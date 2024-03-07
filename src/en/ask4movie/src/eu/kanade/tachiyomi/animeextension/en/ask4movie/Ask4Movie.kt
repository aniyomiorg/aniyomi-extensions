package eu.kanade.tachiyomi.animeextension.en.ask4movie

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Ask4Movie : ParsedAnimeHttpSource() {

    override val name = "Ask4Movie"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://ask4movie.li"

    override val lang = "en"

    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/channel_cat/trending/page/$page/", headers)

    override fun popularAnimeSelector(): String = "div.all-channels div.channel-content"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("p.channel-name a")!!.attr("abs:href"))
        thumbnail_url = element.select("div.channel-avatar a img").attr("src")
        title = element.select("p.channel-name a").text()
    }

    override fun popularAnimeNextPageSelector(): String = "div.wp-pagenavi a.nextpostslink"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesSelector(): String = "div.row:has(div.title:contains(Recently Added)) > div.slide > div.slide-item"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val a = element.selectFirst("div.main-slide a[href]")!!

        return SAnime.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            thumbnail_url = element.select("div.item-thumb").attr("style")
                .substringAfter("background-image: url(").substringBefore(")")
            title = a.text()
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val tvSeriesFilter = filterList.find { it is TVSeriesFilter } as TVSeriesFilter
        val moviesFilter = filterList.find { it is MoviesFilter } as MoviesFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query", headers)
            tvSeriesFilter.state != 0 -> GET("$baseUrl${tvSeriesFilter.toUriPart()}${page.toPage()}/")
            moviesFilter.state != 0 -> GET("$baseUrl${moviesFilter.toUriPart()}${page.toPage()}/")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.encodedPath.contains("/channel_cat/")) {
            popularAnimeParse(response)
        } else {
            super.searchAnimeParse(response)
        }
    }

    override fun searchAnimeSelector(): String = "div.cacus-sub-wrap > div.item,div#search-content > div.item"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("div.description a")!!.attr("abs:href"))
        thumbnail_url = element.attr("style")
            .substringAfter("background-image: url(").substringBefore(")")
        title = element.selectFirst("div.description a")!!.text()
    }

    override fun searchAnimeNextPageSelector(): String = "div.wp-pagenavi > span.current + a"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        genre = document.select("div.categories:contains(Genres) a").joinToString(", ") { it.text() }
            .ifBlank { document.selectFirst("div.channel-description > p:has(span:contains(Genre)) em")?.text() }
        description = document.selectFirst("div.custom.video-the-content p")?.ownText()
            ?: document.selectFirst("div.channel-description > p:not(:has(em))")?.text()
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        // Select multiple seasons
        val seasonsList = document.select("div.row > div.cactus-sub-wrap > div.item")
        if (seasonsList.isNotEmpty()) {
            seasonsList.forEach { season ->
                val link = season.selectFirst("a.btn-play-nf")!!.attr("abs:href")
                val seasonName = "Season ${season.selectFirst("div.description p a")!!.text().substringAfter("(Season ").substringBefore(")")} "
                val newDocument = client.newCall(
                    GET(link, headers),
                ).execute().asJsoup()

                val groupList = episodesFromGroupLinks(newDocument, seasonName)
                episodeList.addAll(groupList.ifEmpty { episodesFromSingle(newDocument, seasonName) })
            }
        } else {
            val groupList = episodesFromGroupLinks(document)
            episodeList.addAll(groupList.ifEmpty { episodesFromSingle(document) })
        }

        return episodeList
    }

    // Returns episode list when episodes are in red boxes below the player
    private fun episodesFromGroupLinks(document: Document, prefix: String = ""): List<SEpisode> {
        return document.select("ul.group-links-list > li.group-link").mapNotNull { link ->
            val a = link.selectFirst("a") ?: return@mapNotNull null
            SEpisode.create().apply {
                url = a.attr("data-embed-src")
                episode_number = a.text().toFloatOrNull() ?: 0F
                name = "${prefix}Ep. ${a.text()}"
            }
        }.reversed()
    }

    private fun episodesFromSingle(document: Document, prefix: String = ""): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                url = document.selectFirst("div#player-embed > iframe[src~=filemoon]")!!.attr("src")
                episode_number = 1F
                name = "${prefix}Ep. 1"
            },
        )
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = FilemoonExtractor(client).videosFromUrl(episode.url, headers = headers)
        require(videoList.isNotEmpty()) { "Failed to fetch videos" }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun Int.toPage(): String {
        return if (this == 1) {
            ""
        } else {
            "page/$this"
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        TVSeriesFilter(),
        MoviesFilter(),
    )

    private class TVSeriesFilter : UriPartFilter(
        "TV Series",
        arrayOf(
            Pair("<select>", ""),
            Pair("Show All", "/channel_cat/tv-series/"),
            Pair("Trending", "/channel_cat/trending/"),
            Pair("Popular", "/channel_cat/popular/"),
            Pair("Ongoing", "/channel_cat/ongoing/"),
            Pair("Ended", "/channel_cat/ended/"),
            Pair("Netflix", "/channel_cat/netflix/"),
            Pair("HBO", "/channel_cat/hbo/"),
            Pair("Apple TV+", "/channel_cat/apple-tv/"),
            Pair("Action", "/channel_cat/action/"),
            Pair("Adventure", "/channel_cat/adventure/"),
            Pair("Animated", "/channel_cat/animated/"),
            Pair("Comedy", "/channel_cat/comedy/"),
            Pair("Crime", "/channel_cat/crime/"),
            Pair("Documentary", "/channel_cat/documentary/"),
            Pair("Drama", "/channel_cat/drama/"),
            Pair("Fantasy", "/channel_cat/fantasy/"),
            Pair("Horror", "/channel_cat/horror/"),
            Pair("Thriller", "/channel_cat/thriller/"),
            Pair("Mystery", "/channel_cat/mystery/"),
            Pair("Romance", "/channel_cat/romance/"),
            Pair("Sport", "/channel_cat/sport/"),
            Pair("War", "/channel_cat/war/"),
            Pair("Biography", "/channel_cat/biography/"),
            Pair("History", "/channel_cat/history/"),
            Pair("Sci-Fi", "/channel_cat/sci-fi/"),
        ),
    )

    private class MoviesFilter : UriPartFilter(
        "Movies",
        arrayOf(
            Pair("<select>", ""),
            Pair("NETFLIX", "/netflix-originals/"),
            Pair("Action", "/action/"),
            Pair("Adventure", "/adventure/"),
            Pair("Animated", "/animated/"),
            Pair("Biography", "/biography/"),
            Pair("Comedy", "/comedy/"),
            Pair("Crime", "/crime/"),
            Pair("Drama", "/drama/"),
            Pair("Documentary", "/documentary/"),
            Pair("Fantasy", "/fantasy/"),
            Pair("History", "/history/"),
            Pair("Horror", "/horror/"),
            Pair("Music", "/music/"),
            Pair("Mystery", "/mystery/"),
            Pair("Romance", "/romance/"),
            Pair("Sci-Fi", "/sci-fi/"),
            Pair("Sport", "/sport/"),
            Pair("Thriller", "/thriller/"),
            Pair("War", "/war/"),
            Pair("Western", "/western/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
