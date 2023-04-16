package eu.kanade.tachiyomi.animeextension.en.kickassanime

import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.AnimeInfoDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.PopularItemDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.PopularResponseDto
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.RecentsResponseDto
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

class KickAssAnime : AnimeHttpSource() {

    override val name = "KickAssAnime"

    override val baseUrl = "https://kaas.am"

    private val API_URL = "$baseUrl/api/show"

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$API_URL/popular?page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<PopularResponseDto>()
        val animes = data.result.map(::popularAnimeFromObject)
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 0
        val hasNext = data.page_count > page
        return AnimesPage(animes, hasNext)
    }

    private fun popularAnimeFromObject(anime: PopularItemDto): SAnime {
        return SAnime.create().apply {
            title = anime.title
            setUrlWithoutDomain("/${anime.slug}")
            thumbnail_url = "$baseUrl/${anime.poster.url}"
        }
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
    override fun animeDetailsRequest(anime: SAnime) = GET("$API_URL/${anime.url}")

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.parseAs<AnimeInfoDto>()
        return SAnime.create().apply {
            title = anime.title
            setUrlWithoutDomain("/${anime.slug}")
            thumbnail_url = "$baseUrl/${anime.poster.url}"
            genre = anime.genres.joinToString()
            status = anime.status.parseStatus()
            description = buildString {
                append(anime.synopsis + "\n\n")
                append("Season: ${anime.season.capitalize()}\n")
                append("Year: ${anime.year}")
            }
        }
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
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$slug"))
                .asObservableSuccess()
                .map(::searchAnimeBySlugParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeBySlugParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        details.setUrlWithoutDomain(response.request.url.toString())
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<RecentsResponseDto>()
        val animes = data.result.map(::popularAnimeFromObject)
        return AnimesPage(animes, data.hadNext)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$API_URL/recent?type=all&page=$page")

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        return body.string().let(json::decodeFromString)
    }

    private fun String.parseStatus() = when (this) {
        "finished_airing" -> SAnime.COMPLETED
        "currently_airing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"
    }
}
