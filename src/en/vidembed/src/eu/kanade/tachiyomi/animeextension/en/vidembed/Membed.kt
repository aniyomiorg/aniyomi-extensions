package eu.kanade.tachiyomi.animeextension.en.vidembed

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.vidembed.extractors.MixDropExtractor
import eu.kanade.tachiyomi.animeextension.en.vidembed.extractors.XstreamcdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Membed : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Membed"

    override val baseUrl = "https://membed.net"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    override val id: Long = 8093842542096095331

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val downloadLink = "https://membed.net/download?id="

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
        val elements = document.select(videoListSelector())

        val videoList = elements.parallelMap { element ->
            val serverName = element.text()
            val quality = serverName.substringAfter("Download (").replace("P - mp4)", "p")
            val url = element.attr("href").replace("/d/", "/e/").replace("/f/", "/e/")
            val location = element.ownerDocument().location()
            val videoHeaders = Headers.headersOf("Referer", location)
            runCatching {
                when {
                    serverName.contains("Xstreamcdn") -> {
                        XstreamcdnExtractor(client, json).videosFromUrl(url)
                    }
                    serverName.contains("DoodStream") -> {
                        DoodExtractor(client).videosFromUrl(url)
                    }
                    serverName.contains("StreamSB") -> {
                        StreamSBExtractor(client).videosFromUrl(url, headers)
                    }
                    serverName.contains("Mixdrop") -> {
                        MixDropExtractor(client).videoFromUrl(url)
                    }
                    else -> {
                        val parsedQuality = when (quality) {
                            "FullHDp" -> "1080p"
                            "HDp" -> "720p"
                            "SDp" -> "360p"
                            "" -> "Watch"
                            else -> quality
                        }
                        try {
                            Video(
                                url,
                                parsedQuality,
                                videoUrlParse(url, location),
                                headers = videoHeaders
                            ).let {
                                listOf(it)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.getOrNull()
        }
            .filterNotNull()
            .flatten()
        return videoList
    }

    override fun videoListSelector() = "div.mirror_link > div > a"

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

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
