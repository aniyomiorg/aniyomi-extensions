package eu.kanade.tachiyomi.animeextension.it.aniplay

import eu.kanade.tachiyomi.animeextension.it.aniplay.dto.PopularResponseDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Request
import okhttp3.Response

class AniPlay : AnimeHttpSource() {

    override val name = "AniPlay"

    override val baseUrl = "https://aniplay.co"

    override val lang = "it"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) =
        GET("$API_URL/advancedSearch?sort=7&page=$page&origin=,,,,,,", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<PopularResponseDto>()
        val animes = parsed.data.map {
            SAnime.create().apply {
                url = "/series/${it.id}"
                title = it.title
                thumbnail_url = it.thumbnailUrl
            }
        }

        return AnimesPage(animes, parsed.pagination.hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        throw UnsupportedOperationException()
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/series/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
            .apply { setUrlWithoutDomain(response.request.url.toString()) }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        throw UnsupportedOperationException()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        throw UnsupportedOperationException()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException()
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        throw UnsupportedOperationException()
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val API_URL = "https://api.aniplay.co/api/series"
    }
}
