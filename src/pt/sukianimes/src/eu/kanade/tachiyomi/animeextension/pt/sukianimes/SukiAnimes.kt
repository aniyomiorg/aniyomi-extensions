package eu.kanade.tachiyomi.animeextension.pt.sukianimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get
import kotlin.Exception

class SukiAnimes : ParsedAnimeHttpSource() {

    override val name = "SukiAnimes"

    override val baseUrl = "https://sukianimes.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = throw Exception("not used")
    override fun popularAnimeRequest(page: Int) = throw Exception("not used")
    override fun popularAnimeFromElement(element: Element) = throw Exception("not used")
    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = throw Exception("not used")
    override fun episodeListParse(response: Response) = throw Exception("not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")
    // ============================ Video Links =============================
    override fun videoListParse(response: Response) = throw Exception("not used")

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")
    override fun searchAnimeSelector() = throw Exception("not used")
    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = throw Exception("not used")

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.paginacao > a.next"
    override fun latestUpdatesSelector() = "div.epiItem > div.epiImg > a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img").attr("src")
        }
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/lista-de-episodios/page/$page/")
}
