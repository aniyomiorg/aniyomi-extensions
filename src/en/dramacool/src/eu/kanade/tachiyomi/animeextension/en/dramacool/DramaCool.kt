package eu.kanade.tachiyomi.animeextension.en.dramacool

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.animeextension.en.dramacool.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.en.dramacool.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.en.dramacool.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animeextension.en.dramacool.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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
import java.text.SimpleDateFormat
import java.util.Locale

class DramaCool : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DramaCool"

    private val defaultBaseUrl = "https://dramacool.cr"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime
    override fun popularAnimeSelector(): String = "ul.list-episode-item li a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-popular-drama?page=$page") // page/$page

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
        val epNum = element.select("h3").text().substringAfter("Episode ")
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.select("span.type").text() + " Episode: " + element.select("h3").text().substringAfter("Episode ")
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        episode.date_upload = parseDate(element.select("span.time").text())
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

    override fun videoListSelector() = "ul.list-server-items li"

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val url = element.attr("data-video")
            when {
                url.contains("sbembed.com") || url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                    url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                    url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                    url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                    url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                    url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                    url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com") ||
                    url.contains("streamsss.net") || url.contains("sbplay2.xyz") // || url.contains("")
                -> {
                    val headers = headers.newBuilder()
                        .set("Referer", url)
                        .set("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("watchsb", "streamsb")
                        .build()
                    val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
                    videoList.addAll(videos)
                }

                url.contains("dood") -> {
                    val video = DoodExtractor(client).videoFromUrl(url)
                    if (video != null) {
                        videoList.add(video)
                    }
                }

                url.contains("fembed.com") ||
                    url.contains("anime789.com") || url.contains("24hd.club") || url.contains("fembad.org") ||
                    url.contains("vcdn.io") || url.contains("sharinglink.club") || url.contains("moviemaniac.org") ||
                    url.contains("votrefiles.club") || url.contains("femoload.xyz") || url.contains("albavido.xyz") ||
                    url.contains("feurl.com") || url.contains("dailyplanet.pw") || url.contains("ncdnstm.com") ||
                    url.contains("jplayer.net") || url.contains("xstreamcdn.com") || url.contains("fembed-hd.com") ||
                    url.contains("gcloud.live") || url.contains("vcdnplay.com") || url.contains("superplayxyz.club") ||
                    url.contains("vidohd.com") || url.contains("vidsource.me") || url.contains("cinegrabber.com") ||
                    url.contains("votrefile.xyz") || url.contains("zidiplay.com") || url.contains("ndrama.xyz") ||
                    url.contains("fcdn.stream") || url.contains("mediashore.org") || url.contains("suzihaza.com") ||
                    url.contains("there.to") || url.contains("femax20.com") || url.contains("javstream.top") ||
                    url.contains("viplayer.cc") || url.contains("sexhd.co") || url.contains("fembed.net") ||
                    url.contains("mrdhan.com") || url.contains("votrefilms.xyz") || url.contains("fembed9hd.com") ||
                    url.contains("embedsito.com") || url.contains("dutrag.com") || // url.contains("") ||
                    url.contains("youvideos.ru") || url.contains("streamm4u.club") || // url.contains("") ||
                    url.contains("moviepl.xyz") || url.contains("asianclub.tv") || // url.contains("") ||
                    url.contains("vidcloud.fun") || url.contains("fplayer.info") || // url.contains("") ||
                    url.contains("diasfem.com") || url.contains("javpoll.com") // url.contains("")
                -> {
                    val videos = FembedExtractor().videosFromUrl(url)
                    if (videos != null) {
                        videoList.addAll(videos)
                    }
                }

                url.contains("streamtape") -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(url)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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
        anime.description = document.select("div.info p").text().substringAfter("Description: ").substringBefore("Country: ").substringBefore("Director: ").substringBefore("Original Network: ")
        anime.author = document.select("div.info p:contains(Original Network) a").text()
        anime.genre = document.select("div.info p:contains(Genre) a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("div.info p:contains(Status) a").text())
        return anime
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recently-added?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select("ul.switch-block a").map { element ->
            val _document = client.newCall(GET("$baseUrl/${element.attr("href")}")).execute().asJsoup()
            SAnime.create().apply {
                title = element.select("h3").text()
                url = _document.select("div.category a").attr("abs:href").substringAfter(baseUrl)
                thumbnail_url = element.select("img").attr("data-original").replace(" ", "%20")
            }
        }
        val hasNextPage = document.select("li.next a").first() != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw Exception("not used")
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw Exception("not used")
    }

    override fun latestUpdatesSelector(): String {
        throw Exception("not used")
    }

    // Preferences
    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF_TITLE
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            this.setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(BASE_URL_PREF, newValue as String).commit()
                    Toast.makeText(screen.context, RESTART_ANIYOMI, Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
            entryValues = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(baseUrlPref)
        screen.addPreference(videoQualityPref)
    }

    // Utilities
    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private const val RESTART_ANIYOMI = "Restart Aniyomi to apply new setting."

        private const val BASE_URL_PREF_TITLE = "Override BaseUrl"

        private val BASE_URL_PREF = "overrideBaseUrl_v${AppInfo.getVersionName()}"

        private const val BASE_URL_PREF_SUMMARY = "For temporary uses. Update extension will erase this setting."

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }
    }
}
