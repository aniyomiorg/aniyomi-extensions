package eu.kanade.tachiyomi.animeextension.en.edytjedhgmdhm

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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
import java.net.URLEncoder

class Edytjedhgmdhm : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "edytjedhgmdhm"

    override val baseUrl = "https://edytjedhgmdhm.abfhaqrhbnf.workers.dev"

    private val videoFormats = arrayOf(".mkv", ".mp4", ".avi")

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tvs/#$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val page = response.request.url.encodedFragment!!.toInt()
        val path = response.request.url.encodedPath
        val items = document.select(popularAnimeSelector())

        val animeList = items.chunked(CHUNKED_SIZE)[page - 1].mapNotNull {
            val a = it.selectFirst("a")!!
            val name = a.text()
            if (a.attr("href") == "..") return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(joinPaths(path, a.attr("href")))
                title = name.removeSuffix("/")
                thumbnail_url = ""
            }
        }

        return AnimesPage(animeList, (page + 1) * CHUNKED_SIZE <= items.size)
    }

    override fun popularAnimeSelector(): String = "table > tbody > tr:has(a)"

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    // =============================== Search ===============================

    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> {
        return Observable.defer {
            try {
                client.newCall(searchAnimeRequest(page, query, filters)).asObservableSuccess()
            } catch (e: NoClassDefFoundError) {
                // RxJava doesn't handle Errors, which tends to happen during global searches
                // if an old extension using non-existent classes is still around
                throw RuntimeException(e)
            }
        }
            .map { response ->
                searchAnimeParse(response, query)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter
        val subPage = subPageFilter.toUriPart()

        return GET("$baseUrl$subPage#$page")
    }

    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
        val document = response.asJsoup()
        val page = response.request.url.encodedFragment!!.toInt()
        val path = response.request.url.encodedPath
        val items = document.select(popularAnimeSelector()).filter { t ->
            t.selectFirst("a")!!.text().contains(query, true)
        }

        val animeList = items.chunked(CHUNKED_SIZE)[page - 1].mapNotNull {
            val a = it.selectFirst("a")!!
            val name = a.text()
            if (a.attr("href") == "..") return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(joinPaths(path, a.attr("href")))
                title = name.removeSuffix("/")
                thumbnail_url = ""
            }
        }

        return AnimesPage(animeList, (page + 1) * CHUNKED_SIZE <= items.size)
    }

    override fun searchAnimeSelector(): String = throw Exception("Not used")

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun searchAnimeNextPageSelector(): String = throw Exception("Not used")

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search will only search inside selected sub-page"),
        SubPageFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Select subpage",
        arrayOf(
            Pair("TVs", "/tvs/"),
            Pair("Movies", "/movies/"),
            Pair("misc", "/misc/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = Observable.just(anime)

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val episodeList = mutableListOf<SEpisode>()
        var counter = 1

        fun traverseDirectory(url: String) {
            val doc = client.newCall(GET(url)).execute().asJsoup()

            doc.select(popularAnimeSelector()).forEach { link ->
                val href = link.selectFirst("a")!!.attr("href")

                if (href.isNotBlank() && href != "..") {
                    val fullUrl = joinUrl(url, href)
                    if (fullUrl.endsWith("/")) {
                        traverseDirectory(fullUrl)
                    }
                    if (videoFormats.any { t -> fullUrl.endsWith(t) }) {
                        val paths = fullUrl.toHttpUrl().pathSegments

                        val seasonInfoRegex = """(\([\s\w-]+\))(?: ?\[[\s\w-]+\])?${'$'}""".toRegex()
                        val seasonInfo = if (seasonInfoRegex.containsMatchIn(paths[1])) {
                            "${seasonInfoRegex.find(paths[1])!!.groups[1]!!.value} • "
                        } else {
                            ""
                        }

                        val extraInfo = if (paths.size > 3) {
                            "/" + paths.subList(2, paths.size - 1).joinToString("/") { it.trimInfo() }
                        } else {
                            ""
                        }
                        val size = link.selectFirst("td[data-order]")?.let { formatBytes(it.text().toLongOrNull()) }

                        episodeList.add(
                            SEpisode.create().apply {
                                name = videoFormats.fold(paths.last()) { acc, suffix -> acc.removeSuffix(suffix).trimInfo() }
                                this.url = fullUrl
                                scanlator = "${if (size == null) "" else "$size"} • $seasonInfo$extraInfo"
                                episode_number = counter.toFloat()
                            },
                        )
                        counter++
                    }
                }
            }
        }

        traverseDirectory(joinUrl(baseUrl, anime.url))

        return Observable.just(episodeList.reversed())
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.just(listOf(Video(episode.url, "Video", episode.url)))

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    private fun joinPaths(path1: String, path2: String): String {
        return URLEncoder.encode(path1.removeSuffix("/"), "UTF-8") +
            "/" +
            URLEncoder.encode(path2.removeSuffix("/"), "UTF-8") +
            "/"
    }

    private fun joinUrl(url: String, path2: String): String {
        return url.removeSuffix("/") +
            "/" +
            path2.removePrefix("/")
    }

    private fun formatBytes(bytes: Long?): String? {
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
        var value = bytes?.toDouble() ?: return null
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }
    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString
    }

    companion object {
        private const val CHUNKED_SIZE = 300
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) { }
}
