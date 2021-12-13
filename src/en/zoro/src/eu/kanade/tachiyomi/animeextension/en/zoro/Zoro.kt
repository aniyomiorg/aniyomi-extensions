package eu.kanade.tachiyomi.animeextension.en.zoro

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Zoro : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "zoro.to (experimental)"

    override val baseUrl = "https://zoro.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.flw-item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-popular?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("div.film-poster > img").attr("data-src")
        anime.setUrlWithoutDomain(baseUrl + element.select("div.film-detail a").attr("href"))
        anime.title = element.select("div.film-detail a").attr("data-jname")
        anime.description = element.select("div.film-detail div.description").firstOrNull()?.text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a[title=Next]"

    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        val referer = Headers.headersOf("Referer", baseUrl + anime.url)
        return GET("$baseUrl/ajax/v2/episode/list/$id", referer)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.body!!.string().substringAfter("\"html\":\"").substringBefore("<script>")
        val unescapedData = JSONUtil.unescape(data)
        val episodeList = mutableListOf<SEpisode>()
        val document = Jsoup.parse(unescapedData)
        val aList = document.select("a.ep-item")
        for (a in aList) {
            val episode = SEpisode.create()
            episode.episode_number = a.attr("data-number").toFloat()
            episode.name = "Episode ${a.attr("data-number")}: ${a.attr("title")}"
            episode.url = a.attr("href")
            episode.date_upload = System.currentTimeMillis()
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListRequest(episode: SEpisode): Request {
        val id = episode.url.substringAfterLast("?ep=")
        val referer = Headers.headersOf("Referer", baseUrl + episode.url)
        return GET("$baseUrl/ajax/v2/episode/servers?episodeId=$id", referer)
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body!!.string()
        val episodeReferer = Headers.headersOf("Referer", response.request.header("referer")!!)
        val data = body.substringAfter("\"html\":\"").substringBefore("<script>")
        val unescapedData = JSONUtil.unescape(data)
        val serversHtml = Jsoup.parse(unescapedData)
        val videoList = mutableListOf<Video>()
        serversHtml.select("div.server-item").forEach {
            val id = it.attr("data-id")
            val subDub = it.attr("data-type")
            val videos = getVideosFromServer(
                client.newCall(GET("$baseUrl/ajax/v2/episode/sources?id=$id", episodeReferer)).execute(),
                subDub
            )
            if (videos != null) videoList.addAll(videos)
        }
        return videoList
    }

    private fun getVideosFromServer(response: Response, subDub: String): List<Video>? {
        val body = response.body!!.string()
        val url = body.substringAfter("\"link\":\"").substringBefore("\"").toHttpUrl()
        val id = url.pathSegments.last()
        val getSources = url.toString().substringBefore("embed-6") + "ajax/embed-6/getSources?id=$id&_token="

        val referer = Headers.headersOf("Referer", baseUrl)
        val recaptchaClient = client.newBuilder().addInterceptor(GetSourcesInterceptor(getSources, client)).build()

        val lol = recaptchaClient.newCall(GET("$url&autoPlay=1", referer)).execute().body!!.string()
        if (!lol.contains("{\"sources\":[{\"file\":\"")) return null
        val masterUrl = lol.substringAfter("{\"sources\":[{\"file\":\"").substringBefore("\"")
        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
        val videoList = mutableListOf<Video>()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p - $subDub"
            val videoUrl = masterUrl.substringBeforeLast("/") + "/" + it.substringAfter("\n").substringBefore("\n")
            videoList.add(Video(videoUrl, quality, videoUrl, null))
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/search?keyword=$query&page=$page")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.anisc-detail h2").attr("data-jname")
        anime.description = document.select("div.anisc-info > div.item-title > div.text").text()
        anime.author = document.select("div.item-title:contains(Studios:) a").text()
        anime.status = parseStatus(document.select("div.item-title:contains(Status:) span.name").text())
        anime.genre = document.select("div.item-title:contains(Genres:) a").joinToString { it.text() }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/top-airing")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("sub", "dub")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue("dub")
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
