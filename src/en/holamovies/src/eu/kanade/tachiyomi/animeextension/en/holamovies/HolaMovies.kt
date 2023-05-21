package eu.kanade.tachiyomi.animeextension.en.holamovies

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.holamovies.extractors.GDBotExtractor
import eu.kanade.tachiyomi.animeextension.en.holamovies.extractors.GDFlixExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HolaMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HolaMovies"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://holamovies.org")!! }

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun popularAnimeSelector(): String = "div#content > div > div.row > div"

    override fun popularAnimeNextPageSelector(): String = "nav.gridlove-pagination > span.current + a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
            title = element.selectFirst("h1")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "div#latest-tab-pane > div.row > div.col-md-6"

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val thumbnailUrl = element.selectFirst("img")!!.attr("data-src")

        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a.animeparent")!!.attr("href"))
            thumbnail_url = if (thumbnailUrl.contains(baseUrl.toHttpUrl().host)) {
                thumbnailUrl
            } else {
                baseUrl + thumbnailUrl
            }
            title = element.selectFirst("span.animename")!!.text()
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
//        val FILTER_LIST = if (filters.isEmpty()) getFilterList() else filters
//        val genreFilter = FILTER_LIST.find { it is GenreFilter } as GenreFilter
//        val recentFilter = FILTER_LIST.find { it is RecentFilter } as RecentFilter
//        val seasonFilter = FILTER_LIST.find { it is SeasonFilter } as SeasonFilter

        val cleanQuery = query.replace(" ", "+")

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$cleanQuery", headers)
//            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}?page=$page")
//            recentFilter.state != 0 -> GET("https://ajax.gogo-load.com/ajax/page-recent-release.html?page=$page&type=${recentFilter.toUriPart()}")
            else -> GET("$baseUrl/popular.html?page=$page")
        }
    }
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== FILTERS ===============================

    // Todo - add these when the site starts working again
