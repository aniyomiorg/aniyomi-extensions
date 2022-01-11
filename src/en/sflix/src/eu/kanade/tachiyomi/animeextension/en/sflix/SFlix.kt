package eu.kanade.tachiyomi.animeextension.en.sflix

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

class SFlix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Sflix"

    override val baseUrl = "https://sflix.to"

    // https://streamrapid.ru/embed
    // streamrapid.ru/-4/viSila1P4blj?z=

    // https://sflix.to/ajax/movie/episodes/76255
    // https://sflix.to/ajax/get_link/8004253
    // https://streamrapid.ru/ajax/embed-5/getSources?id=coTXci0tOHn5&_token=03AGdBq27w4Pg0bLeCc2uP2wBJLY0C7FVup4jefr042H44vVTd_4tO6DfCKOkgzAJazBWMz1jUeIy9stay02C4v9jmQm_tZ1xxTmDBPbLwnjcMoFy0JsMfBRFwU4s02ibSCRh-Wrsc2xyV23MU2v8BBFhGRtiQycZTRM7frIBmhNxozZgswYhDqYqQadsKHa2Tzd5w_RpmEiRFtTy5w3Ex1-2IgfS6ffk5X6h0DuOQVlquuTv72tYjGLwkJgAqMmBOfbaij1r922s6-I72OiEWCeKouTQhpMU_4EvOM3jheXTcwbbSaqdJEh23VTSPQsly8UNGHDzk1vbwL0m7TICzh71gAsDKuDx0I7qMOP7jd8HVqENC70UQpWr1FNczqCM7EpYG8U2EneBNFW2bz-vtr6hLK9SPr7G-bxTbCbQGezsycVZ_EMGg6mAX9DJqlgp9Nm4kAKCo3aqIexo_1PqfAsiHvgmLTInvUw&_number=4

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val domain = "co=aHR0cHM6Ly9zdHJlYW1yYXBpZC5ydTo0NDM."

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
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

    override fun episodeListSelector() = "h2.heading-name a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        // val dataId = element.attr("href").substringAfterLast("-")
        // iframe1 = client.newCall(GET("https://sflix.to/ajax/movie/episodes/" + dataId)).execute().asJsoup()

        episode.setUrlWithoutDomain(element.ownerDocument().select("div.button-shares div.addthis_inline_share_toolbox").attr("data-url")) // + element.ownerDocument().select("li a.btn-play").attr("data-id"))
        Log.i("lol", element.ownerDocument().select("div.button-shares div.addthis_inline_share_toolbox").attr("data-url")) // + element.ownerDocument().select("li a.btn-play").attr("data-id"))
        episode.name = element.text()
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        // Log.i("lol0", "$document")
        Log.i("lol1", document.select("div.detail_page-watch").attr("data-id"))
        val getApi = client.newCall(GET("https://sflix.to/ajax/movie/episodes/" + document.select("div.detail_page-watch").attr("data-id"))).execute().asJsoup()
        Log.i("lol0", "$getApi")
        // document.select("div.button-shares div.addthis_inline_share_toolbox").attr("data-url").substringAfterLast("-")

        val getVidNum = getApi.select("a").attr("data-id")
        Log.i("lol2", "$getVidNum")
        val getVidApi = client.newCall(GET("https://sflix.to/ajax/get_link/" + getVidNum)).execute().asJsoup()

        val getVideoEmbed = getVidApi.text().substringAfter("link\":\"").substringBefore("\"")
        val videoID = getVideoEmbed.substringAfterLast("/").substringBefore("?")
        Log.i("lol3", "$getVideoEmbed")
        val getVidKink = client.newCall(GET(getVideoEmbed, refererHeaders)).execute().asJsoup()
        Log.i("lol4", "$getVidKink")
        val getVidKinktext = getVidKink.body().toString()
        Log.i("tess", getVidKinktext)
        val recapchaNum = getVidKinktext.substringAfter("recaptchaSiteKey = '").substringBefore("'")
        Log.i("loll", recapchaNum)
        // Get the reCAPTCHA _token
        val capcha1 = getVidKink.select("script").text().substringAfter("recaptchaNumber = '").substringBefore("'")
        Log.i("capch1", "$capcha1")
        val capcha2 = client.newCall(GET("https://www.google.com/recaptcha/api.js?render=" + recapchaNum)).execute().asJsoup()
        Log.i("capch2", "$capcha2")
        val getVVal = capcha2.text().substringAfter("releases/").substringBefore("/")
        Log.i("capch3", "$getVVal")
        val getTokenUrl = client.newCall(GET("https://www.google.com/recaptcha/api2/anchor?ar=1&k=" + recapchaNum + "&" + domain + "&hl=en&v=" + getVVal + "&size=invisible", refererHeaders)).execute().asJsoup()
        Log.i("capch4", "$getTokenUrl")
        Log.i("teee", "https://www.google.com/recaptcha/api2/anchor?ar=1&k=" + recapchaNum + "&" + domain + "&hl=en&v=" + getVVal + "&size=invisible")

        val tOOOKEN = getTokenUrl.select("input#recaptcha-token").attr("value")
        Log.i("capch5", "$tOOOKEN")

        val getM3U8api = client.newCall(GET("https://streamrapid.ru/ajax/embed-5/getSources?id=" + videoID + "&_token=" + tOOOKEN + "&_number=" + capcha1)).execute().asJsoup()
        Log.i("m3u81", "https://streamrapid.ru/ajax/embed-5/getSources?id=" + videoID + "&_token=" + tOOOKEN + "&_number=" + capcha1)
        Log.i("m3u8", "$getM3U8api")
        val iframe = document.select("iframe#iframe-embed").attr("src")
        Log.i("iframe0", document.select("iframe#iframe-embed").attr("src"))
        // val referer = response.request.url.toString()
        // val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "script"

    private fun videosFromElement(element: Element): List<Video> {
        val data = element.data().substringAfter("{").substringBefore("}")
        Log.i("paggggge", element.data().substringAfter("{").substringBefore("}"))
        val sources = data.split("src: '").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringBefore("'")
            val size = source.substringAfter("size: ").substringBefore(",")
            val video = Video(src, size + "p", src, null)
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

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.title = element.select("a").attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[title=next]"

    override fun searchAnimeSelector(): String = "div.film_list-wrap div.flw-item div.film-poster"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/$query")

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
