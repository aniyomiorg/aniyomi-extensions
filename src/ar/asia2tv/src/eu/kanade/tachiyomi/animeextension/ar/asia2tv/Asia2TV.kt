package eu.kanade.tachiyomi.animeextension.ar.asia2tv

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Asia2TV : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Asia2TV"

    override val baseUrl = "https://asia2tv.net"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ========================== popular =======================

    override fun popularAnimeSelector(): String = "div.postmovie-photo a[title]"

    override fun popularAnimeNextPageSelector(): String = "div.nav-links a.next"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/asian-drama/page/$page/") // page/$page

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        // anime.thumbnail_url = element.select("div.image img").first().attr("data-src")
        anime.title = element.attr("title")
        return anime
    }

    // ========================== episodes =======================

    override fun episodeListSelector() = "div.loop-episode a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.attr("href").substringAfterLast("-").substringBeforeLast("/") + " : الحلقة"
        return episode
    }

    // ========================== video urls =======================

    override fun videoListSelector() = "ul.server-list-menu li"

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val link = document.selectFirst("div.loop-episode a.current").attr("href")
        return GET(link)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(videoListSelector()).forEach {
            val url = it.attr("data-server")
            if (url.contains("dood")) {
                val videosFromURL = DoodExtractor(client).videoFromUrl(url)
                if (videosFromURL != null) videoList.add(videosFromURL)
            } else if (url.contains("fembed")) {
                val videosFromURL = FembedExtractor(client).videosFromUrl(url)
                videoList.addAll(videosFromURL)
            } else if (url.contains("ok")) {
                val videosFromURL = OkruExtractor(client).videosFromUrl(url)
                videoList.addAll(videosFromURL)
            } else if (url.contains("streamtape")) {
                val videosFromURL = StreamTapeExtractor(client).videoFromUrl(url)
                if (videosFromURL != null) videoList.add(videosFromURL)
            } else if (url.contains("yodbox")) {
                val html = client.newCall(GET(url)).execute().asJsoup()
                val videoFromURL = html.select("source").attr("src")
                if (videoFromURL.isNotEmpty())
                    videoList.add(Video(videoFromURL, "Yodbox: mirror", videoFromURL))
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document): String = throw Exception("not used")

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

    // ========================== search =======================

    override fun searchAnimeSelector(): String = "div.postmovie-photo a[title]"

    override fun searchAnimeNextPageSelector(): String = "div.nav-links a.next"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        // anime.thumbnail_url = element.select("div.image img").first().attr("data-src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is TypeList -> {
                        if (filter.state > 0) {
                            val GenreN = getTypeList()[filter.state].query
                            val genreUrl = "$baseUrl/category/asian-drama/$GenreN/page/$page/".toHttpUrlOrNull()!!.newBuilder()
                            return GET(genreUrl.toString(), headers)
                        }
                    }
                    is StatusList -> {
                        if (filter.state > 0) {
                            val StatusN = getStatusList()[filter.state].query
                            val statusUrl = "$baseUrl/$StatusN/page/$page/".toHttpUrlOrNull()!!.newBuilder()
                            return GET(statusUrl.toString(), headers)
                        }
                    }
                    else -> {}
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // ========================== details =======================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1 span.title").text()
        anime.thumbnail_url = document.select("div.single-thumb-bg > img").attr("src")
        anime.description = document.select("div.getcontent p").text()
        anime.genre = document.select("div.box-tags a, li:contains(البلد) a").joinToString(", ") { it.text() }

        return anime
    }

    // ========================== latest =======================

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // ========================== filters =======================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeList(typesName),
        StatusList(statusesName),
    )

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("نوع الدراما", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private class StatusList(statuse: Array<String>) : AnimeFilter.Select<String>("حالة الدراما", statuse)
    private data class Status(val name: String, val query: String)
    private val statusesName = getStatusList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList() = listOf(
        Type("اختر", ""),
        Type("الدراما الكورية", "korean"),
        Type("الدراما اليابانية", "japanese"),
        Type("الدراما الصينية والتايوانية", "chinese-taiwanese"),
        Type("الدراما التايلاندية", "thai"),
        Type("برامج الترفيه", "kshow")
    )

    private fun getStatusList() = listOf(
        Status("أختر", ""),
        Status("يبث حاليا", "status/ongoing-drama"),
        Status("الدراما المكتملة", "completed-dramas"),
        Status("الدراما القادمة", "status/upcoming-drama")

    )

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality & Server"
            entries = arrayOf("StreamTape", "DooDStream", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("StreamTape", "Dood", "1080", "720", "480", "360")
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