//    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
//        AnimeFilter.Header("Text search ignores filters"),
//        GenreFilter(),
//    )

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequest(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response, anime).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    private fun animeDetailsParse(response: Response, anime: SAnime): SAnime {
        val document = response.asJsoup()
        val oldAnime = anime

        oldAnime.description = document.selectFirst("div.entry-content > p")?.text()

        return oldAnime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val sizeRegex = Regex("""[\[\(](\d+\.?\d* ?[KMGT]B)[\]\)]""")
        val zipRegex = Regex("""\bZIP\b""")

        document.select("div.entry-content:has(h3,h4,p) > p:has(a[href]):not(:has(span.mb-text))").forEach {
            it.select("a").forEach { link ->
                if (zipRegex.find(link.text()) != null) return@forEach
                val info = it.previousElementSiblings().firstOrNull { prevTag ->
                    arrayOf("h3", "h4", "p").contains(prevTag.normalName())
                }?.text()

                val size = sizeRegex.find(link.text())?.groupValues?.get(1)

                val episode = SEpisode.create()
                episode.name = link.text()
                episode.episode_number = 1F
                episode.date_upload = -1L
                episode.url = link.attr("href")
                episode.scanlator = "${if (size == null) "" else "$size • "}$info"
                episodeList.add(episode)
            }
        }

        // We don't want to parse multiple times
        if (episodeList.isEmpty()) {
            document.select("div.entry-content:has(pre:contains(episode)) > p:has(a[href])").reversed().forEach {
                it.select("a").forEach { link ->
                    if (zipRegex.find(link.text()) != null) return@forEach
                    val info = it.previousElementSiblings().firstOrNull { prevTag ->
                        prevTag.normalName() == "p" && prevTag.selectFirst("strong") != null
                    }?.text()
                    val episodeNumber = it.previousElementSiblings().firstOrNull { prevTag ->
                        prevTag.normalName() == "pre" && prevTag.text().contains("episode", true)
                    }?.text()?.substringAfter(" ")?.toFloatOrNull() ?: 1F

                    val size = sizeRegex.find(link.text())?.groupValues?.get(1)

                    val episode = SEpisode.create()
                    episode.name = "Ep. $episodeNumber - ${link.text()}"
                    episode.episode_number = episodeNumber
                    episode.date_upload = -1L
                    episode.url = link.attr("href")
                    episode.scanlator = "${if (size == null) "" else "$size • "}$info"
                    episodeList.add(episode)
                }
            }
        }

        if (episodeList.isEmpty()) {
            document.select("div.entry-content > p:has(a[href]:has(span.mb-text)), div.entry-content > em p:has(a[href]:has(span.mb-text))").reversed().forEach {
                it.select("a").forEach { link ->
                    if (zipRegex.find(link.text()) != null) return@forEach
                    val title = it.previousElementSiblings().firstOrNull { prevTag ->
                        arrayOf("p", "h5").contains(prevTag.normalName()) && prevTag.text().isNotBlank()
                    }?.text() ?: "Item"

                    val size = sizeRegex.find(title)?.groupValues?.get(1)
                        ?: sizeRegex.find(link.text())?.groupValues?.get(1)

                    val episode = SEpisode.create()
                    episode.name = "$title - ${link.text()}"
                    episode.episode_number = 1F
                    episode.date_upload = -1L
                    episode.scanlator = size
                    episode.url = link.attr("href")
                    episodeList.add(episode)
                }
            }
        }

        if (episodeList.isEmpty()) {
            document.select("div.entry-content:has(pre:has(em)) > p:has(a[href])").reversed().forEach {
                it.select("em a").forEach { link ->
                    if (zipRegex.find(link.text()) != null) return@forEach
                    if (link.text().contains("click here", true)) return@forEach
                    val title = it.previousElementSiblings().firstOrNull { prevTag ->
                        prevTag.normalName() == "pre"
                    }?.text()

                    val size = sizeRegex.find(link.text())?.groupValues?.get(1)

                    val episode = SEpisode.create()
                    episode.name = "$title - ${link.text()}"
                    episode.episode_number = 1F
                    episode.date_upload = -1L
                    episode.scanlator = size
                    episode.url = link.attr("href")
                    episodeList.add(episode)
                }
            }
        }

        if (episodeList.isEmpty()) {
            document.select("div.entry-content:has(p:has(em)) > p:has(a[href])").reversed().forEach {
                it.select("a").forEach { link ->
                    if (zipRegex.find(link.text()) != null) return@forEach
                    val info = it.previousElementSiblings().firstOrNull { prevTag ->
                        prevTag.normalName() == "p" && prevTag.text().isNotBlank() && prevTag.selectFirst("a") == null
                    }?.text() ?: "Item"

                    val size = sizeRegex.find(link.text())?.groupValues?.get(1)

                    val episode = SEpisode.create()
                    episode.name = link.text()
                    episode.episode_number = 1F
                    episode.date_upload = -1L
                    episode.scanlator = "${if (size == null) "" else "$size • "}$info"
                    episode.url = link.attr("href")
                    episodeList.add(episode)
                }
            }
        }

        if (episodeList.isEmpty()) {
            document.select("div.entry-content > div.wp-block-buttons").reversed().forEach {
                it.select("a").forEach { link ->
                    if (zipRegex.find(link.text()) != null) return@forEach
                    val info = it.previousElementSiblings().firstOrNull { prevTag ->
                        prevTag.normalName() == "pre" && prevTag.text().isNotBlank()
                    }?.text() ?: ""

                    val size = sizeRegex.find(link.text())?.groupValues?.get(1)

                    val episode = SEpisode.create()
                    episode.name = link.text()
                    episode.episode_number = 1F
                    episode.date_upload = -1L
                    episode.scanlator = "${if (size == null) "" else "$size • "}$info"
                    episode.url = link.attr("href")
                    episodeList.add(episode)
                }
            }
        }

        if (episodeList.isEmpty()) {
            document.select("div.entry-content > figure.wp-block-embed").reversed().forEach {
                it.select("a").forEach { link ->
                    if (zipRegex.find(link.text()) != null) return@forEach
                    val size = sizeRegex.find(link.text())?.groupValues?.get(1)

                    val episode = SEpisode.create()
                    episode.name = link.text()
                    episode.episode_number = 1F
                    episode.date_upload = -1L
                    episode.scanlator = size
                    episode.url = link.attr("href")
                    episodeList.add(episode)
                }
            }
        }

        if (episodeList.isEmpty()) {
            document.select("div.entry-content > a[role=button][href]").reversed().forEach {
                it.select("a").forEach { link ->
                    if (zipRegex.find(link.text()) != null) return@forEach
                    val size = sizeRegex.find(link.text())?.groupValues?.get(1)

                    val episode = SEpisode.create()
                    episode.name = link.text()
                    episode.episode_number = 1F
                    episode.date_upload = -1L
                    episode.scanlator = size
                    episode.url = link.attr("href")
                    episodeList.add(episode)
                }
            }
        }

        return episodeList
    }

    override fun episodeListSelector(): String = throw Exception("Not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val videoList = when {
            episode.url.toHttpUrl().host.contains("gdflix") -> {
                GDFlixExtractor(client, headers).videosFromUrl(episode.url)
            }
            episode.url.toHttpUrl().host.contains("gdtot") ||
                episode.url.toHttpUrl().host.contains("gdbot") -> {
                GDBotExtractor(client, headers, preferences).videosFromUrl(episode.url)
            }
            else -> { throw Exception("Unsupported url: ${episode.url}") }
        }

        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
