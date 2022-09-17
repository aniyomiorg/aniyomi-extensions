package eu.kanade.tachiyomi.animeextension.pt.goyabu

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.goyabu.GYFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.pt.goyabu.extractors.PlayerOneExtractor
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
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class Goyabu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Goyabu"

    override val baseUrl = "https://goyabu.com"

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
        .add("Accept-Language", GYConstants.ACCEPT_LANGUAGE)
        .add("Referer", baseUrl)

    // ============================== Popular ===============================   
    private fun popularAnimeContainerSelector(): String = "div.index-size > div.episodes-container"

    override fun popularAnimeSelector(): String = "div.anime-episode"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime: SAnime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        anime.title = element.selectFirst("h3").text()
        anime.thumbnail_url = element.selectFirst("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val content = document.selectFirst(popularAnimeContainerSelector())
        val animes = content.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        return AnimesPage(animes, false)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.episodes-container > div.row > a"

    private fun getAllEps(response: Response): List<SEpisode> {
        val epList = mutableListOf<SEpisode>()
        val url = response.request.url.toString()
        val doc = if (url.contains("/videos/")) {
            getRealDoc(response.asJsoup())
        } else { response.asJsoup() }

        val epElementList = doc.select(episodeListSelector())
        epList.addAll(epElementList.map { episodeFromElement(it) })

        val next = doc.selectFirst("div.naco > a.next")
        if (next != null) {
            val newResponse = client.newCall(GET(next.attr("href"))).execute()
            epList.addAll(getAllEps(newResponse))
        }
        return epList
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return getAllEps(response)
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val info_div = element.selectFirst("div.chaps-infs")
        episode.setUrlWithoutDomain(element.attr("href"))
        val epName = info_div.ownText()
        episode.name = epName.substringAfter("– ")
        episode.date_upload = info_div.selectFirst("small").ownText().toDate()
        episode.episode_number = try {
            epName.substringAfter("#")
                .substringBefore(" ")
                .toFloat()
        } catch (e: NumberFormatException) { 0F }
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document: Document = response.asJsoup()
        val html: String = document.html()
        val videoList = mutableListOf<Video>()
        val extractor = PlayerOneExtractor()
        val kanraElement = document.selectFirst("script:containsData(kanra.dev)")
        if (kanraElement != null) {
            val kanraUrl = kanraElement.html()
                .substringAfter("src='")
                .substringBefore("'")
            val kanraVideos = extractor.videoListFromKanraUrl(kanraUrl, client)
            videoList.addAll(kanraVideos)
        }
        val extracted = extractor.videoListFromHtml(html)
        videoList.addAll(extracted)

        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")

    private fun searchAnimeFromResult(result: SearchResultDto): SAnime {
        val anime: SAnime = SAnime.create()
        anime.title = result.title
        anime.setUrlWithoutDomain("/assistir/" + result.slug)
        anime.thumbnail_url = "$baseUrl/${result.cover}"
        return anime
    }

    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeSelector() = throw Exception("not used")

    override fun searchAnimeParse(response: Response) = throw Exception("not used")

    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/assistir/$slug"
        return AnimesPage(listOf(details), false)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        if (query.startsWith(GYConstants.PREFIX_SEARCH_SLUG)) {
            val slug = query.removePrefix(GYConstants.PREFIX_SEARCH_SLUG)
            return client.newCall(GET("$baseUrl/assistir/$slug"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response, slug)
                }
        }
        // else
        val params = GYFilters.getSearchParameters(filters)
        return Observable.just(searchAnimeRequest(page, query, params))
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filterParams: GYFilters.FilterSearchParams): AnimesPage {
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
            results.get(page - 1).map { searchAnimeFromResult(it) }
        }
        return AnimesPage(currentPage, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)

        val infos = doc.selectFirst("div.anime-cover")

        anime.thumbnail_url = infos.selectFirst("img").attr("src")
        anime.title = infos.selectFirst("div.anime-title").text()
        anime.genre = infos.getInfo("Generos")
        anime.status = parseStatus(infos.getInfo("Status"))

        var desc = doc.selectFirst("div.anime-description").text() + "\n"
        desc += "\n" + infos.getInfo("Alternativo", false)
        desc += "\n" + infos.getInfo("Views", false)
        desc += "\n" + infos.getInfo("Episódios", false)
        anime.description = desc

        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div#pagination a:contains(Próxima)"
    override fun latestUpdatesSelector(): String = "div.releases-box div.anime-episode"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        anime.title = element.selectFirst("h3").text()
        anime.thumbnail_url = img.attr("src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    // ============================== Settings ============================== 
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = GYConstants.PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = GYConstants.QUALITY_LIST
            entryValues = GYConstants.QUALITY_LIST
            setDefaultValue(GYConstants.QUALITY_LIST.last())
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

    override fun getFilterList(): AnimeFilterList = GYFilters.filterList

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val player = document.selectFirst("div[itemprop=video]")
        if (player != null) {
            val url = document.selectFirst("div.anime-thumb-single > a").attr("href")
            val req = client.newCall(GET(url)).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun Element.getInfo(item: String, cut: Boolean = true): String {
        val text = this.selectFirst("div.anime-info-right div:contains($item)").text()
        return when {
            cut -> text.substringAfter(": ")
            else -> text.substringAfter(" ")
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            "Em lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun String.toDate(): Long {
        return try {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(GYConstants.PREFERRED_QUALITY, null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.equals(quality)) {
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

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd/MM/yy", Locale.ENGLISH)
        }
    }
}
