package eu.kanade.tachiyomi.animeextension.en.nollyverse

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class NollyVerse : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "NollyVerse"

    override val baseUrl = "https://www.nollyverse.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    private fun toImgUrl(inputUrl: String): String {
        val url = inputUrl.removeSuffix("/").toHttpUrl()
        val pathSeg = url.encodedPathSegments.toMutableList()
        pathSeg.add(1, "img")
        return url.scheme +
            "://" +
            url.host +
            "/" +
            pathSeg.joinToString(separator = "/") +
            ".jpg"
    }

    override fun popularAnimeNextPageSelector(): String = "div.loadmore ul.pagination.pagination-md li:nth-last-child(2)"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }

        val hasNextPage = popularAnimeNextPageSelector()?.let { selector ->
            if (document.select(selector).text() != ">") {
                return AnimesPage(animes, false)
            }
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeSelector(): String = "div.col-md-8 div.row div.col-md-6"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/category/trending-movies/page/$page/")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("div.post-body h3 a").text()
        anime.thumbnail_url = element.select("a.post-img img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a.post-img").attr("href").toHttpUrl().encodedPath)
        return anime
    }

    // Episodes

    override fun episodeListRequest(anime: SAnime): Request {
        return if (anime.url.startsWith("/movie/")) {
            GET(baseUrl + anime.url + "/download/", headers)
        } else {
            GET(baseUrl + anime.url + "/seasons/", headers)
        }
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val path = response.request.url.encodedPath

        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        if (path.startsWith("/movie/")) {
            val episode = SEpisode.create()
            episode.name = "Movie"
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(path)
            episodeList.add(episode)
        } else {
            var counter = 1
            for (season in document.select("table.table.table-striped tbody tr").reversed()) {
                val seasonUrl = season.select("td a[href]").attr("href")
                val seasonSoup = client.newCall(
                    GET(seasonUrl, headers)
                ).execute().asJsoup()

                val episodeTable = seasonSoup.select("table.table.table-striped")
                val seasonNumber = episodeTable.select("thead th").eachText().find {
                    t ->
                    """Season (\d+)""".toRegex().matches(t)
                }?.split(" ")!![1]

                for (ep in episodeTable.select("tbody tr")) {
                    val episode = SEpisode.create()

                    episode.name = "Episode S${seasonNumber}E${ep.selectFirst("td").text().split(" ")!![1]}"
                    episode.episode_number = counter.toFloat()
                    episode.setUrlWithoutDomain(seasonUrl + "#$counter")
                    episodeList.add(episode)

                    counter++
                }

                // Stop API abuse
                Thread.sleep(500)
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("td > span.Num").text().toFloat()
        val seasonNum = element.ownerDocument().select("div.Title span").text()
        episode.name = "Season $seasonNum" + "x" + element.select("td span.Num").text() + " : " + element.select("td.MvTbTtl > a").text()
        episode.setUrlWithoutDomain(element.select("td.MvTbPly > a.ClA").attr("abs:href"))
        return episode
    }

    // Video urls
    override fun videoListRequest(episode: SEpisode): Request {
        return if (episode.name == "Movie") {
            GET(baseUrl + episode.url + "#movie", headers)
        } else {
            val episodeIndex = """Episode S(\d+)E(?<num>\d+)""".toRegex().matchEntire(
                episode.name
            )!!.groups["num"]!!.value
            GET(baseUrl + episode.url.replaceAfterLast("#", "") + episodeIndex, headers)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = response.asJsoup()

        val fragment = response.request.url.fragment!!
        if (fragment == "movie") {
            for (res in document.select("table.table.table-striped tbody tr")) {
                val url = res.select("td a").attr("href")
                val name = res.select("td:not(:has(a))").text().trim()
                videoList.add(Video(url, name, url))
            }
        } else {
            val episodeIndex = fragment.toInt() - 1

            val episodeList = document.select("table.table.table-striped tbody tr").toList()

            for (res in episodeList[episodeIndex].select("td").reversed()) {
                val url = res.select("a").attr("href")
                if (url.isNotEmpty()) {
                    videoList.add(
                        Video(url, res.text().trim(), url)
                    )
                }
            }
        }

        return videoList.sort()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        val codec = preferences.getString("preferred_codec", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    if (codec?.let { video.quality.contains(it) } == true) {
                        newList.add(0, video)
                    } else {
                        newList.add(preferred, video)
                    }
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val path = response.request.url.encodedPath

        var hasNextPage: Boolean
        var animes: List<SAnime>

        when {
            path.startsWith("/livesearch") -> {
                hasNextPage = false
                animes = document.select(searchAnimeSelector()).map { element ->
                    searchAnimeFromElement(element)
                }
            }

            path.startsWith("/movies/genre/") -> {
                animes = document.select(movieGenreSelector()).map { element ->
                    movieGenreFromElement(element)
                }
                hasNextPage = NextPageSelector()?.let { selector ->
                    if (document.select(selector).text() != ">") {
                        return AnimesPage(animes, false)
                    }
                    document.select(selector).first()
                } != null
            }

            path.startsWith("/series/genre/") ||
                path.startsWith("/category/popular-movies/") ||
                path.startsWith("/category/trending-movies/") -> {
                animes = document.select(seriesGenreSelector()).map { element ->
                    seriesGenreFromElement(element)
                }
                hasNextPage = NextPageSelector()?.let { selector ->
                    if (document.select(selector).text() != ">") {
                        return AnimesPage(animes, false)
                    }
                    document.select(selector).first()
                } != null
            }

            path.startsWith("/category/korean-movies/") || path.startsWith("/category/korean-series/") -> {
                animes = document.select(koreanSelector()).map { element ->
                    koreanFromElement(element)
                }
                hasNextPage = NextPageSelector()?.let { selector ->
                    if (document.select(selector).text() != ">") {
                        return AnimesPage(animes, false)
                    }
                    document.select(selector).first()
                } != null
            }

            path.startsWith("/category/latest-movies/") ||
                path.startsWith("/category/new-series/") ||
                path.startsWith("/category/latest-uploads/") -> {
                animes = document.select(latestSelector()).map { element ->
                    latestFromElement(element)
                }
                hasNextPage = NextPageSelector()?.let { selector ->
                    if (document.select(selector).text() != ">") {
                        return AnimesPage(animes, false)
                    }
                    document.select(selector).first()
                } != null
            }

            path.startsWith("/series/") -> {
                animes = document.select(seriesSelector()).map { element ->
                    seriesFromElement(element)
                }
                hasNextPage = false
            }

            else -> {
                animes = document.select(movieSelector()).map { element ->
                    movieFromElement(element)
                }
                hasNextPage = NextPageSelector()?.let { selector ->
                    if (document.select(selector).text() != ">") {
                        return AnimesPage(animes, false)
                    }
                    document.select(selector).first()
                } != null
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    private fun NextPageSelector(): String = "ul.pagination.pagination-md li:nth-last-child(2)"

    private fun movieGenreSelector(): String = "div.container > div.row > div.col-md-4"

    private fun movieGenreFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    private fun seriesGenreSelector(): String = "div.row div.col-md-8 div.col-md-6"

    private fun seriesGenreFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    private fun koreanSelector(): String = "div.col-md-8 div.row div.col-md-6"

    private fun koreanFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    private fun latestSelector(): String = latestUpdatesSelector()

    private fun latestFromElement(element: Element): SAnime {
        return latestUpdatesFromElement(element)
    }

    private fun seriesSelector(): String = "div.section-row ul.list-style li"

    private fun seriesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("a").text()
        anime.thumbnail_url = toImgUrl(element.select("a").attr("href"))
        anime.setUrlWithoutDomain(element.select("a").attr("href").toHttpUrl().encodedPath)
        return anime
    }

    private fun movieSelector(): String = "div.container div.row div.col-md-12 div.col-md-4"

    private fun movieFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("h3 a").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        anime.setUrlWithoutDomain(element.select("a.post-img").attr("href").toHttpUrl().encodedPath)
        return anime
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.text()
        anime.thumbnail_url = toImgUrl(element.attr("href"))
        anime.setUrlWithoutDomain(element.attr("href").toHttpUrl().encodedPath)
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = throw Exception("Not used")

    override fun searchAnimeSelector(): String = "a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val body = FormBody.Builder()
                .add("name", "$query")
                .build()
            return POST("$baseUrl/livesearch.php", body = body)
        } else {
            var searchPath = ""
            filters.filter { it.state != 0 }.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> searchPath = if (filter.toUriPart() == "/series/") filter.toUriPart() else "${filter.toUriPart()}page/$page"
                    is MovieGenreFilter -> searchPath = "/movies/genre/${filter.toUriPart()}/page/$page"
                    is SeriesGenreFilter -> searchPath = "/series/genre/${filter.toUriPart()}/page/$page"
                    else -> ""
                }
            }
            if (searchPath.isEmpty()) {
                throw Exception("Empty search")
            }
            return GET(baseUrl + searchPath)
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.page-header div.container div.row div.text-center h1").text()
        anime.description = document.select("blockquote.blockquote small").text()
        document.select("div.col-md-8 ul.list-style li").forEach {
            if (it.text().startsWith("Genre: ")) {
                anime.genre = it.text().substringAfter("Genre: ").replace(",", ", ")
            }
        }
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "div.loadmore ul.pagination.pagination-md li:nth-last-child(2)"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }

        val hasNextPage = latestUpdatesNextPageSelector()?.let { selector ->
            if (document.select(selector).text() != ">") {
                return AnimesPage(animes, false)
            }
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("a.post-img").attr("href").toHttpUrl().encodedPath)
        anime.title = element.select("div.post-body h3 a").text()
        anime.thumbnail_url = element.select("a.post-img img").attr("data-src")
        if (anime.thumbnail_url.toString().isEmpty()) {
            anime.thumbnail_url = element.select("a.post-img img").attr("src")
        }
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/category/new-series/page/$page/")
    }

    override fun latestUpdatesSelector(): String = "div.section div.container div.row div.post.post-row"

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Only one works at a time"),
        AnimeFilter.Separator(),
        CategoryFilter(getCategoryList()),
        MovieGenreFilter(getMovieGenreList()),
        SeriesGenreFilter(getSeriesGenreList())
    )

    private class CategoryFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Category", vals)
    private class MovieGenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Movies Genre", vals)
    private class SeriesGenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Series Genres", vals)

    private fun getCategoryList() = arrayOf(
        Pair("None", "none"),
        Pair("All series", "/series/"),
        Pair("All movies", "/movies/"),
        Pair("Koren movies", "/category/korean-movies/"),
        Pair("Koren series", "/category/korean-series/"),
        Pair("Latest movies", "/category/latest-movies/"),
        Pair("Latest series", "/category/new-series/"),
        Pair("Latest uploads", "/category/latest-uploads/"),
        Pair("Popular movies", "/category/popular-movies/"),
        Pair("Trending movies", "/category/trending-movies/")
    )

    private fun getMovieGenreList() = arrayOf(
        Pair("All", "all"),
        Pair("Drama", "drama"),
        Pair("Comedy", "comedy"),
        Pair("Action", "action"),
        Pair("Thriller", "thriller"),
        Pair("Crime", "crime"),
        Pair("Adventure", "adventure"),
        Pair("Mystery", "mystery"),
        Pair("Sci-Fi", "sci-Fi"),
        Pair("Fantasy", "fantasy"),
        Pair("Horror", "horror"),
        Pair("Animation", "animation"),
        Pair("Romance", "romance"),
        Pair("Documentary", "documentary"),
        Pair("Family", "family"),
        Pair("Biography", "biography"),
        Pair("History", "history"),
        Pair("Music", "music"),
        Pair("Sport", "sport"),
        Pair("Musical", "musical"),
        Pair("War", "war"),
        Pair("Western", "western"),
        Pair("News", "news"),
    )

    private fun getSeriesGenreList() = arrayOf(
        Pair("All", "all"),
        Pair("Drama", "drama"),
        Pair("Comedy", "comedy"),
        Pair("Action", "action"),
        Pair("Thriller", "thriller"),
        Pair("Crime", "crime"),
        Pair("Adventure", "adventure"),
        Pair("Mystery", "mystery"),
        Pair("Sci-Fi", "sci-Fi"),
        Pair("Fantasy", "fantasy"),
        Pair("Horror", "horror"),
        Pair("Animation", "animation"),
        Pair("Romance", "romance"),
        Pair("Documentary", "documentary"),
        Pair("Family", "family"),
        Pair("Biography", "biography"),
        Pair("History", "history"),
        Pair("Music", "music"),
        Pair("Sport", "sport"),
        Pair("Musical", "musical"),
        Pair("War", "war"),
        Pair("Western", "western"),
        Pair("Reality-TV", "reality-TV"),
        Pair("Game-Show", "game-show"),
        Pair("News", "news"),
        Pair("Sitcom", "sitcom"),
        Pair("Talk-Show", "talk-show"),
        Pair("Costume", "Costume")
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        val videoCodecPref = ListPreference(screen.context).apply {
            key = "preferred_codec"
            title = "Preferred Video Codec"
            entries = arrayOf("h265", "h264")
            entryValues = arrayOf("265", "264")
            setDefaultValue("265")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoCodecPref)
    }
}
