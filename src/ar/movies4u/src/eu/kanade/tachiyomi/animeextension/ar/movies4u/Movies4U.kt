package eu.kanade.tachiyomi.animeextension.ar.movies4u

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Movies4U : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "موفيز فور يو"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://movies4u.cam")!! }
    // "https://movies4u.cam"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    /*override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://animeblkom.net")
    }*/

    // Popular

    override fun popularAnimeSelector(): String = "div.container div.row div.col-6 div.card div.card__cover"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("img").attr("alt").replace("مشاهدة", "").replace("فيلم", "").replace("مترجم", "")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.paginator li.paginator__item paginator__item--next a"

    // Episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seriesLink = document.select("link[rel=canonical]").attr("href")
        if (seriesLink.contains("/series/")) {
            val seasonUrl = seriesLink
            val seasonsHtml = client.newCall(
                GET(
                    seasonUrl,
                    headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().asJsoup()
            val seasonsElements = seasonsHtml.select("div.col-6.col-sm-4.col-md-3.col-xl-2 div.card")
            seasonsElements.forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            val movieUrl = seriesLink
            val episode = SEpisode.create()
            episode.name = document.select("div.col-12 > h1").text().replace("مشاهدة", "").replace("فيلم", "").replace("مترجم", "")
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(movieUrl)
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.select("div.card__content h3.card__title a").attr("href")
        val seasonName = element.select("div.card__content h3.card__title a").text()
        val episodesHtml = client.newCall(
            GET(
                seasonId,
            )
        ).execute().asJsoup()
        val episodeElements = episodesHtml.select("div.col-6.col-sm-4.col-md-3.col-xl-2")
        return episodeElements.map { episodeFromElement(it, seasonName) }
    }

    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("h3.card__title a").attr("abs:href").substringAfter("episode-").toFloat()
        // val SeasonNum = element.ownerDocument().select("div.Title span").text()
        episode.name = "$seasonName" + ": " + element.select("h3.card__title a").text()
        episode.setUrlWithoutDomain(element.select("h3.card__title a").attr("abs:href"))
        return episode
    }

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe1 = client.newCall(GET(document.selectFirst("iframe#video").attr("data-src")))
            .execute().asJsoup()
        val iframe = iframe1.selectFirst("iframe").attr("src")

        val referer = response.request.url.encodedPath
        val newHeaders = Headers.headersOf("referer", baseUrl + referer)
        val iframeResponse = client.newCall(GET(iframe, newHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "script:containsData(source)"

    private fun videosFromElement(element: Element): List<Video> {
        val data = element.data().substringAfter("sources: [").substringBefore("],")
        val sources = data.split("file:\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringBefore("\"")
            val quality = source.substringAfter("label:\"").substringBefore("\"") // .substringAfter("format: '")
            val video = Video(src, quality, src)
            videoList.add(video)
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    private fun loadToken(): String? {
        val tokenUrl = client.newCall(GET(baseUrl))
            .execute().asJsoup()
        val token = tokenUrl.select("meta[name=csrf-token]").attr("content")
        return token
    }
    /*private fun loadToken(): String? {
        val c = "d"
    }*/

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        // anime.setUrlWithoutDomain(element.attr("href"))
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        // anime.thumbnail_url = element.select("img").attr("data-src")
        // anime.title = element.text()
        anime.title = element.select("img").attr("alt").replace("مشاهدة", "").replace("فيلم", "").replace("مترجم", "")
        // .select("img").attr("alt").removePrefix(" poster")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.paginator li.paginator__item paginator__item--next a"

    override fun searchAnimeSelector(): String = "div.container div.row div.col-6 div.card div.card__cover"

    // override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/auto-complete?_token=${loadToken()}&q=$query")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/auto-complete?_token=${loadToken()}&q=$query".replace(" ", "%20")
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is GenreList -> {
                        if (filter.state > 0) {
                            val genreN = getGenreList()[filter.state].query
                            val genreUrl = "$baseUrl/movies?category=$genreN&quality=&imdb=0.0|10.0&year=1900|2021&page=$page"
                            return GET(genreUrl, headers)
                        }
                    }
                    is GenreList2 -> {
                        if (filter.state > 0) {
                            val genreN = getGenreList()[filter.state].query
                            val genreUrl = "$baseUrl/series?category=$genreN&quality=undefined&imdb=0.0|10.0&year=1900|2021&page=$page"
                            return GET(genreUrl, headers)
                        }
                    }
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.card--details div.card__cover img").attr("src")
        anime.title = document.select("div.col-12 h1").text().replace("مشاهدة", "").replace("فيلم", "").replace("مترجم", "")
        anime.genre = document.select("ul.card__meta li a").joinToString(", ") { it.text() }
        anime.description = document.select("div.card__description").text()
        // anime.author = document.select("div:contains(الاستديو) span > a").text()
        anime.status = SAnime.COMPLETED
        // anime.artist = document.select("div:contains(المخرج) > span.info").text()
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // preferred quality settings

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
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("movies4u.cam", "movie4u.watch")
            entryValues = arrayOf("https://movies4u.cam", "https://movie4u.watch")
            setDefaultValue("https://movies4u.cam")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref) // domainPref
        screen.addPreference(domainPref) //
    }

    // Filter

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        GenreList(genresName),
        GenreList2(genresName),
    )

    private class GenreList(genres: Array<String>) : AnimeFilter.Select<String>("تصنيف الافلام", genres)
    private class GenreList2(genres: Array<String>) : AnimeFilter.Select<String>("تصنيف المسلسلات", genres)
    private data class Genre(val name: String, val query: String)
    private val genresName = getGenreList().map {
        it.name
    }.toTypedArray()

    private fun getGenreList() = listOf(
        Genre("الكل", ""),
        Genre("أثارة", "22"),
        Genre("اكشن", "23"),
        Genre("انيميشن", "24"),
        Genre("تاريخ", "25"),
        Genre("جريمة", "26"),
        Genre("حرب", "27"),
        Genre("خيال", "28"),
        Genre("خيال علمي", "29"),
        Genre("دراما", "30"),
        Genre("رعب", "31"),
        Genre("رومانسية", "32"),
        Genre("رياضة", "33"),
        Genre("سيرة ذاتية", "34"),
        Genre("عائلية", "35"),
        Genre("غربية", "36"),
        Genre("غموض", "37"),
        Genre("كوميديا", "38"),
        Genre("مغامرة", "39"),
        Genre("موسيقية", "40"),
        Genre("وثائقية", "41")
    )
}
