package eu.kanade.tachiyomi.animeextension.pt.animesvision

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesvision.dto.AVResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animesvision.dto.PayloadData
import eu.kanade.tachiyomi.animeextension.pt.animesvision.dto.PayloadItem
import eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors.GlobalVisionExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors.VisionExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimesVision : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesVision"

    override val baseUrl = "https://animes.vision"

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
    private fun nextPageSelector(): String = "ul.pagination li.page-item:contains(›):not(.disabled)"
    override fun popularAnimeSelector(): String = "div#anime-trending div.item > a.film-poster"
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime: SAnime = SAnime.create()
        val img = element.selectFirst("img")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = img.attr("title")
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
    override fun episodeListSelector(): String = "div.container div.screen-items > div.item"

    private fun getAllEps(response: Response): List<SEpisode> {
        val epList = mutableListOf<SEpisode>()
        val url = response.request.url.toString()
        val doc = if (url.contains("/episodio-") || url.contains("/filme-")) {
            getRealDoc(response.asJsoup())
        } else { response.asJsoup() }

        val epElementList = doc.select(episodeListSelector())
        epList.addAll(epElementList.map { episodeFromElement(it) })
        if (doc.hasNextPage()) {
            val nextUrl = doc.selectFirst(nextPageSelector())
                .selectFirst("a")
                .attr("href")
            val newResponse = client.newCall(GET(nextUrl)).execute()
            epList.addAll(getAllEps(newResponse))
        }
        return epList
    }
    override fun episodeListParse(response: Response): List<SEpisode> {
        return getAllEps(response).reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()

        episode.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        val epName = element.selectFirst("h3").text().trim()
        episode.name = epName
        episode.episode_number = try {
            epName.substringAfterLast(" ").toFloat()
        } catch (e: NumberFormatException) { 0F }
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document: Document = response.asJsoup()
        return videosFromEpisode(document)
    }

    private fun videosFromEpisode(doc: Document): List<Video> {
        val wireDiv: Element = doc.selectFirst("div[wire:id]")
        val initialData: String = wireDiv.attr("wire:initial-data")!!.dropLast(1)
        val wireToken: String = doc.html()
            .substringAfter("livewire_token")
            .substringAfter("'")
            .substringBefore("'")

        val headers = headersBuilder()
            .add("x-livewire", "true")
            .add("x-csrf-token", wireToken)
            .add("content-type", "application/json")
            .build()

        val players = doc.select("div.server-item > a.btn")

        val videos = players.mapNotNull {
            val id = it.attr("wire:click")
                .substringAfter("(")
                .substringBefore(")")
                .toInt()
            val updateItem = PayloadItem(PayloadData(listOf(id)))
            val updateString = json.encodeToString(updateItem)
            val body = "$initialData, \"updates\": [$updateString]}"
            val reqBody = body.toRequestBody()
            val url = "$baseUrl/livewire/message/components.episodio.player-episodio-component"
            val response = client.newCall(POST(url, headers, reqBody)).execute()
            val responseBody = response.body?.string().orEmpty()
            val resJson = json.decodeFromString<AVResponseDto>(responseBody)
            (resJson.serverMemo?.data?.framePlay ?: resJson.effects?.html)
                ?.let(::parsePlayerData)
        }.flatten().toMutableList()

        if ("/filmes/" in doc.location())
            parsePlayerData(doc.outerHtml())?.let { videos.addAll(it) }

        return videos
    }

    private fun parsePlayerData(data: String): List<Video>? {
        val videoList = when {
            "streamtape" in data ->
                StreamTapeExtractor(client).videoFromUrl(data)?.let(::listOf)
            "dood" in data ->
                DoodExtractor(client).videoFromUrl(data)?.let(::listOf)
            "voe.sx" in data ->
                VoeExtractor(client).videoFromUrl(data)?.let(::listOf)
            "<div" in data ->
                if ("const playerGlobalVideo" in data)
                    GlobalVisionExtractor().videoListFromHtml(data)
                else VisionExtractor().videoFromHtml(data)?.let(::listOf)
            else -> null
        } as List<Video>?
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime: SAnime = SAnime.create()
        val elementA = element.selectFirst("a")
        anime.title = elementA.attr("title")
        anime.setUrlWithoutDomain(elementA.attr("href"))
        anime.thumbnail_url = element.selectFirst("img").attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = nextPageSelector()

    override fun searchAnimeSelector(): String = "div.film_list-wrap div.film-poster"

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByPathParse(response, path)
                }
        } else {
            val params = AVFilters.getSearchParameters(filters)
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

    private fun searchAnimeRequest(page: Int, query: String, filters: AVFilters.FilterSearchParams): Request {
        val url = "$baseUrl/search?".toHttpUrlOrNull()!!.newBuilder()
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("nome", query)
        url.addQueryParameter("tipo", filters.type)
        url.addQueryParameter("idioma", filters.language)
        url.addQueryParameter("ordenar", filters.sort)
        url.addQueryParameter("ano_inicial", filters.initial_year)
        url.addQueryParameter("ano_final", filters.last_year)
        url.addQueryParameter("fansub", filters.fansub)
        url.addQueryParameter("status", filters.status)
        url.addQueryParameter("temporada", filters.season)
        url.addQueryParameter("estudios", filters.studio)
        url.addQueryParameter("produtores", filters.producer)
        url.addQueryParameter("generos", filters.genres)

        return GET(url.build().toString())
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)

        val content = doc.selectFirst("div#ani_detail div.anis-content")
        val detail = content.selectFirst("div.anisc-detail")
        val infos = content.selectFirst("div.anisc-info")

        anime.thumbnail_url = content.selectFirst("img").attr("src")
        anime.title = detail.selectFirst("h2.film-name").text()
        anime.genre = infos.getInfo("Gêneros")
        anime.author = infos.getInfo("Produtores")
        anime.artist = infos.getInfo("Estúdios")
        anime.status = parseStatus(infos.getInfo("Status"))

        var desc = infos.getInfo("Sinopse") + "\n"
        infos.getInfo("Inglês")?.let { desc += "\nTítulo em inglês: $it" }
        infos.getInfo("Japonês")?.let { desc += "\nTítulo em japonês: $it" }
        infos.getInfo("Foi")?.let { desc += "\nFoi ao ar em: $it" }
        infos.getInfo("Temporada")?.let { desc += "\nTemporada: $it" }
        infos.getInfo("Duração")?.let { desc += "\nDuração: $it" }
        infos.getInfo("Fansub")?.let { desc += "\nFansub: $it" }
        anime.description = desc

        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = nextPageSelector()
    override fun latestUpdatesSelector(): String = episodeListSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        anime.title = element.selectFirst("h3").text()
        anime.thumbnail_url = element.selectFirst("img").attr("src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/lancamentos?page=$page")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREFERRED_QUALITY
            title = "Qualidade preferida (Válido apenas no GlobalVision)"
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

    override fun getFilterList(): AnimeFilterList = AVFilters.filterList

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val player = document.selectFirst("div.player-frame")
        if (player != null) {
            val url = document.selectFirst("h2.film-name > a").attr("href")
            val req = client.newCall(GET(url)).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Fim da exibição" -> SAnime.COMPLETED
            "Atualmente sendo exibido" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.hasNextPage(): Boolean =
        this.selectFirst(nextPageSelector()) != null

    private fun Element.getInfo(key: String): String? {
        val div: Element? = this.selectFirst("div.item:contains($key)")
        if (div == null) return div
        val elementsA = div.select("a[href]")
        val text = if (elementsA.size == 0) {
            if (div.hasClass("w-hide")) {
                div.selectFirst("div.text").text().trim()
            } else {
                div.selectFirst("span.name").text().trim()
            }
        } else {
            elementsA.map { it.text().trim() }.joinToString(", ")
        }
        if (text == "") return null
        return text
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREFERRED_QUALITY, null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (quality in video.quality) {
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
        private val QUALITY_LIST = arrayOf("480p", "720p", "1080p", "4K")
        const val PREFIX_SEARCH = "path:"
    }
}
