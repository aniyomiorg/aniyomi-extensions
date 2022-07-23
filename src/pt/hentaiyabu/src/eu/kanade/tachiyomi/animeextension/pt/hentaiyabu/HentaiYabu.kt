package eu.kanade.tachiyomi.animeextension.pt.hentaiyabu

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.hentaiyabu.HYFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.pt.hentaiyabu.extractors.PlayerOneExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class HentaiYabu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HentaiYabu"

    override val baseUrl = "https://hentaiyabu.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private var searchJson: List<SearchResultDto>? = null

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept-Language", HYConstants.ACCEPT_LANGUAGE)
        .add("Referer", baseUrl)
        .add("User-Agent", HYConstants.USER_AGENT)

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.main-index > div.index-size > div.episodes-container > div.anime-episode"
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime: SAnime = SAnime.create()
        val img = element.selectFirst("img")
        val elementA = element.selectFirst("a")
        anime.setUrlWithoutDomain(elementA.attr("href"))
        anime.title = element.selectFirst("h3").text()
        anime.thumbnail_url = img.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        return AnimesPage(animes, false)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.left-single div.anime-episode"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val doc = if (url.contains("/video/")) {
            getRealDoc(response.asJsoup())
        } else {
            response.asJsoup()
        }
        return doc.select(episodeListSelector()).map {
            episodeFromElement(it)
        }.reversed()
    }
    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val elementA = element.selectFirst("a")
        episode.setUrlWithoutDomain(elementA.attr("href"))
        val name = element.selectFirst("h3").text()
        val epName = name.substringAfterLast("– ")
        episode.name = epName
        episode.episode_number = try {
            epName.substringAfter(" ").substringBefore(" ").toFloat()
        } catch (e: NumberFormatException) { 0F }
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document: Document = response.asJsoup()
        val html: String = document.html()
        val videoList = mutableListOf<Video>()
        val kanraElement = document.selectFirst("script:containsData(kanra.dev)")
        if (kanraElement != null) {
            val kanraUrl = kanraElement.html()
                .substringAfter("src='")
                .substringBefore("'")
            val kanraVideos = PlayerOneExtractor(client).videoListFromKanraUrl(kanraUrl)
            videoList.addAll(kanraVideos)
        } else {
            val extracted = PlayerOneExtractor()
                .videoListFromHtml(html)
            videoList.addAll(extracted)
        }

        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeParse(response: Response) = throw Exception("not used")

    private fun searchAnimeParse(result: SearchResultDto): SAnime {
        val anime: SAnime = SAnime.create()
        anime.title = result.title
        anime.url = "/hentai/${result.slug}"
        anime.thumbnail_url = "$baseUrl/${result.cover}"
        return anime
    }

    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeSelector() = throw Exception("not used")

    private fun searchAnimeByIdParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/hentai/$slug"
        return AnimesPage(listOf(details), false)
    }
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(HYConstants.PREFIX_SEARCH_SLUG)) {
            val slug = query.removePrefix(HYConstants.PREFIX_SEARCH_SLUG)
            client.newCall(GET("$baseUrl/hentai/$slug"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, slug)
                }
        } else {
            val params = HYFilters.getSearchParameters(filters)
            Observable.just(searchAnimeRequest(page, query, params))
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filterParams: HYFilters.FilterSearchParams): AnimesPage {
        filterParams.animeName = query
        if (searchJson == null) {
            val body = client.newCall(GET("$baseUrl/api/show.php"))
                .execute()
                .body?.string().orEmpty()
            searchJson = json.decodeFromString<List<SearchResultDto>>(body)
        }
        val mutableJson = searchJson!!.toMutableList()
        mutableJson.applyFilterParams(filterParams)
        val results = mutableJson.chunked(30)
        val hasNextPage = results.size > page
        val currentPage = if (results.size == 0) {
            emptyList<SAnime>()
        } else {
            results.get(page - 1).map { searchAnimeParse(it) }
        }
        return AnimesPage(currentPage, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)

        val img = doc.selectFirst("div.anime-cover")
        val infos = img.selectFirst("div.anime-info-right")

        anime.thumbnail_url = img.selectFirst("img").attr("src")
        anime.title = infos.selectFirst("h1").text()
        anime.genre = infos.getInfo("Generos")
            ?.replace(".", "") // Prevents things like "Yuri."
        anime.status = parseStatus(infos.getInfo("Status"))
        anime.description = doc.selectFirst("div.anime-description").text()

        return anime
    }

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector() = "div#pagination a:contains(Próxima)"
    override fun latestUpdatesSelector() = "div.releases-box div.anime-episode"

    override fun latestUpdatesFromElement(element: Element) =
        popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        val videoQualityPref = ListPreference(screen.context).apply {
            key = HYConstants.PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = HYConstants.QUALITY_LIST
            entryValues = HYConstants.QUALITY_LIST
            setDefaultValue(HYConstants.QUALITY_LIST.last())
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

    override fun getFilterList(): AnimeFilterList = HYFilters.filterList

    // ============================= Utilities ==============================

    private fun getRealDoc(document: Document): Document {
        val elementA = document.selectFirst("div.anime-thumb-single > a")
        if (elementA != null) {
            val url = elementA.attr("href")
            val req = client.newCall(GET(url)).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun Element.getInfo(tag: String): String? {
        val item = this.selectFirst("div:contains($tag) > i").parent()
        val text = item?.text()
        val info = text?.substringAfter(tag + ": ") ?: ""
        return when {
            info == "" -> null
            else -> info
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            "Em lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(HYConstants.PREFERRED_QUALITY, null)
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            when {
                quality != null && video.quality.contains(quality) -> {
                    newList.add(preferred, video)
                    preferred++
                }
                else -> newList.add(video)
            }
        }
        return newList
    }
}
