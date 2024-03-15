package eu.kanade.tachiyomi.animeextension.en.slothanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SlothAnime : ParsedAnimeHttpSource() {

    override val name = "SlothAnime"

    override val baseUrl = "https://slothanime.com"

    override val lang = "en"

    override val supportsLatest = true

    /*
    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
     */

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl/list/viewed?page=$page"
        } else {
            "$baseUrl/list/viewed"
        }

        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = ".row > div > .anime-card-md"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")!!.imgAttr()
        with(element.selectFirst("a[href~=/anime]")!!) {
            title = text()
            setUrlWithoutDomain(attr("abs:href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = ".pagination > .active ~ li:has(a)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl/list/latest?page=$page"
        } else {
            "$baseUrl/list/latest"
        }

        return GET(url, headers)
    }
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().first()
        val typeFilter = filters.filterIsInstance<TypeFilter>().first()
        val statusFilter = filters.filterIsInstance<StatusFilter>().first()
        val sortFilter = filters.filterIsInstance<SortFilter>().first()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addQueryParameter("q", query)
            genreFilter.getIncluded().forEachIndexed { idx, value ->
                addQueryParameter("genre[$idx]", value)
            }
            typeFilter.getValues().forEachIndexed { idx, value ->
                addQueryParameter("type[$idx]", value)
            }
            addQueryParameter("status", statusFilter.getValue())
            addQueryParameter("sort", sortFilter.getValue())
            genreFilter.getExcluded().forEachIndexed { idx, value ->
                addQueryParameter("ignore_genre[$idx]", value)
            }

            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst(".single-title > h5")!!.text()
        thumbnail_url = document.selectFirst(".single-cover > img")!!.imgAttr()
        description = document.selectFirst(".single-detail:has(span:contains(Description)) .more-content")?.text()
        genre = document.select(".single-tag > a.tag").joinToString { it.text() }
        author = document.select(".single-detail:has(span:contains(Studios)) .value a").joinToString { it.text() }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = ".list-episodes-container > a[class~=episode]"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        name = element.text()
            .replace(Regex("""^EP """), "Episode ")
            .replace(Regex("""^\d+""")) { m -> "Episode ${m.value}" }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        return emptyList()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }
}
