package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class AnimesDigital : ParsedAnimeHttpSource() {

    override val name = "Animes Digital"

    override val baseUrl = "https://animesdigital.org"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector() = null
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)
    override fun popularAnimeSelector() = latestUpdatesSelector()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.asJsoup())
        return doc.select(episodeListSelector()).map(::episodeFromElement)
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epname = element.selectFirst("div.episode")!!.text()
        episode_number = epname.substringAfterLast(" ").toFloatOrNull() ?: 1F
        name = buildString {
            append(epname)
            element.selectFirst("div.sub_title")?.text()?.let {
                if (!it.contains("Ainda não tem um titulo oficial")) {
                    append(" - $it")
                }
            }
        }
    }

    override fun episodeListSelector() = "div.item_ep > a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())
        thumbnail_url = doc.selectFirst("div.poster > img")!!.attr("data-lazy-src")
        status = when (doc.selectFirst("div.clw > div.playon")?.text()) {
            "Em Lançamento" -> SAnime.ONGOING
            "Completo" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val infos = doc.selectFirst("div.crw > div.dados")!!

        artist = infos.getInfo("Estúdio")
        author = infos.getInfo("Autor") ?: infos.getInfo("Diretor")

        title = infos.selectFirst("h1")!!.text()
        genre = infos.select("div.genre a").eachText().joinToString()

        description = infos.selectFirst("div.sinopse")?.text()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val player = response.asJsoup().selectFirst("div#player")!!
        return player.select("div.tab-video").flatMap {
            it.select(videoListSelector()).flatMap { element ->
                runCatching {
                    videosFromElement(element)
                }.getOrElse { emptyList() }
            }
        }
    }

    private fun videosFromElement(element: Element): List<Video> {
        return when (element.tagName()) {
            "iframe" -> {
                val url = element.attr("data-lazy-src").ifEmpty { element.attr("src") }
                    .let {
                        when {
                            it.startsWith("/") -> baseUrl + it
                            else -> it
                        }
                    }
                client.newCall(GET(url, headers)).execute()
                    .asJsoup()
                    .select(videoListSelector())
                    .flatMap(::videosFromElement)
            }
            "script" -> {
                val scriptData = element.data().let {
                    when {
                        "eval(function" in it -> JsUnpacker.unpackAndCombine(it)
                        else -> it
                    }
                }?.replace("\\", "")
                scriptData?.let(::videosFromScript) ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun videosFromScript(script: String): List<Video> {
        return script.substringAfter("sources:").substringAfter(".src(")
            .substringBefore(")")
            .substringAfter("[")
            .substringBefore("]")
            .split("{")
            .drop(1)
            .map {
                val quality = it.substringAfter("label", "")
                    .substringAfterKey()
                    .ifEmpty { name }
                val url = it.substringAfter("file").substringAfter("src")
                    .substringAfterKey()
                Video(url, quality, url, headers)
            }
    }

    override fun videoListSelector() = "iframe, script:containsData(eval), script:containsData(player.src), script:containsData(this.src), script:containsData(sources:)"

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("data-lazy-src")
        title = element.selectFirst("span.title_anime")!!.text()
    }

    override fun latestUpdatesNextPageSelector() = "ul > li.next"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page")

    override fun latestUpdatesSelector() = "div.b_flex:nth-child(2) > div.itemE > a"

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("div.subitem > a:contains(menu)")?.let { link ->
            client.newCall(GET(link.attr("href")))
                .execute()
                .asJsoup()
        } ?: document
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("div.info:has(span:containsOwn($key))")
            ?.ownText()
            ?.trim()
            ?.let {
                when (it) {
                    "", "?" -> null
                    else -> it
                }
            }
    }

    private fun String.substringAfterKey() = substringAfter(":")
        .substringAfter('"')
        .substringBefore('"')
        .substringAfter("'")
        .substringBefore("'")

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
