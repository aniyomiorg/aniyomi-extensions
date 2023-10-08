package eu.kanade.tachiyomi.animeextension.en.myrunningman

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class MyRunningMan : ParsedAnimeHttpSource() {

    override val name = "My Running Man"

    override val baseUrl = "https://www.myrunningman.com"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/episodes/mostwatched/$page")

    override fun popularAnimeSelector() = "table > tbody > tr"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("p > strong > a")!!.run {
            title = text()
            setUrlWithoutDomain(attr("href"))
        }

        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector() = "li > a[aria-label=Next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episodes/newest/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/ep/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/_search.php?q=$query", headersBuilder().add("X-Requested-With", "XMLHttpRequest").build())

    @Serializable
    data class ResultDto(val value: String, val label: String)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val animes = response.parseAs<List<ResultDto>>().map {
            SAnime.create().apply {
                url = "/ep/" + it.value
                title = it.label
                thumbnail_url = buildString {
                    append("$baseUrl/assets/epimg/${it.value.padStart(3, '0')}")
                    if ((it.value.toIntOrNull() ?: 1) > 396) append("_temp")
                    append(".jpg")
                }
            }
        }

        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("div.container h1")!!.text()

        val row = document.selectFirst("div.row")!!
        thumbnail_url = row.selectFirst("p > img")?.absUrl("src")
        artist = row.select("li > a[href*=guest/]").eachText().joinToString().takeIf(String::isNotBlank)
        genre = row.select("li > a[href*=tag/]").eachText().joinToString().takeIf(String::isNotBlank)

        description = row.select("p:has(i.fa)").eachText().joinToString("\n") {
            when {
                it.startsWith("Watches") || it.startsWith("Faves") -> it.substringBefore(" (")
                else -> it
            }
        }

        status = when (document.selectFirst("div.alert:contains(Coming soon)")) {
            null -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { it.asJsoup() }
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                name = doc.selectFirst("div.container h1")!!.text()
                episode_number = doc.selectFirst("div#userepoptions")
                    ?.attr("data-ep")
                    ?.toFloatOrNull()
                    ?: 1F
                date_upload = doc.selectFirst("p:contains(Broadcast Date)")
                    ?.text()
                    ?.substringAfter(": ")
                    ?.substringBefore(" ")
                    ?.toDate()
                    ?: 0L
            },
        )
    }

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        return use { it.body.string() }.let(json::decodeFromString)
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
