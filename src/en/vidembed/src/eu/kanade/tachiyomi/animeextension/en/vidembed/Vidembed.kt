package eu.kanade.tachiyomi.animeextension.en.vidembed

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.vidembed.extractors.StreamSBExtractor
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

class Vidembed : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Vidembed"

    override val baseUrl = "https://vidembed.io"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val downloadLink = "https://vidembed.io/download?id="

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = ".video-block a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.select(".name").first().text().split("Episode").first()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:last-child:not(.selected)"

    override fun episodeListSelector() = "ul.listing.items.lists li a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.attr("href"))
        val epName = element.selectFirst("div.name").ownText()
        val ep = epName.substringAfter("Episode ")
        val epNo = try {
            ep.substringBefore(" ").toFloat()
        } catch (e: NumberFormatException) {
            0.toFloat()
        }
        episode.episode_number = epNo
        episode.name = if (ep == epName) epName else "Episode $ep"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val link = document.selectFirst(".play-video iframe").attr("src")
        val id = Uri.parse(link).getQueryParameter("id").toString()
        return GET(downloadLink + id)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val quality = element.text().substringAfter("Download (").replace("P - mp4)", "p")
            val url = element.attr("href")
            val location = element.ownerDocument().location()
            val videoHeaders = Headers.headersOf("Referer", location)
            when {
                url.contains("https://dood") -> {
                    val newQuality = "Doodstream mirror"
                    val video = try {
                        Video(url, newQuality, doodUrlParse(url), null, videoHeaders)
                    } catch (e: Exception) {
                        null
                    }
                    if (video != null) videoList.add(video)
                }
                url.contains("https://sbplay") -> {
                    val newUrl = url.replace("/d/", "/e/")
                    val headers = headers.newBuilder()
                        .set("watchsb", "streamsb")
                        .build()
                    val videos = StreamSBExtractor(client).videosFromUrl(newUrl, headers)
                    videoList.addAll(videos)
                }
                else -> {
                    val parsedQuality = when (quality) {
                        "FullHDp" -> "1080p"
                        "HDp" -> "720p"
                        "SDp" -> "360p"
                        else -> quality
                    }
                    val video =
                        Video(url, parsedQuality, videoUrlParse(url, location), null, videoHeaders)
                    videoList.add(video)
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = "div.mirror_link a[download], div.mirror_link a[href*=https://dood],div.mirror_link a[href*=https://sbplay]"

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    private fun videoUrlParse(url: String, referer: String): String {
        val refererHeader = Headers.headersOf("Referer", referer)
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val response = noRedirectClient.newCall(GET(url, refererHeader)).execute()
        val videoUrl = response.header("location")
        response.close()
        return videoUrl ?: url
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

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.select(".name").first().text().split("Episode").first()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun searchAnimeSelector(): String = ".video-block a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> GET("$baseUrl/search.html?keyword=$query&page=$page", headers)
            else -> GET("$baseUrl/?page=$page")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select(".video-details span").text()
        anime.description = document.select(".video-details .post-entry .content-more-js").text()

        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

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
