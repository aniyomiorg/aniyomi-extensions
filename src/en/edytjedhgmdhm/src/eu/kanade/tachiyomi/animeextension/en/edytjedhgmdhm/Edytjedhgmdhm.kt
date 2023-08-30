package eu.kanade.tachiyomi.animeextension.en.edytjedhgmdhm

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Edytjedhgmdhm : ParsedAnimeHttpSource() {

    override val name = "edytjedhgmdhm"

    override val baseUrl = "https://edytjedhgmdhm.abfhaqrhbnf.workers.dev"

    private val videoFormats = arrayOf(".mkv", ".mp4", ".avi")

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    // ============================ Initializers ============================

    private val miscList by lazy {
        client.newCall(GET("$baseUrl/misc/")).execute()
            .asJsoup()
            .select("a")
    }

    private val moviesList by lazy {
        client.newCall(GET("$baseUrl/movies/")).execute()
            .asJsoup()
            .select("a")
    }

    private val tvsList by lazy {
        client.newCall(GET("$baseUrl/tvs/")).execute()
            .asJsoup()
            .select("a")
    }

    // ============================== Popular ===============================

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        val results = tvsList.chunked(CHUNKED_SIZE).toList()

        val hasNextPage = results.size > page
        val animeList = if (results.isEmpty()) {
            emptyList()
        } else {
            results.get(page - 1).map {
                SAnime.create().apply {
                    title = it.text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                    thumbnail_url = ""
                }
            }
        }
        return Observable.just(AnimesPage(animeList, hasNextPage))
    }

    override fun popularAnimeRequest(page: Int): Request = throw Exception("Not used")

    override fun popularAnimeParse(response: Response): AnimesPage = throw Exception("Not used")

    override fun popularAnimeSelector(): String = throw Exception("Not used")

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun popularAnimeNextPageSelector(): String = throw Exception("Not used")

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
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter
        val subPage = subPageFilter.toUriPart()

        val resultList = when (subPage) {
            "/tvs/" -> tvsList
            "/movies/" -> moviesList
            "/misc/" -> miscList
            else -> emptyList()
        }

        val results = if (query.isNotEmpty() && resultList.isNotEmpty()) {
            resultList.filter { it.text().contains(query, true) }
                .chunked(CHUNKED_SIZE).toList()
        } else {
            resultList.chunked(CHUNKED_SIZE).toList()
        }

        val hasNextPage = results.size > page
        val animeList = if (results.isEmpty()) {
            emptyList()
        } else {
            results.get(page - 1).map {
                SAnime.create().apply {
                    title = it.text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                    thumbnail_url = ""
                }
            }
        }
        return Observable.just(AnimesPage(animeList, hasNextPage))
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

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

            doc.select("a").forEach { link ->
                val href = link.selectFirst("a")!!.attr("abs:href")

                if (href.isNotBlank()) {
                    if (href.endsWith("/")) {
                        traverseDirectory(href)
                    }
                    if (videoFormats.any { t -> href.endsWith(t) }) {
                        val paths = href.toHttpUrl().pathSegments

                        val extraInfo = if (paths.size > 3) {
                            "/" + paths.subList(2, paths.size - 1).joinToString("/") { it.trimInfo() }
                        } else {
                            "/"
                        }
                        val size = link.selectFirst("td[data-order]")?.let { formatBytes(it.attr("data-order").toLongOrNull()) }

                        episodeList.add(
                            SEpisode.create().apply {
                                name = videoFormats.fold(paths.last()) { acc, suffix -> acc.removeSuffix(suffix).trimInfo() }
                                setUrlWithoutDomain(href)
                                scanlator = "${if (size == null) "" else "$size • "}$extraInfo"
                                date_upload = -1L
                                episode_number = counter.toFloat()
                            },
                        )
                        counter++
                    }
                }
            }
        }

        traverseDirectory(baseUrl + anime.url)

        return Observable.just(episodeList.reversed())
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.just(listOf(Video(baseUrl + episode.url, "Video", baseUrl + episode.url)))

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    private fun formatBytes(bytes: Long?): String? {
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
        var value = bytes?.toDouble() ?: return null
        var i = 0
        while (value >= 1000 && i < units.size - 1) {
            value /= 1000
            i++
        }
        return String.format("%.2f %s", value, units[i])
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
        private const val CHUNKED_SIZE = 30
    }
}
