package eu.kanade.tachiyomi.animeextension.en.myanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.myanime.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animeextension.en.myanime.extractors.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.en.myanime.extractors.YouTubeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Myanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Myanime"

    override val baseUrl = "https://myanime.live"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private var postBody = ""

    private var postHeaders = headers.newBuilder()

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/category/donghua-list/page/$page/")
    }

    override fun popularAnimeSelector(): String = "main#main > article.post"

    override fun popularAnimeNextPageSelector(): String = "script:containsData(infiniteScroll)"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("h2.entry-header-title > a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
            title = element.selectFirst("h2.entry-header-title > a")!!.text().removePrefix("Playlist ")
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("h2.entry-header-title > a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
            title = element.selectFirst("h2.entry-header-title > a")!!.text()
                .substringBefore(" Episode")
                .substringBefore(" episode")
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter
        val cleanQuery = query.replace(" ", "+")

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$cleanQuery", headers)
            subPageFilter.state != 0 -> GET("$baseUrl${subPageFilter.toUriPart()}page/$page/")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = latestUpdatesFromElement(element)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        SubPageFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Sup-page",
        arrayOf(
            Pair("<select>", ""),
            Pair("izfanmade", "/category/anime/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return Observable.just(anime)
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val paths = response.request.url.encodedPathSegments
        val itemName = paths[paths.size - 2]
        val episodeList = mutableListOf<SEpisode>()

        if (itemName.startsWith("playlist-")) {
            episodeList.addAll(
                document.select(
                    "div.dpt-wrapper > div.dpt-entry",
                ).map {
                    val a = it.selectFirst("a.dpt-permalink")!!
                    SEpisode.create().apply {
                        name = a.text()
                        episode_number = a.text().substringAfter("pisode ").substringBefore(" ").toFloatOrNull() ?: 0F
                        setUrlWithoutDomain(a.attr("href").toHttpUrl().encodedPath)
                    }
                },
            )
        } else if (document.selectFirst("a:contains(All Episodes)[href]") != null) {
            val url = document.selectFirst("a:contains(All Episodes)[href]")!!.attr("href")
            episodeList.addAll(
                episodeListParse(client.newCall(GET(url)).execute()),
            )
        } else if (paths.first() == "tag") {
            var page = 1
            var infiniteScroll = true

            while (infiniteScroll) {
                val epDocument = client.newCall(
                    GET("${response.request.url}page/$page/"),
                ).execute().asJsoup()

                epDocument.select("main#main > article.post").forEach {
                    val a = it.selectFirst("h2.entry-header-title > a")!!
                    val episode = SEpisode.create()

                    episode.name = a.text()
                    episode.episode_number = a.text().substringAfter("pisode ").substringBefore(" ").toFloatOrNull() ?: 0F
                    episode.setUrlWithoutDomain(a.attr("href").toHttpUrl().encodedPath)
                    episodeList.add(episode)
                }

                infiniteScroll = epDocument.selectFirst("script:containsData(infiniteScroll)") != null
                page++
            }
        } else if (document.selectFirst("iframe.youtube-player[src]") != null) {
            val episode = SEpisode.create()

            episode.name = document.selectFirst("title")!!.text()
            episode.episode_number = 0F
            episode.setUrlWithoutDomain(response.request.url.encodedPath)
            episodeList.add(episode)
        } else if (document.selectFirst("span > a[href*=/tag/]") != null) {
            val url = document.selectFirst("span > a[href*=/tag/]")!!.attr("href")
            episodeList.addAll(
                episodeListParse(client.newCall(GET(url)).execute()),
            )
        }

        return episodeList
    }

    override fun episodeListSelector(): String = "div#episodes-tab-pane > div.row > div > div.card"

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        videoList.addAll(
            document.select(videoListSelector()).parallelMap { element ->
                runCatching {
                    val url = element.attr("src")
                        .replace("""^\/\/""".toRegex(), "https://")

                    when {
                        url.contains("dailymotion") -> {
                            DailymotionExtractor(client).videosFromUrl(url)
                        }
                        url.contains("ok.ru") -> {
                            OkruExtractor(client).videosFromUrl(url)
                        }
                        url.contains("youtube.com") -> {
                            YouTubeExtractor(client).videosFromUrl(url, "YouTube - ")
                        }
                        url.contains("gdriveplayer") -> {
                            GdrivePlayerExtractor(client).videosFromUrl(url, name = "Gdriveplayer")
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = "div.entry-content iframe[src]"

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "dailymotion")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality, true) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

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
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("YouTube", "Dailymotion", "ok.ru")
            entryValues = arrayOf("youtube", "dailymotion", "okru")
            setDefaultValue("dailymotion")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
    }
}
