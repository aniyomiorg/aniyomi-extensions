package eu.kanade.tachiyomi.animeextension.pt.animeyabu

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
class Animeyabu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animeyabu"

    override val baseUrl = "https://animeyabu.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.loop-content.phpvibe-video-list.miau div.video"

    override fun popularAnimeRequest(page: Int): Request = GET("https://animeyabu.com/?s=")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.video-data h4.video-title a").attr("href")
        )
        anime.title = element.select("div.video-data h4.video-title a").text()
        anime.thumbnail_url = element.select("div.video-thumb a.clip-link span.clip img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a" // https://www.youtube.com/watch?v=tas0O586t80

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val animeId = response.request.url.toString().replace("https://hentaila.com/hentai-", "").toLowerCase()
        Log.i("bruh", "AnimeID: $animeId")
        val jsoup = response.asJsoup()

        jsoup.select("div#channel-content.main-holder.pad-holder.col-md-12.top10.nomargin div.loop-content.phpvibe-video-list.miau div.video").forEachIndexed { index, it ->
            val epNum = index
            val episode = SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                date_upload = System.currentTimeMillis()
            }
            episode.setUrlWithoutDomain(it.select("div.video-data h4 a").attr("href"))
            episodes.add(episode)
        }

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        Log.i("bruh", "${response.request.url}")
        document.select("script").forEach { it ->
            if (it.data().contains("{type: \"video/mp4\"")) {
                val url = it.data().substringAfter("file: \"").substringBefore("\"")
                videoList.add(Video(url, "Video", url, null))
                val urlHd = it.data().substringAfter("\"HD\",file: \"").substringBefore("\"")
                videoList.add(Video(url, "HD", url, null))
                val urlSd = it.data().substringAfter("\"SD\",file: \"").substringBefore("\"")
                videoList.add(Video(url, "SD", url, null))
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Arc")
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request { return GET("https://animeyabu.com/?s=$query") }

    override fun searchAnimeFromElement(element: Element): SAnime { return popularAnimeFromElement(element) }

    override fun searchAnimeNextPageSelector(): String = "poto"

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.anime-single-list div.anime-info div.anime-cover img").attr("src")
        anime.title = document.selectFirst("div.anime-single-list div.anime-info div.anime-title h1").text()
        anime.description = document.select("div.anime-single-list div.anime-synopsis p").text()
        anime.genre = document.select("div.anime-single-list div.anime-info div.anime-genres").text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Video", "HD", "SD")
            entryValues = arrayOf("Video", "HD", "SD")
            setDefaultValue("Video")
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
