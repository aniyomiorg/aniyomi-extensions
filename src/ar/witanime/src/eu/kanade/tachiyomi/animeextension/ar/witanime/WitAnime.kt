package eu.kanade.tachiyomi.animeextension.ar.witanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SoraPlayExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class WitAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "WIT ANIME"

    override val baseUrl = "https://witanime.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ================================== popular ==================================

    override fun popularAnimeSelector(): String = "div.anime-list-content div:nth-child(1) div.col-lg-2 div.anime-card-container"

    override fun popularAnimeNextPageSelector(): String = "ul.pagination a.next"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/قائمة-الانمي/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").first().attr("abs:src")
        return anime
    }

    // ================================== episodes ==================================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.attr("href"))
            episode.name = element.text()
            return episode
        }
        fun addEpisodes(document: Document) {
            /*if (document.select(episodeListSelector()).isNullOrEmpty())
                document.select("div.all-episodes ul.all-episodes-list li a").forEach { episodes.add(episodeExtract(it)) }
            else*/
            document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
        }
        addEpisodes(response.asJsoup())
        return episodes.reversed()
    }

    override fun episodeListSelector() = "div.all-episodes ul.all-episodes-list li, div.ehover6 > div.episodes-card-title > h3"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.name = element.select("a").text()
        val episodeNumberString = element.select("a").text().removePrefix("الحلقة ").removePrefix("الخاصة ").removePrefix("الأونا ").removePrefix("الفلم ").removePrefix("الأوفا ")
        episode.episode_number = episodeNumberString.toFloat()
        return episode
    }

    // ================================== video urls ==================================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("ul#episode-servers li").forEach { it ->
            val server = it.select("a").text()
            val url = it.select("a").attr("data-ep-url")
            when {
                server.contains("fembed") || server.contains("yuistream") || server.contains("vivyplay") -> {
                    val videos = FembedExtractor(client).videosFromUrl(url)
                    videoList.addAll(videos)
                }
                url.contains("soraplay") -> {
                    val witAnime = "https://witanime.com/"
                    val newHeaders = headers.newBuilder()
                        .set("referer", witAnime)
                        .build()
                    val videos = SoraPlayExtractor(client).videosFromUrl(url, newHeaders)
                    videoList.addAll(videos)
                }
                url.contains("yonaplay") -> {
                    val newHeaders = headers.newBuilder().set("referer", "https://witanime.com/").build()
                    val videos = client.newCall(GET(url, newHeaders)).execute().asJsoup()
                    videos.select("div.OD li").forEach {
                        val videoUrl = it.attr("onclick").substringAfter("go_to_player('").substringBefore("')")
                        when {
                            videoUrl.contains("soraplay") -> {
                                val video = SoraPlayExtractor(client).videosFromUrl(videoUrl, newHeaders)
                                videoList.addAll(video)
                            }
                            videoUrl.contains("dropbox") -> {
                                videoList.add(Video(videoUrl, "Dropbox mirror", videoUrl))
                            }
                            videoUrl.contains("4shared") -> {
                                val video = SharedExtractor(client).videosFromUrl(videoUrl, it.select("p").text().take(3).trim())
                                if (video != null) videoList.add(video)
                            }
                            /*videoUrl.contains("drive") -> {
                                val video = GdriveExtractor(client).getVideoList(videoUrl)
                                videoList.addAll(video)
                            }*/
                        }
                    }
                }
                url.contains("dood") -> {
                    val video = DoodExtractor(client).videoFromUrl(url, "Dood mirror")
                    if (video != null)
                        videoList.add(video)
                }
                url.contains("4shared") -> {
                    val video = SharedExtractor(client).videosFromUrl(url)
                    if (video != null) videoList.add(video)
                }
                url.contains("sbanh") -> {
                    val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
                    videoList.addAll(videos)
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ================================== search ==================================

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").first().attr("abs:src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination a.next"

    override fun searchAnimeSelector(): String = "div.anime-list-content div:nth-child(1) div.col-lg-2 div.anime-card-container"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/?search_param=animes&s=$query")

    // ================================== details ==================================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = if (!document.select("div.anime-page-link").isNullOrEmpty())
            client.newCall(GET(document.select("div.anime-page-link a").attr("href"), headers)).execute().asJsoup()
        else
            document
        anime.thumbnail_url = doc.select("img.thumbnail").first().attr("src")
        anime.title = doc.select("h1.anime-details-title").text()
        anime.genre = doc.select("ul.anime-genres > li > a, div.anime-info > a").joinToString(", ") { it.text() }
        anime.description = doc.select("p.anime-story").text()
        doc.select("div.anime-info a").text()?.also { statusText ->
            when {
                statusText.contains("يعرض الان", true) -> anime.status = SAnime.ONGOING
                statusText.contains("مكتمل", true) -> anime.status = SAnime.COMPLETED
                else -> anime.status = SAnime.UNKNOWN
            }
        }

        return anime
    }

    // ================================== latest ==================================

    override fun latestUpdatesSelector(): String = "div.anime-list-content div:nth-child(1) div.col-lg-2 div.anime-card-container"

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episode/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").first().attr("abs:src")
        return anime
    }

    // ================================== preferences ==================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360p", "240")
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
