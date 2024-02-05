package eu.kanade.tachiyomi.animeextension.es.ennovelas

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit
import kotlin.Exception

class EnNovelas : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EnNovelas"

    override val baseUrl = "https://u.ennovelas.net"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = ".block-post"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/telenovelas/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a .title").text()
        anime.thumbnail_url = element.select("a img").attr("data-img")
        anime.description = ""
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = ".pagination .current ~ a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seasonIds = document.select(".listSeasons li[data-season]")
        var noEp = 1F
        if (seasonIds.any()) {
            seasonIds.reversed().map {
                try {
                    val headers = headers.newBuilder()
                        .add("authority", response.request.url.toString().substringAfter("https://").substringBefore("/wp-content"))
                        .add("referer", response.request.url.toString())
                        .add("accept", "*/*")
                        .add("accept-language", "es-MX,es;q=0.9,en;q=0.8")
                        .add("sec-ch-ua", "\"Google Chrome\";v=\"119\", \"Chromium\";v=\"119\", \"Not?A_Brand\";v=\"24\"")
                        .add("sec-ch-ua-mobile", "?0")
                        .add("sec-ch-ua-platform", "\"Windows\"")
                        .add("sec-fetch-dest", "empty")
                        .add("sec-fetch-mode", "cors")
                        .add("sec-fetch-site", "same-origin")
                        .add("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                        .add("x-requested-with", "XMLHttpRequest")
                        .build()
                    val season = getNumberFromEpsString(it.text())
                    val tmpClient = client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(35, TimeUnit.SECONDS)
                        .readTimeout(35, TimeUnit.SECONDS)
                        .build()
                    tmpClient.newCall(GET("$baseUrl/wp-content/themes/vo2022/temp/ajax/seasons.php?seriesID=${it.attr("data-season")}", headers = headers))
                        .execute().asJsoup().select(".block-post").forEach { element ->
                            val ep = SEpisode.create()
                            val noEpisode = getNumberFromEpsString(element.selectFirst("a .episodeNum span:nth-child(2)")!!.text()).ifEmpty { noEp }
                            ep.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                            ep.name = "T$season - E$noEpisode - Cap" + element.selectFirst("a .title")!!.text().substringAfter("Cap")
                            ep.episode_number = noEp
                            episodeList.add(ep)
                            noEp += 1
                        }
                } catch (_: Exception) { }
            }
        } else {
            document.select(".block-post").forEach { element ->
                val ep = SEpisode.create()
                val noEpisode = getNumberFromEpsString(element.selectFirst("a .episodeNum span:nth-child(2)")!!.text())
                ep.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                ep.name = "Cap" + element.selectFirst("a .title")!!.text().substringAfter("Cap")
                ep.episode_number = noEpisode.toFloat()
                episodeList.add(ep)
            }
        }
        return episodeList.reversed()
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val form = document.selectFirst("#btnServers form")
        val urlRequest = form?.attr("action") ?: ""
        val watch = form?.selectFirst("input")?.attr("value")
        val domainRegex = Regex("^(?:https?:\\/\\/)?(?:[^@\\/\\n]+@)?(?:www\\.)?([^:\\/?\\n]+)")
        val domainUrl = domainRegex.findAll(urlRequest).firstOrNull()?.value ?: ""

        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val body = "watch=$watch&submit=".toRequestBody(mediaType)
        val headers = headers.newBuilder()
            .add("authority", domainUrl.substringAfter("//"))
            .add("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .add("accept-language", "es-MX,es;q=0.9,en;q=0.8")
            .add("content-type", "application/x-www-form-urlencoded")
            .add("origin", baseUrl)
            .add("referer", "$baseUrl/")
            .add("sec-ch-ua-mobile", "?0")
            .add("upgrade-insecure-requests", "1")
            .build()

        client.newCall(POST(urlRequest, headers, body)).execute().asJsoup().select(".serversList li").map {
            val frameString = it.attr("abs:data-server")
            val link = frameString.substringAfter("src='").substringBefore("'")
                .replace("https://api.mycdn.moe/sblink.php?id=", "https://streamsb.net/e/")
                .replace("https://api.mycdn.moe/uqlink.php?id=", "https://uqload.co/embed-")
            if (link.contains("ok.ru")) {
                try {
                    OkruExtractor(client).videosFromUrl(link).let { videoList.addAll(it) }
                } catch (_: Exception) {}
            }
            if (link.contains("vidmoly")) {
                try {
                    VidmolyExtractor(client).getVideoList(link, "").let { videoList.addAll(it) }
                } catch (_: Exception) {}
            }
            if (link.contains("voe")) {
                try {
                    VoeExtractor(client).videosFromUrl(link).also(videoList::addAll)
                } catch (_: Exception) {}
            }
            if (link.contains("vudeo")) {
                try {
                    VudeoExtractor(client).videosFromUrl(link).let { videoList.addAll(it) }
                } catch (_: Exception) {}
            }
            if (link.contains("streamtape")) {
                try {
                    StreamTapeExtractor(client).videoFromUrl(link)?.let { videoList.add(it) }
                } catch (_: Exception) {}
            }
            if (link.contains("uqload")) {
                try {
                    UqloadExtractor(client).videosFromUrl(if (link.contains(".html")) link else "$link.html", "Uqload").let { videoList.addAll(it) }
                } catch (_: Exception) {}
            }
            if (link.contains("dood")) {
                try {
                    DoodExtractor(client).videoFromUrl(link)?.let { videoList.add(it) }
                } catch (_: Exception) {}
            }
            if (link.contains("streamlare")) {
                try {
                    StreamlareExtractor(client).videosFromUrl(link)?.let { videoList.addAll(it) }
                } catch (_: Exception) {}
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Voex")
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> GET("$baseUrl/search/$query/page/$page/")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.select(".pagination .current ~ a").any()
        document.select(".block-post").map { element ->
            if (element.selectFirst("a")?.attr("href")?.contains("/series/") == true) {
                val anime = SAnime.create()
                anime.setUrlWithoutDomain(element.select("a").attr("href"))
                anime.title = element.select("a .title").text()
                anime.thumbnail_url = element.select("a img").attr("data-img")
                anime.description = ""
                animeList.add(anime)
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()
    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val title = document.selectFirst("[itemprop=\"name\"] a")?.text() ?: ""

        anime.title = title
        anime.description = document.selectFirst(".postDesc .post-entry div")?.text() ?: title
        anime.genre = document.select("ul.postlist li:nth-child(1) span a").joinToString { it.text() }
        anime.status = parseStatus(document.select("ul.postlist li:nth-child(8) .getMeta span a").text().trim())
        anime.artist = document.selectFirst(".postInfo > .getMeta > span:nth-child(2) > a")?.text() ?: ""
        anime.author = document.selectFirst("ul.postlist li:nth-child(3) span a")?.text() ?: ""
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Continuous") -> SAnime.ONGOING
            statusString.contains("Finished") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
            "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
            "Streamlare:1080p", "Streamlare:720p", "Streamlare:480p", "Streamlare:360p", "Streamlare:240p", // Streamlare
            "StreamTape", "Voex", "DoodStream", "YourUpload", "MixDrop", "Vidmoly", "Uqload",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Voex")
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

class VidmolyExtractor(private val client: OkHttpClient) {
    fun getVideoList(url: String, lang: String): List<Video> {
        val body = client.newCall(GET(url)).execute()
            .body.string()
        val playlistUrl = Regex("file:\"(\\S+?)\"").find(body)!!.groupValues.get(1)
        val headers = Headers.headersOf("Referer", "https://vidmoly.to")
        val playlistData = client.newCall(GET(playlistUrl, headers)).execute()
            .body.string()

        val separator = "#EXT-X-STREAM-INF:"
        return playlistData.substringAfter(separator).split(separator).map {
            val quality = it.substringAfter("RESOLUTION=")
                .substringAfter("x")
                .substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")
            Video(videoUrl, "$lang - $quality", videoUrl, headers)
        }
    }
}
