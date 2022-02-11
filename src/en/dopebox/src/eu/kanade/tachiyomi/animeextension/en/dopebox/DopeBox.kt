package eu.kanade.tachiyomi.animeextension.en.dopebox

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class DopeBox : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DopeBox (experimental)"

    override val baseUrl = "https://dopebox.to"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val domain = "aHR0cHM6Ly9yYWJiaXRzdHJlYW0ubmV0OjQ0Mw.."

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://dopebox.to/")
    }

    override fun popularAnimeSelector(): String = "div.film_list-wrap div.flw-item div.film-poster"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movie?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.title = element.select("a").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[title=next]"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val infoElement = document.select("div.detail_page-watch")
        val id = infoElement.attr("data-id")
        val dataType = infoElement.attr("data-type") // Tv = 2 or movie = 1
        if (dataType == "2") {
            val seasonUrl = "https://dopebox.to/ajax/v2/tv/seasons/$id"
            val seasonsHtml = client.newCall(
                GET(
                    seasonUrl,
                    headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().asJsoup()
            val seasonsElements = seasonsHtml.select("a.dropdown-item.ss-item")
            seasonsElements.forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            val movieUrl = "https://dopebox.to/ajax/movie/episodes/$id"
            val episode = SEpisode.create()
            episode.name = document.select("h2.heading-name").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(movieUrl)
            episodeList.add(episode)
        }
        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("data-id")
        val seasonName = element.text()
        val episodesUrl = "https://dopebox.to/ajax/v2/season/episodes/$seasonId"
        val episodesHtml = client.newCall(
            GET(
                episodesUrl,
            )
        ).execute().asJsoup()
        val episodeElements = episodesHtml.select("div.eps-item")
        return episodeElements.map { episodeFromElement(it, seasonName) }
    }

    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        val episodeId = element.attr("data-id")
        val episode = SEpisode.create()
        val epNum = element.select("div.episode-number").text()
        val epName = element.select("h3.film-name a").text()
        episode.name = "$seasonName $epNum $epName"
        episode.setUrlWithoutDomain("https://dopebox.to/ajax/v2/episode/servers/$episodeId")
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        // referers
        val referer1 = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer1)
        val referer = response.request.url.encodedPath
        val newHeaders = Headers.headersOf("referer", referer)

        // get embed id
        val getVidID = document.selectFirst("a").attr("data-id")
        Log.i("lol2", "$getVidID")
        val getVidApi = client.newCall(GET("https://dopebox.to/ajax/get_link/" + getVidID)).execute().asJsoup()

        // streamrapid URL
        val getVideoEmbed = getVidApi.text().substringAfter("link\":\"").substringBefore("\"")
        Log.i("lol3", "$getVideoEmbed")
        val videoEmbedUrlId = getVideoEmbed.substringAfterLast("/").substringBefore("?")
        Log.i("videoEmbedId", "$videoEmbedUrlId")
        val callVideolink = client.newCall(GET(getVideoEmbed, refererHeaders)).execute().asJsoup()
        Log.i("lol4", "$callVideolink")
        val callVideolink2 = client.newCall(GET(getVideoEmbed, refererHeaders)).execute().body!!.string()
        // get Token vals
        val getRecaptchaRenderLink = callVideolink.select("script[src*=https://www.google.com/recaptcha/api.js?render=]").attr("src")
        Log.i("lol5", getRecaptchaRenderLink)
        val getRecaptchaRenderNum = callVideolink2.substringAfter("recaptchaNumber = '").substringBeforeLast("'")
        Log.i("recapchaNum", "$getRecaptchaRenderNum")
        val callReacapchaRenderLink = client.newCall(GET(getRecaptchaRenderLink)).execute().asJsoup()
        Log.i("lol6", "$callReacapchaRenderLink")
        val getAnchorVVal = callReacapchaRenderLink.text().substringAfter("releases/").substringBefore("/")
        val getRecaptchaSiteKey = callVideolink.select("script[src*=https://www.google.com/recaptcha/api.js?render=]").attr("src").substringAfterLast("=")
        Log.i("lol7", getRecaptchaSiteKey)
        val anchorLink = "https://www.google.com/recaptcha/api2/anchor?ar=1&k=$getRecaptchaSiteKey&co=$domain&hl=en&v=$getAnchorVVal&size=invisible&cb=123456789"
        Log.i("anchorLik", "$anchorLink")
        val callAnchor = client.newCall(GET(anchorLink, newHeaders)).execute().asJsoup()
        Log.i("lolll", "$callAnchor")
        val rtoken = callAnchor.select("input#recaptcha-token").attr("value")
        Log.i("Retoken", rtoken)

        val pageData = FormBody.Builder()
            .add("v", "$getAnchorVVal")
            .add("reason", "q")
            .add("k", "$getRecaptchaSiteKey")
            .add("c", "$rtoken")
            .add("sa", "")
            .add("co", "$domain")
            .build()

        val reloadTokenUrl = "https://www.google.com/recaptcha/api2/reload?k=$getRecaptchaSiteKey"
        Log.i("loll", reloadTokenUrl)
        val reloadHeaders = headers.newBuilder()
            .set("Referer2", "$anchorLink")
            .build()
        val callreloadToken = client.newCall(POST(reloadTokenUrl, reloadHeaders, pageData)).execute().asJsoup()
        Log.i("lol9", "$callreloadToken")
        val get1Token = callreloadToken.text().substringAfter("rresp\",\"").substringBefore("\"")
        Log.i("lol10", get1Token)
        Log.i("m3u8fi", "https://rabbitstream.net/ajax/embed-4/getSources?id=$videoEmbedUrlId&_token=$get1Token&_number=$getRecaptchaRenderNum")
        val iframeResponse = client.newCall(GET("https://rabbitstream.net/ajax/embed-5/getSources?id=$videoEmbedUrlId&_token=$get1Token&_number=$getRecaptchaRenderNum", newHeaders))
            .execute().asJsoup()
        Log.i("iframere", "$iframeResponse")

        return videosFromElement(iframeResponse)
    }

    private fun videosFromElement(element: Element): List<Video> {
        val test = element.text()
        val masterUrl = element.text().substringAfter("file\":\"").substringBefore("\",\"type")
        if (test.contains("playlist.m3u8")) {
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                videoList.add(Video(videoUrl, quality, videoUrl, null))
            }
            return videoList
        } else if (test.contains("index.m3u8")) {
            return listOf(Video(masterUrl, "Default", masterUrl, null))
        } else {
            throw Exception("never give up and try again :)")
        }
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

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.title = element.select("a").attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[title=next]"

    override fun searchAnimeSelector(): String = "div.film_list-wrap div.flw-item div.film-poster"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/$query?page=$page".replace(" ", "-")
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is GenreList -> {
                        if (filter.state > 0) {
                            val GenreN = getGenreList()[filter.state].query
                            val genreUrl = "$baseUrl/genre/$GenreN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(genreUrl.toString(), headers)
                        }
                    }
                }
            }
            throw Exception("Choose Filter")
        }
        return GET(url, headers)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.film-poster-img").attr("src")
        anime.title = document.select("img.film-poster-img").attr("title")
        anime.genre = document.select("div.row-line:contains(Genre) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.detail_page-watch div.description").text().replace("Overview:", "")
        anime.author = document.select("div.row-line:contains(Production) a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("li.status span.value").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.COMPLETED
        }
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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

    // Filter

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Ignored If Using Text Search"),
        GenreList(genresName),
    )

    private class GenreList(genres: Array<String>) : AnimeFilter.Select<String>("Genre", genres)
    private data class Genre(val name: String, val query: String)
    private val genresName = getGenreList().map {
        it.name
    }.toTypedArray()

    private fun getGenreList() = listOf(
        Genre("CHOOSE", ""),
        Genre("Action", "action"),
        Genre("Action & Adventure", "action-adventure"),
        Genre("Adventure", "adventure"),
        Genre("Animation", "animation"),
        Genre("Biography", "biography"),
        Genre("Comedy", "comedy"),
        Genre("Crime", "crime"),
        Genre("Documentary", "documentary"),
        Genre("Drama", "drama"),
        Genre("Family", "family"),
        Genre("Fantasy", "fantasy"),
        Genre("History", "history"),
        Genre("Horror", "horror"),
        Genre("Kids", "kids"),
        Genre("Music", "music"),
        Genre("Mystery", "mystery"),
        Genre("News", "news"),
        Genre("Reality", "reality"),
        Genre("Romance", "romance"),
        Genre("Sci-Fi & Fantasy", "sci-fi-fantasy"),
        Genre("Science Fiction", "science-fiction"),
        Genre("Soap", "soap"),
        Genre("Talk", "talk"),
        Genre("Thriller", "thriller"),
        Genre("TV Movie", "tv-movie"),
        Genre("War", "war"),
        Genre("War & Politics", "war-politics"),
        Genre("Western", "western")
    )
}
