package eu.kanade.tachiyomi.animeextension.en.dopebox

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class DopeBox : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DopeBox"

    override val baseUrl = "https://dopebox.to"

    // https://streamrapid.ru/embed
    // streamrapid.ru/-4/viSila1P4blj?z=

    // https://dopebox.to/ajax/movie/episodes/76255
    // https://dopebox.to/ajax/get_link/8004253
    // https://streamrapid.ru/ajax/embed-5/getSources?id=coTXci0tOHn5&_token=03AGdBq27w4Pg0bLeCc2uP2wBJLY0C7FVup4jefr042H44vVTd_4tO6DfCKOkgzAJazBWMz1jUeIy9stay02C4v9jmQm_tZ1xxTmDBPbLwnjcMoFy0JsMfBRFwU4s02ibSCRh-Wrsc2xyV23MU2v8BBFhGRtiQycZTRM7frIBmhNxozZgswYhDqYqQadsKHa2Tzd5w_RpmEiRFtTy5w3Ex1-2IgfS6ffk5X6h0DuOQVlquuTv72tYjGLwkJgAqMmBOfbaij1r922s6-I72OiEWCeKouTQhpMU_4EvOM3jheXTcwbbSaqdJEh23VTSPQsly8UNGHDzk1vbwL0m7TICzh71gAsDKuDx0I7qMOP7jd8HVqENC70UQpWr1FNczqCM7EpYG8U2EneBNFW2bz-vtr6hLK9SPr7G-bxTbCbQGezsycVZ_EMGg6mAX9DJqlgp9Nm4kAKCo3aqIexo_1PqfAsiHvgmLTInvUw&_number=4

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val domain = "aHR0cHM6Ly9yYWJiaXRzdHJlYW0ubmV0OjQ0Mw.."

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://dopebox.to/") // https://s12.gemzawy.com https://moshahda.net
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

        /*Log.i("lol1", document.select("div.detail_page-watch").attr("data-id"))
        val getApi = client.newCall(GET("https://dopebox.to/ajax/movie/episodes/" + document.select("div.detail_page-watch").attr("data-id"))).execute().asJsoup()
        Log.i("lol0", "$getApi")*/
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

        // Log.i("lol8", "$anchorLink")

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
            .set("Referer2", "https://www.google.com/recaptcha/api2")
            .build()
        val callreloadToken = client.newCall(POST(reloadTokenUrl, newHeaders, pageData)).execute().asJsoup()
        Log.i("lol9", "$callreloadToken")
        val get1Token = callreloadToken.text().substringAfter("rresp\",\"").substringBefore("\"")
        Log.i("lol10", get1Token)
        Log.i("m3u8fi", "https://rabbitstream.net/ajax/embed-4/getSources?id=$videoEmbedUrlId&_token=$get1Token&_number=$getRecaptchaRenderNum") // &_number=$getRecaptchaRenderNum")
        val iframeResponse = client.newCall(GET("https://rabbitstream.net/ajax/embed-5/getSources?id=$videoEmbedUrlId&_token=$get1Token&_number=$getRecaptchaRenderNum", newHeaders))
            .execute().asJsoup()
        Log.i("iframere", "$iframeResponse")

        return videosFromElement(iframeResponse)
    }

    private fun videosFromElement(element: Element): List<Video> {
        val masterUrl = element.text().substringAfter("file\":\"").substringBefore("\",\"type")
        if (masterUrl.contains("playlist.m3u8")) {
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                videoList.add(Video(videoUrl, quality, videoUrl, null))
            }
            return videoList
        } else {
            return listOf(Video(masterUrl, "Default", masterUrl, null))
        }
        // return listOf(Video(masterUrl, "Default", masterUrl, null))
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/$query?page=$page".replace(" ", "-"))

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

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        anime.title = element.select("div span").not(".badge").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime?s=rel-d&page=$page")

    override fun latestUpdatesSelector(): String = "ul.anime-loop.loop li a"

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
}
