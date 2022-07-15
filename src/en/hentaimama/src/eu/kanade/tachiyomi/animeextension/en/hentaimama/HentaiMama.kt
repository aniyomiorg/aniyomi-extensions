package eu.kanade.tachiyomi.animeextension.en.hentaimama

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
import java.text.SimpleDateFormat

class HentaiMama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.tvshows"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/advance-search/page/$page/?submit=Submit&filter=weekly")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination-wraper div.resppages a"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.series div.items article"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.select("div.season_m a span.c").text().removePrefix("Episode ")
        val date = SimpleDateFormat("MMM. dd, yyyy").parse(element.select("div.data > span").text())
        episode.setUrlWithoutDomain(element.select("div.season_m a").attr("href"))
        episode.name = element.select("div.season_m a span.c").text()
        episode.date_upload = date.time
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }

        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        // POST body data
        val body = FormBody.Builder()
            .add("action", "get_player_contents")
            .add(
                "a",
                (document.select("#post_report  input:nth-child(5)").attr("value")).toString()
            )
            .build()

        // Call POST
        val newHeaders = Headers.headersOf("referer", "$baseUrl/")

        val listOfVideos = client.newCall(
            POST("$baseUrl/wp-admin/admin-ajax.php", newHeaders, body)
        )
            .execute().asJsoup()
            .body().select("iframe")

        val embedUrl = listOfVideos.toString()
            .substringAfter("src=\"\\&quot;https:\\/\\/hentaimama.io\\/")
            .substringBefore("\\&")
            .split("p=")[1]

        // Video from Element
        val source1 = client.newCall(GET("$baseUrl/new2.php?p=$embedUrl")).execute().asJsoup()
            .body().toString()
            .substringAfterLast("file: \"").substringBeforeLast("}],")
            .replace("\"", "").replace("\n", "")

        val source2 = client.newCall(GET("$baseUrl/new3.php?p=$embedUrl")).execute().asJsoup()
            .body().select("video source").attr("src")

        val videoList = mutableListOf<Video>()
        videoList.add(Video(source1, "Mirror 1", source1, null))
        videoList.add(Video(source2, "Mirror 2", source2, null))
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

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

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.details > div.title a").attr("href"))
        anime.thumbnail_url = element.select("div.image div a img").attr("src")
        anime.title = element.select("div.details > div.title a").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "link[rel=next]"

    override fun searchAnimeSelector(): String = "article"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/page/$page/?s=${query.replace(("[\\W]").toRegex(), " ")}")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.sheader div.poster img").first().attr("data-src")
        anime.title = document.select("#info1 div:nth-child(2) span").text()
        anime.genre = document.select("div.sheader  div.data  div.sgeneros a")
            .joinToString(", ") { it.text() }
        anime.description = document.select("#info1 div.wp-content p").text()
        anime.author = document.select("#info1 div:nth-child(3) span div  div a")
            .joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("#info1 div:nth-child(6) span").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    // Latest

    override fun latestUpdatesSelector(): String = "article.tvshows"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/tvshows/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "link[rel=next]"

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred Mirror"
            entries = arrayOf("Mirror 1", "Mirror 2")
            entryValues = arrayOf("source1", "source2")
            setDefaultValue("source1")
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
