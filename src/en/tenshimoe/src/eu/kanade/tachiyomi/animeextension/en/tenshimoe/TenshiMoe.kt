package eu.kanade.tachiyomi.animeextension.en.tenshimoe

import android.annotation.SuppressLint
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
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.lang.Float.parseFloat
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.collections.ArrayList

class TenshiMoe : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "tenshi.moe"

    override val baseUrl = "https://tenshi.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client: OkHttpClient = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "ul.anime-loop.loop li a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime?s=vdy-d&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        anime.title = element.select("div span").not(".badge").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun episodeListSelector() = "ul.episode-loop li a"

    private fun episodeNextPageSelector() = popularAnimeNextPageSelector()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodes(document: Document) {
            document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
            document.select(episodeNextPageSelector()).firstOrNull()
                ?.let { addEpisodes(client.newCall(GET(it.attr("href"), headers)).execute().asJsoup()) }
        }

        addEpisodes(response.asJsoup())
        return episodes
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        val episodeNumberString = element.select("div.episode-number").text().removePrefix("Episode ")
        var numeric = true
        try {
            parseFloat(episodeNumberString)
        } catch (e: NumberFormatException) {
            numeric = false
        }
        episode.episode_number = if (numeric) episodeNumberString.toFloat() else element.parent().className().removePrefix("episode").toFloat()
        episode.name = element.select("div.episode-number").text() + ": " + element.select("div.episode-label").text() + element.select("div.episode-title").text()
        val date: String = element.select("div.date").text()
        val parsedDate = parseDate(date)
        if (parsedDate.time != -1L) episode.date_upload = parsedDate.time
        return episode
    }

    @SuppressLint("SimpleDateFormat")
    private fun parseDate(date: String): Date {
        val knownPatterns: MutableList<SimpleDateFormat> = ArrayList()
        knownPatterns.add(SimpleDateFormat("dd'th of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'nd of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'st of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'rd of 'MMM, yyyy"))

        for (pattern in knownPatterns) {
            try {
                // Take a try
                return Date(pattern.parse(date)!!.time)
            } catch (e: Throwable) {
                // Loop on
            }
        }
        return Date(-1L)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("src")
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "script:containsData(source)"

    private fun videosFromElement(element: Element): List<Video> {
        val data = element.data().substringAfter("sources: [").substringBefore("],")
        val sources = data.split("src: '").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringBefore("'")
            val size = source.substringAfter("size: ").substringBefore(",")
            val cookie = ddgInterceptor.getNewCookie(src.toHttpUrl())?.value ?: ""
            val video = Video(
                src,
                size + "p",
                src,
                headers = Headers.headersOf("cookie", "__ddg2_=$cookie"),
            )
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
        anime.setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        anime.title = element.select("div span.thumb-title, div span.text-primary").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "ul.anime-loop.loop li a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/anime?q=$query")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.cover-image.img-thumbnail").first().attr("src")
        anime.title = document.select("li.breadcrumb-item.active").text()
        anime.genre = document.select("li.genre span.value").joinToString(", ") { it.text() }
        anime.description = document.select("div.card-body").text()
        anime.author = document.select("li.production span.value").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("li.status span.value").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
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
