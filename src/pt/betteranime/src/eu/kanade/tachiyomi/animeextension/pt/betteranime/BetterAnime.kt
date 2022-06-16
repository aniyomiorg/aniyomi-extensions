package eu.kanade.tachiyomi.animeextension.pt.betteranime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.betteranime.dto.LivewireResponseDto
import eu.kanade.tachiyomi.animeextension.pt.betteranime.dto.PayloadData
import eu.kanade.tachiyomi.animeextension.pt.betteranime.dto.PayloadItem
import eu.kanade.tachiyomi.animeextension.pt.betteranime.extractors.BetterAnimeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class BetterAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Better Anime"

    override val baseUrl = "https://betteranime.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    private fun nextPageSelector(): String = "ul.pagination li.page-item:contains(›)"
    override fun popularAnimeNextPageSelector() = throw Exception("not used")
    override fun popularAnimeSelector(): String = "div.list-animes article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")!!
        val url = element.selectFirst("a")?.attr("href")!!
        anime.setUrlWithoutDomain(url)
        anime.title = element.selectFirst("h3")?.text()!!
        anime.thumbnail_url = "https:" + img.attr("src")
        return anime
    }

    // The site doesn't have a popular anime tab, so we use the latest anime page instead.
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/ultimosAdicionados?page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        val hasNextPage = hasNextPage(document)
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "ul#episodesList > li.list-group-item-action > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector()).map { element ->
            episodeFromElement(element)
        }
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val episodeName = element.text()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = episodeName
        episode.episode_number = try {
            episodeName.substringAfterLast(" ").toFloat()
        } catch (e: NumberFormatException) { 0F }
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val html = response.body?.string().orEmpty()
        val extractor = BetterAnimeExtractor(client, baseUrl, json)
        return extractor.videoListFromHtml(html)
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        val data = json.decodeFromString<LivewireResponseDto>(body)
        val html = data.effects.html?.unescape() ?: ""
        val document = Jsoup.parse(html)
        val animes = document.select(searchAnimeSelector()).map { element ->
            searchAnimeFromElement(element)
        }
        return AnimesPage(animes, hasNextPage(document))
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH_PATH)) {
            val path = query.removePrefix(PREFIX_SEARCH_PATH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByPathParse(response, path)
                }
        } else {
            val params = BAFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    private fun searchAnimeByPathParse(response: Response, path: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/$path"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: BAFilters.FilterSearchParams): Request {
        if (page == 1)
            updateInitialData(GET("$baseUrl/pesquisa"))

        val searchParams = mutableListOf<PayloadItem>()
        searchParams.add(PayloadItem(PayloadData(method = "search"), "callMethod"))
        searchParams.add(
            PayloadItem(
                PayloadData(
                    method = "gotoPage",
                    params = listOf(JsonPrimitive(page), JsonPrimitive("page"))
                ),
                "callMethod"
            )
        )

        val data = mutableListOf<PayloadData>()

        if (filters.genres.size > 1)
            data.add(PayloadData(name = "byGenres", value = filters.genres))
        if (!filters.year.isBlank())
            data.add(PayloadData(name = "byYear", value = listOf(filters.year)))
        if (!filters.language.isBlank())
            data.add(PayloadData(name = "byLanguage", value = listOf(filters.language)))
        if (!query.isBlank())
            data.add(PayloadData(name = "searchTerm", value = listOf(query)))

        searchParams += data.map { PayloadItem(it, "syncInput") }
        return wireRequest("anime-search", searchParams)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)

        val infos = doc.selectFirst("div.infos_left > div.anime-info")
        val img = doc.selectFirst("div.infos-img > img")
        anime.thumbnail_url = "https:" + img.attr("src")
        anime.title = img.attr("alt")
        val genres = infos.select("div.anime-genres > a")
            .joinToString(", ") {
                it.text()
            }
        anime.genre = genres
        anime.author = infos.getInfo("Produtor")
        anime.artist = infos.getInfo("Estúdio")
        anime.status = parseStatus(infos.getInfo("Estado"))
        var desc = infos.selectFirst("div.anime-description").text() + "\n\n"
        desc += infos.select(">p").joinToString("\n") { it.text() }
        anime.description = desc

        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw Exception("not used")
    override fun latestUpdatesSelector() = throw Exception("not used")
    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/ultimosLancamentos?page=$page")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(QUALITY_LIST.last())
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

    override fun getFilterList(): AnimeFilterList = BAFilters.filterList

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val link = document.selectFirst("div.anime-title a")
        if (link != null) {
            val req = client.newCall(GET(link.attr("href"))).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        val next = document.selectFirst(nextPageSelector())
        if (next == null) return false
        return !next.hasClass("disabled")
    }

    private fun updateInitialData(request: Request) {
        val document = client.newCall(request).execute().asJsoup()
        val wireElement = document.selectFirst("[wire:id]")
        WIRE_TOKEN = document.html()
            .substringAfter("livewire_token")
            .substringAfter("'")
            .substringBefore("'")
        INITIAL_DATA = wireElement.attr("wire:initial-data")!!.dropLast(1)
    }

    private fun wireRequest(path: String, updates: List<PayloadItem>): Request {
        val url = "$baseUrl/livewire/message/$path"
        val items = updates.joinToString(",") { json.encodeToString(it) }
        val data = "$INITIAL_DATA, \"updates\": [$items]}"
        val reqBody = data.toRequestBody("application/json".toMediaType())
        val headers = headersBuilder()
            .add("x-livewire", "true")
            .add("x-csrf-token", WIRE_TOKEN)
            .build()
        return POST(url, headers, reqBody)
    }

    private fun Element.getInfo(key: String): String? {
        val element = this.selectFirst("p:containsOwn($key) > span")
        if (element == null)
            return element
        return element.text().trim()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREFERRED_QUALITY, null)
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
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val PREFERRED_QUALITY = "preferred_quality"
        private val QUALITY_LIST = arrayOf("480p", "720p", "1080p")
        const val PREFIX_SEARCH_PATH = "path:"
        private var INITIAL_DATA: String = ""
        private var WIRE_TOKEN: String = ""
    }
}
