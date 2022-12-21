package eu.kanade.tachiyomi.animeextension.pt.sukianimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
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
    // This source doesn't have a popular anime page, so we'll grab 
    // the latest anime additions instead.
    override fun popularAnimeSelector() = "section.animeslancamentos div.aniItem > a"
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)
    override fun popularAnimeNextPageSelector() = null // disable it
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img").attr("src")
        }
    }

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
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            thumbnail_url = doc.selectFirst("div.animeimgleft > img").attr("src")
            val section = doc.selectFirst("section.rightnew")
            val titleSection = section.selectFirst("section.anime_titulo")
            title = titleSection.selectFirst("h1").text()
            status = parseStatus(titleSection.selectFirst("div.anime_status"))
            genre = titleSection.select("div.anime_generos > span")
                .joinToString(", ") { it.text() }

            var desc = doc.selectFirst("span#sinopse_content").text()
            desc += "\n\n" + section.select("div.anime_info").joinToString("\n") {
                val key = it.selectFirst("span.anime_info_title").text()
                val value = it.selectFirst("span.anime_info_content").text()
                "$key: $value"
            }
            description = desc
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.paginacao > a.next"
    override fun latestUpdatesSelector() = "div.epiItem > div.epiImg > a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img").attr("src")
        }
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/lista-de-episodios/page/$page/")

    // ============================= Utilities ==============================

    private fun getRealDoc(doc: Document): Document {
        val controls = doc.selectFirst("div.episodioControles")
        if (controls != null) {
            val newUrl = controls.select("a").get(1)!!.attr("href")
            val res = client.newCall(GET(newUrl)).execute()
            return res.asJsoup()
        } else {
            return doc
        }
    }

    private fun parseStatus(element: Element?): Int {
        return when (element?.text()?.trim()) {
            "Em Lançamento" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }
}
