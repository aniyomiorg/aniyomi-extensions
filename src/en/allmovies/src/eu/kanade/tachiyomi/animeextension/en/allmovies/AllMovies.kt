package eu.kanade.tachiyomi.animeextension.en.allmovies

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AllMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AllMoviesForYou"

    override val baseUrl = "https://allmoviesforyou.net"

    override val lang = "en"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.TPost > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("h2.Title").text()
        anime.thumbnail_url = "https:" + element.select("div.Image figure img").attr("data-src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nav-links a:last-child"

    // Episodes

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seriesLink = document.select("link[rel=canonical]").attr("abs:href")
        if (seriesLink.contains("/series/")) {
            val seasonsHtml = client.newCall(
                GET(
                    seriesLink,
                    headers = Headers.headersOf("Referer", document.location()),
                ),
            ).execute().asJsoup()
            val seasonsElements = seasonsHtml.select("section.SeasonBx.AACrdn a")
            seasonsElements.forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            val episode = SEpisode.create()
            episode.name = document.select("div.TPMvCn h1.Title").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(seriesLink)
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("td > span.Num").text().toFloat()
        val seasonNum = element.ownerDocument()!!.select("div.Title span").text()
        episode.name = "Season $seasonNum" + "x" + element.select("td span.Num").text() + " : " + element.select("td.MvTbTtl > a").text()
        episode.setUrlWithoutDomain(element.select("td.MvTbPly > a.ClA").attr("abs:href"))
        return episode
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("abs:href")
        val episodesHtml = client.newCall(GET(seasonId)).execute().asJsoup()
        val episodeElements = episodesHtml.select("tr.Viewed")
        return episodeElements.map { episodeFromElement(it) }
    }

    // Video urls

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val iframe = document.select("iframe[src*=/?trembed]").attr("abs:src")
        return GET(iframe)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "iframe"

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val url = element.attr("abs:src")
            val location = element.ownerDocument()!!.location()
            val videoHeaders = Headers.headersOf("Referer", location)
            when {
                url.contains("https://dood") -> {
                    val newQuality = "Doodstream mirror"
                    val video = Video(url, newQuality, doodUrlParse(url), headers = videoHeaders)
                    videoList.add(video)
                }
                url.contains("streamhub") -> {
                    val response = client.newCall(GET(url, videoHeaders)).execute().asJsoup()
                    val script = response.selectFirst("script:containsData(m3u8)")!!
                    val data = script.data()
                    val masterUrl = masterExtractor(data)
                    val masterPlaylist = client.newCall(GET(masterUrl)).execute().body.string()
                    masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                        val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                        val videoUrl = it.substringAfter("\n").substringBefore("\n")
                        videoList.add(Video(videoUrl, quality, videoUrl))
                    }
                }
            }
        }
        return videoList
    }

    private fun masterExtractor(code: String): String {
        val stringsRegex = """(?<!\\)'.+?(?<!\\)'""".toRegex()
        val strings = stringsRegex.findAll(code).map {
            it.value
        }.toList()
        var p = strings[3]
        val k = strings[4].split('|')

        val numbersRegex = """(?<=,)\d+(?=,)""".toRegex()
        val numbers = numbersRegex.findAll(code).map {
            it.value.toInt()
        }.toList()
        val a = numbers[0]
        var c = numbers[1] - 1

        while (c >= 0) {
            val replaceRegex = ("""\b""" + c.toString(a) + """\b""").toRegex()
            p = p.replace(replaceRegex, k[c])
            c--
        }

        val sourcesRegex = """(?<=sources':\[\{src:").+?(?=")""".toRegex()
        return sourcesRegex.find(p)!!.value
    }

    private fun doodUrlParse(url: String): String? {
        val response = client.newCall(GET(url.replace("/d/", "/e/"))).execute()
        val content = response.body.string()
        if (!content.contains("'/pass_md5/")) return null
        val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
        val token = md5.substringAfterLast("/")
        val doodTld = url.substringAfter("https://dood.").substringBefore("/")
        val randomString = getRandomString()
        val expiry = System.currentTimeMillis()
        val videoUrlStart = client.newCall(
            GET(
                "https://dood.$doodTld/pass_md5/$md5",
                Headers.headersOf("referer", url),
            ),
        ).execute().body.string()
        return "$videoUrlStart$randomString?token=$token&expiry=$expiry"
    }

    private fun getRandomString(length: Int = 10): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
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

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("article a").attr("href"))
        anime.title = element.select("h2.Title").text()
        anime.thumbnail_url = "https:" + element.select("div.Image figure img").attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.nav-links a:last-child"

    override fun searchAnimeSelector(): String = "ul.MovieList li"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/category/".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
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
        anime.title = document.select("div.TPMvCn h1.Title").text()
        anime.genre = document.select("p.Genre a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("div.Info").text()) // span.Qlty
        anime.author = document.select("p.Director span a").joinToString(", ") { it.text() }
        anime.description = document.select("div.TPMvCn div.Description p:first-of-type").text()
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("AIR", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList()),
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Genres", vals)

    private fun getGenreList() = arrayOf(
        Pair("Action & Adventure", "action-adventure"),
        Pair("Adventure", "aventure"),
        Pair("Animation", "animation"),
        Pair("Comedy", "comedy"),
        Pair("Crime", "crime"),
        Pair("Disney", "disney"),
        Pair("Drama", "drama"),
        Pair("Family", "family"),
        Pair("Fantasy", "fantasy"),
        Pair("History", "fistory"),
        Pair("Horror", "horror"),
        Pair("Kids", "kids"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Reality", "reality"),
        Pair("Romance", "romance"),
        Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
        Pair("Science Fiction", "science-fiction"),
        Pair("Thriller", "thriller"),
        Pair("War", "war"),
        Pair("War & Politics", "war-politics"),
        Pair("Western", "western"),
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
    }
}
