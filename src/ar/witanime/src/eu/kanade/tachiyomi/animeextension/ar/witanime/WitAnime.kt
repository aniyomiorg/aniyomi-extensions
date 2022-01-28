package eu.kanade.tachiyomi.animeextension.ar.witanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.FembedExtractor
// import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.OkruExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SoraPlayExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
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

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.anime-list-content div:nth-child(1) div.col-lg-2 div.anime-card-container"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/قائمة-الانمي/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").first().attr("abs:src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination a.next"

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.ehover6 > div.episodes-card-title > h3"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.name = element.select("a").text()
        val episodeNumberString = element.select("a").text().removePrefix("الحلقة ").removePrefix("الخاصة ").removePrefix("الأونا ").removePrefix("الفلم ").removePrefix("الأوفا ")
        episode.episode_number = episodeNumberString.toFloat()
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("ul#episode-servers li").forEach { it ->
            val server = it.select("a").text()
            val url = it.select("a").attr("data-ep-url")
            when {
                server.contains("fembed") -> {
                    val videos = FembedExtractor().videosFromUrl(url)
                    videoList.addAll(videos)
                }
                server.contains("soraplay") -> {
                    val witAnime = "https://witanime.com/"
                    val newHeaders = headers.newBuilder()
                        .set("referer", "$witAnime")
                        .build()
                    val videos = SoraPlayExtractor(client).videosFromUrl(url, newHeaders)
                    videoList.addAll(videos)
                }
                server.contains("yuistream") -> {
                    val videos = FembedExtractor().videosFromUrl(url)
                    videoList.addAll(videos)
                }
                server.contains("vivyplay") -> {
                    val videos = FembedExtractor().videosFromUrl(url)
                    videoList.addAll(videos)
                }
                /*server.contains("ok") -> {
                    val videos = OkruExtractor(client).videosFromUrl(url)
                    if (videos == null) {
                        throw Exception("Not used")
                    } else {
                        videoList.addAll(videos)
                    }
                }*/
            }

            /*if (server == "fembed") {
                val videos = FembedExtractor().videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "soraplay") {
                val witAnime = "https://witanime.com/"
                val newHeaders = headers.newBuilder()
                    .set("referer", "$witAnime")
                    .build()
                val videos = SoraPlayExtractor(client).videosFromUrl(url, newHeaders)
                videoList.addAll(videos)
            }
            if (server == "yuistream") {
                val videos = FembedExtractor().videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "ok.ru") {
                val videos = OkruExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }*/
            /*if (server == "4shared") {
                val videos = SharedExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }*/
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Fembed: 720p")
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

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.thumbnail").first().attr("src")
        anime.title = document.select("h1.anime-details-title").text()
        anime.genre = document.select("ul.anime-genres > li > a, div.anime-info > a").joinToString(", ") { it.text() }
        anime.description = document.select("p.anime-story").text()
        document.select("div.anime-info a").text()?.also { statusText ->
            when {
                statusText.contains("يعرض الان", true) -> anime.status = SAnime.ONGOING
                statusText.contains("مكتمل", true) -> anime.status = SAnime.COMPLETED
                else -> anime.status = SAnime.UNKNOWN
            }
        }

        return anime
    }

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile", "4Shared", "soraplay: 360p", "soraplay: 480p", "soraplay: 720p", "soraplay: 1080p")
            entryValues = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile", "4Shared", "soraplay: 360p", "soraplay: 480p", "soraplay: 720p", "soraplay: 1080p")
            setDefaultValue("Fembed:720p")
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
