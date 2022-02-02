package eu.kanade.tachiyomi.animeextension.en.dramacool

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class DramaCool : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DramaCool"

    override val baseUrl = "https://dramacool.rs"

    override val lang = "en"

    override val supportsLatest = false

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "ul.list-episode-item li a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-popular-drama?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("abs:href"))
        anime.thumbnail_url = element.select("img").attr("data-original").replace(" ", "%20")
        anime.title = element.select("h3").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.next a"

    // Episodes

    override fun episodeListSelector() = "ul.all-episode li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.select("span.type").text() + " Episode: " + element.select("h3").text().substringAfter("Episode ")
        episode.episode_number = element.select("h3").text().substringAfter("Episode ").toFloat()
        // episode.date_upload = element.select("div.meta span.date").text()
        return episode
    }

    // Video urls

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val iframe = "https:" + document.select("iframe").attr("src")
        return GET(iframe)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "ul.list-server-items li[data-video*=https://sbplay2.com], ul.list-server-items li[data-video*=https://dood], ul.list-server-items li[data-video*=https://streamtape], ul.list-server-items li[data-video*=https://fembed]"
    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val url = element.attr("data-video")
            Log.i("lol", url)
            val location = element.ownerDocument().location()
            val videoHeaders = Headers.headersOf("Referer", location)
            when {
                url.contains("https://sbplay") -> {
                    val vidURL = url.substringBefore("?").replace("/e/", "/d/")
                    val videos = sbplayUrlParse(vidURL, location)
                    videoList.addAll(videos)
                }
                url.contains("https://dood") -> {
                    val newQuality = "Doodstream mirror"
                    val video = Video(url, newQuality, doodUrlParse(url), null, videoHeaders)
                    videoList.add(video)
                }
                url.contains("https://streamtape") -> {
                    val newQuality = "StreamTape mirror"
                    val video = Video(url, newQuality, streamTapeParse(url), null, videoHeaders)
                    videoList.add(video)
                }
                url.contains("fembed") -> {
                    val apiCall = client.newCall(POST(url.substringBefore("#").replace("/v/", "/api/source/"))).execute().body!!.string()
                    Log.i("lol", "$apiCall")
                    val data = apiCall.substringAfter("\"data\":[").substringBefore("],")
                    val sources = data.split("\"file\":\"").drop(1)
                    val videoList = mutableListOf<Video>()
                    for (source in sources) {
                        val src = source.substringAfter("\"file\":\"").substringBefore("\"").replace("\\/", "/")
                        Log.i("lol", "$src")
                        val quality = source.substringAfter("\"label\":\"").substringBefore("\"")
                        val video = Video(url, quality, src, null)
                        videoList.add(video)
                    }
                    return videoList
                }
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    private fun sbplayUrlParse(vidURL: String, referer: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val refererHeader = Headers.headersOf("Referer", referer)
        val id = Uri.parse(vidURL).pathSegments[1]
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val respDownloadLinkSelector = noRedirectClient.newCall(GET(vidURL, refererHeader)).execute()
        val documentDownloadSelector = respDownloadLinkSelector.asJsoup()
        val downloadElements = documentDownloadSelector.select("div.contentbox table tbody tr td a")
        for (downloadElement in downloadElements) {
            val videoData = downloadElement.attr("onclick")
            val quality = downloadElement.text()
            val hash = videoData.splitToSequence(",").last().replace("\'", "").replace(")", "")
            val mode =
                videoData.splitToSequence(",").elementAt(1).replace("\'", "").replace(")", "")
            val downloadLink =
                "https://sbplay2.com/dl?op=download_orig&id=$id&mode=$mode&hash=$hash"
            Log.i("lol", downloadLink)
            respDownloadLinkSelector.close()
            val video = sbplayVideoParser(downloadLink, quality)
            if (video != null) videoList.add(video)
        }
        return videoList
    }

    private fun sbplayVideoParser(vidURL: String, quality: String): Video? {
        return try {
            val noRedirectClient = client.newBuilder().followRedirects(false).build()
            val refererHeader = Headers.headersOf("Referer", vidURL)
            val respDownloadLink = noRedirectClient.newCall(GET(vidURL, refererHeader)).execute()
            val documentDownloadLink = respDownloadLink.asJsoup()
            Log.i("lol1", "$documentDownloadLink")
            val downloadLink = documentDownloadLink.selectFirst("div.contentbox span a").attr("href")
            respDownloadLink.close()
            Video(vidURL, quality, downloadLink, null, refererHeader)
        } catch (e: Exception) {
            null
        }
    }

    private fun doodUrlParse(url: String): String? {
        val response = client.newCall(GET(url.replace("/d/", "/e/"))).execute()
        val content = response.body!!.string()
        if (!content.contains("'/pass_md5/")) return null
        val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
        val token = md5.substringAfterLast("/")
        val doodTld = url.substringAfter("https://dood.").substringBefore("/")
        val randomString = getRandomString()
        val expiry = System.currentTimeMillis()
        val videoUrlStart = client.newCall(
            GET(
                "https://dood.$doodTld/pass_md5/$md5",
                Headers.headersOf("referer", url)
            )
        ).execute().body!!.string()
        Log.i("lol", "$videoUrlStart$randomString?token=$token&expiry=$expiry")
        return "$videoUrlStart$randomString?token=$token&expiry=$expiry"
    }

    private fun getRandomString(length: Int = 10): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun streamTapeParse(url: String): String? {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.select("script:containsData(document.getElementById('robotlink'))")
            .firstOrNull()?.data()?.substringAfter("document.getElementById('robotlink').innerHTML = '")
            ?: return null
        val videoUrl = "https:" + script.substringBefore("'") +
            script.substringAfter("+ ('xcd").substringBefore("'")
        return "$videoUrl"
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

    // search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("abs:href"))
        anime.thumbnail_url = element.select("img").attr("data-original").replace(" ", "%20")
        anime.title = element.select("h3").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "li.next a"

    override fun searchAnimeSelector(): String = "ul.list-episode-item li a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?keyword=$query&page=$page")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.img img").attr("alt")
        anime.thumbnail_url = document.select("div.img img").attr("src")
        anime.description = document.select("div.info p").text()
        anime.genre = document.select("div.info p:contains(Genre) a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("div.info p:contains(Status) a").text())
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

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

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
}
