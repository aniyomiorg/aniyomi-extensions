package eu.kanade.tachiyomi.animeextension.ar.asia2tv

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
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

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.postmovie-photo a[title]"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/asian-drama/page/$page/") // page/$page

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        // anime.thumbnail_url = element.select("div.image img").first().attr("data-src")
        anime.title = element.attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nav-links a.next"

    // Episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.loop-episode a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.attr("href").substringAfterLast("-").substringBeforeLast("/") + " : الحلقة"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    // Video urls

    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val link = document.selectFirst("div.loop-episode a.current").attr("href")
        return GET(link)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "ul.server-list-menu li[data-server*=https://dood], ul.server-list-menu li[data-server*=https://streamtape], ul.server-list-menu li[data-server*=https://www.fembed.com]"

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val url = element.attr("data-server")
            val location = element.ownerDocument().location()
            val videoHeaders = Headers.headersOf("Referer", location)
            when {
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
                url.contains("https://www.fembed.com") -> {
                    val apiCall = client.newCall(POST(url.replace("https://www.fembed.com/v", "http://diasfem.com/api/source"))).execute().body!!.string()
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

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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

    /*private fun fembedUrlParse(url: String, quality: String): Video {
        // val noRedirectClient = client.newBuilder().followRedirects(false).build()
        // val refererHeader = Headers.headersOf("Referer", url)

        val apiCall = client.newCall(POST(url.replace("https://www.fembed.com/v", "http://diasfem.com/api/source"))).execute().body!!.string()
        Log.i("lol", "$apiCall")
        val data = apiCall.substringAfter("\"data\":[").substringBefore("],")
        val sources = data.split("\"file\":\"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val src = source.substringAfter("\"file\":\"").substringBefore("\"")
            val quality = source.substringBefore("\"") // .substringAfter("format: '")
            //val videos = Video(src, quality, src, null)
            return Video(url, quality, src, null)
        }
         return Video(url, quality, )
    }*/

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
        anime.setUrlWithoutDomain(element.attr("href"))
        // anime.thumbnail_url = element.select("div.image img").first().attr("data-src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.nav-links a.next"

    override fun searchAnimeSelector(): String = "div.postmovie-photo a[title]"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=l$query"
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
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1 span.title").text()
        anime.thumbnail_url = document.select("div.single-thumb-bg > img").attr("src")
        anime.description = document.select("div.getcontent p").text()
        anime.genre = document.select("div.box-tags a, li:contains(البلد) a").joinToString(", ") { it.text() }

        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filter

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
