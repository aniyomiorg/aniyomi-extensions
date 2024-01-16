package eu.kanade.tachiyomi.animeextension.es.fanpelis

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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.Exception

class FanPelis : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "FanPelis"

    override val baseUrl = "https://fanpelis.la"

    override val lang = "es"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = ".ml-item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies-hd/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a .mli-info h2").text()
        anime.thumbnail_url = element.select("a img").attr("data-original")
        anime.description = ""
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = ".pagination li.active ~ li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val isSerie = response.request.url.toString().contains("/series/")
        if (!isSerie) {
            val ep = SEpisode.create()
            ep.setUrlWithoutDomain(response.request.url.toString())
            ep.name = "PELÍCULA"
            ep.episode_number = 1f
            episodeList.add(ep)
        } else {
            document.select("#seasons .tvseason").mapIndexed { idxSeason, season ->
                val noSeason = try { getNumberFromString(season.selectFirst(".les-title strong")?.text() ?: "") } catch (_: Exception) { idxSeason + 1 }
                season.select(".les-content a").mapIndexed { idxEpisode, ep ->
                    val noEpisode = try { getNumberFromString(ep.text()) } catch (_: Exception) { idxEpisode + 1 }
                    val episode = SEpisode.create()
                    episode.name = try { "T$noSeason - E$noEpisode - ${ep.text()}" } catch (_: Exception) { "" }
                    episode.episode_number = noEpisode.toString().toFloat()
                    episode.setUrlWithoutDomain(ep.attr("href"))
                    episodeList.add(episode)
                }
            }
        }
        return episodeList.reversed()
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(".movieplay iframe").map { iframe ->
            var url = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            (if (url.startsWith("//")) "https:$url" else url).also { url = it }
            val embedUrl = url.lowercase()

            if (embedUrl.contains("streamtape")) {
                val video = StreamTapeExtractor(client).videoFromUrl(url, "Streamtape")
                if (video != null) {
                    videoList.add(video)
                }
            }
            if (embedUrl.contains("streamlare")) {
                try {
                    StreamlareExtractor(client).videosFromUrl(url)?.let {
                        videoList.add(it)
                    }
                } catch (_: Exception) {}
            }
            if (embedUrl.contains("doodstream") || embedUrl.contains("dood")) {
                val video = try {
                    DoodExtractor(client).videoFromUrl(url, "DoodStream", true)
                } catch (e: Exception) {
                    null
                }
                if (video != null) {
                    videoList.add(video)
                }
            }
            if (embedUrl.contains("okru") || embedUrl.contains("ok.ru")) {
                val videos = OkruExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) },
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "DoodStream")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                videoSorted.drop(preferredIdx + 1)
                videoSorted[0] = videoSorted[preferredIdx]
            }
            videoSorted.toList()
        } catch (e: Exception) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query")
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page/")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Peliculas", "movies-hd"),
            Pair("Series", "series"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = externalOrInternalImg(document.selectFirst("#mv-info .mvic-thumb img")!!.attr("src"))
        anime.description = document.selectFirst(".mvic-desc .desc p")!!.text().removeSurrounding("\"")
        anime.title = document.selectFirst(".mvic-desc h3[itemprop=\"name\"]")?.text() ?: ""
        anime.genre = document.select(".mvic-info .mvici-left p a[rel=\"category tag\"]").joinToString { it.text() }
        anime.status = if (document.selectFirst("link[rel=\"canonical\"]")?.text()?.contains("/series/") == true) SAnime.UNKNOWN else SAnime.COMPLETED
        return anime
    }

    private fun externalOrInternalImg(url: String): String {
        return if (url.contains("https")) url else "$baseUrl/$url"
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf(
                "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
                "YourUpload", "DoodStream", "StreamTape",
            ) // video servers without resolution
            entryValues = arrayOf(
                "Okru:1080p",
                "Okru:720p",
                "Okru:480p",
                "Okru:360p",
                "Okru:240p",
                "Okru:144p", // Okru
                "DoodStream",
                "StreamTape",
            ) // video servers without resolution
            setDefaultValue("DoodStream")
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

    class StreamlareExtractor(private val client: OkHttpClient) {
        private val json: Json by injectLazy()
        fun videosFromUrl(url: String): Video? {
            val id = url.substringAfter("/e/").substringBefore("?poster")
            val videoUrlResponse =
                client.newCall(POST("https://slwatch.co/api/video/stream/get?id=$id")).execute()
                    .asJsoup()
            json.decodeFromString<JsonObject>(
                videoUrlResponse.select("body").text(),
            )["result"]?.jsonObject?.forEach { quality ->
                if (quality.toString().contains("file=\"")) {
                    val videoUrl = quality.toString().substringAfter("file=\"").substringBefore("\"").trim()
                    val type = if (videoUrl.contains(".m3u8")) "HSL" else "MP4"
                    val headers = Headers.Builder()
                        .add("authority", videoUrl.substringBefore("/hls").substringBefore("/mp4"))
                        .add("origin", "https://slwatch.co")
                        .add("referer", "https://slwatch.co/e/" + url.substringAfter("/e/"))
                        .add(
                            "sec-ch-ua",
                            "\"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"108\", \"Google Chrome\";v=\"108\"",
                        )
                        .add("sec-ch-ua-mobile", "?0")
                        .add("sec-ch-ua-platform", "\"Windows\"")
                        .add("sec-fetch-dest", "empty")
                        .add("sec-fetch-mode", "cors")
                        .add("sec-fetch-site", "cross-site")
                        .add(
                            "user-agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/),108.0.0.0 Safari/537.36",
                        )
                        .add("Accept-Encoding", "gzip, deflate, br")
                        .add("accept", "*/*")
                        .add(
                            "accept-language",
                            "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7,zh-TW;q=0.6,zh-CN;q=0.5,zh;q=0.4",
                        )
                        .build()
                    return Video(videoUrl, "Streamlare:$type", videoUrl, headers = headers)
                }
            }
            return null
        }
    }
}
