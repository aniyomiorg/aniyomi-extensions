package eu.kanade.tachiyomi.animeextension.sr.animesrbija

import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.PagePropsDto
import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.SearchPageDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.injectLazy

class AnimeSrbija : AnimeHttpSource() {

    override val name = "Anime Srbija"

    override val baseUrl = "https://www.animesrbija.com"

    override val lang = "sr"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.parseAs<SearchPageDto>().anime.map {
            SAnime.create().apply {
                setUrlWithoutDomain("/anime/${it.slug}")
                thumbnail_url = baseUrl + it.imgPath
                title = it.title
            }
        }

        val hasNextPage = doc.selectFirst("ul.pagination span.next-page:not(.disabled)") != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/filter?sort=popular&page=$page")

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
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
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Document.parseAs(): T {
        val nextData = selectFirst("script#__NEXT_DATA__")!!
            .data()
            .substringAfter(":")
            .substringBeforeLast("},\"page\"") + "}"
        return json.decodeFromString<PagePropsDto<T>>(nextData).data
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
