package eu.kanade.tachiyomi.animeextension.pt.animefire

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animefire.extractors.AnimeFireExtractor
import eu.kanade.tachiyomi.animeextension.pt.animefire.extractors.IframeExtractor
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

class AnimeFire : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime Fire"

    override val baseUrl = "https://animefire.net"

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
        .add("Accept-Language", AFConstants.ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = latestUpdatesSelector()
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/top-animes/$page")
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.div_video_list > a"
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val url = element.attr("href")
        episode.setUrlWithoutDomain(url)
        episode.name = element.text()
        episode.episode_number = try {
            url.substringAfterLast("/").toFloat()
        } catch (e: NumberFormatException) { 0F }
        return episode
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document: Document = response.asJsoup()
        val videoElement = document.selectFirst("video#my-video")
        return if (videoElement != null) {
            AnimeFireExtractor(client, json).videoListFromElement(videoElement)
        } else {
            IframeExtractor(client).videoListFromDocument(document)
        }
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun searchAnimeSelector() = latestUpdatesSelector()
    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(AFConstants.PREFIX_SEARCH)) {
            val id = query.removePrefix(AFConstants.PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$id"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, id)
                }
        } else {
            val params = AFFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/animes/$id"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AFFilters.FilterSearchParams): Request {
        if (query.isBlank()) {
            return when {
                filters.season.isNotBlank() -> GET("$baseUrl/temporada/${filters.season}/$page")
                else -> GET("$baseUrl/genero/${filters.genre}/$page")
            }
        }
        val fixedQuery = query.trim().replace(" ", "-").lowercase()
        return GET("$baseUrl/pesquisar/$fixedQuery/$page")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val content = document.selectFirst("div.divDivAnimeInfo")
        val names = content.selectFirst("div.div_anime_names")
        val infos = content.selectFirst("div.divAnimePageInfo")
        anime.thumbnail_url = content.selectFirst("div.sub_animepage_img > img")
            .attr("data-src")
        anime.title = names.selectFirst("h1").text()
        anime.genre = infos.select("a.spanGeneros").joinToString(", ") { it.text() }
        anime.author = infos.getInfo("Estúdios")
        anime.status = parseStatus(infos.getInfo("Status"))

        var desc = content.selectFirst("div.divSinopse > span").text() + "\n"
        names.selectFirst("h6")?.let { desc += "\nNome alternativo: ${it.text()}" }
        infos.getInfo("Dia de")?.let { desc += "\nDia de lançamento: $it" }
        infos.getInfo("Áudio")?.let { desc += "\nTipo: $it" }
        infos.getInfo("Ano")?.let { desc += "\nAno: $it" }
        infos.getInfo("Episódios")?.let { desc += "\nEpisódios: $it" }
        infos.getInfo("Temporada")?.let { desc += "\nTemporada: $it" }
        anime.description = desc

        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "ul.pagination img.seta-right"
    override fun latestUpdatesSelector(): String = "article.cardUltimosEps > a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val url = element.attr("href")

        if (url.substringAfterLast("/").toIntOrNull() != null) {
            val newUrl = url.substringBeforeLast("/") + "-todos-os-episodios"
            anime.setUrlWithoutDomain(newUrl)
        } else { anime.setUrlWithoutDomain(url) }

        anime.title = element.selectFirst("h3.animeTitle").text()
        anime.thumbnail_url = element.selectFirst("img").attr("data-src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home/$page")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = AFConstants.PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = AFConstants.QUALITY_LIST
            entryValues = AFConstants.QUALITY_LIST
            setDefaultValue(AFConstants.QUALITY_LIST.last())
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

    override fun getFilterList(): AnimeFilterList = AFFilters.filterList

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            "Em lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(key: String): String? {
        val div = this.selectFirst("div.animeInfo:contains($key)")
        if (div == null) return div
        val span = div.selectFirst("span")
        return span.text()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(AFConstants.PREFERRED_QUALITY, null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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
}
