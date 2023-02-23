package eu.kanade.tachiyomi.animeextension.pt.animestc

import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.ResponseDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class AnimesTC : AnimeHttpSource() {

    override val name = "AnimesTC"

    override val baseUrl = "https://wwww.animestc.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        TODO("Not yet implemented")
    }

    override fun popularAnimeRequest(page: Int): Request {
        TODO("Not yet implemented")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        TODO("Not yet implemented")
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        TODO("Not yet implemented")
    }

    override fun videoListParse(response: Response): List<Video> {
        TODO("Not yet implemented")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        TODO("Not yet implemented")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$id"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, id)
                }
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/animes/$id"
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsed = response.parseAs<ResponseDto<EpisodeDto>>()
        val hasNextPage = parsed.page < parsed.lastPage
        val animes = parsed.items.map {
            SAnime.create().apply {
                title = it.title
                url = "$API_URL/series/${it.animeId}"
                thumbnail_url = it.cover.url
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$API_URL/episodes?order=created_at&direction=desc&page=$page&ignoreIndex=false")
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val API_URL = "https://api2.animestc.com"
    }
}
