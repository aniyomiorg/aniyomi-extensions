package eu.kanade.tachiyomi.animeextension.pt.animesroll

import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.LatestAnimeDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.PagePropDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

class AnimesROLL : AnimeHttpSource() {

    override val name = "AnimesROLL"

    override val baseUrl = "https://www.anroll.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val API_URL by lazy {
        val home = client.newCall(GET(baseUrl)).execute()
        val body = home.body?.string().orEmpty()
        val buildId = body.substringAfter("\"buildId\":")
            .substringAfter('"')
            .substringBefore('"')
        "$baseUrl/_next/data/$buildId"
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

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsed = response.parseAs<PagePropDto<LatestAnimeDto>>()
        val animes = parsed.data.animes.map {
            SAnime.create().apply {
                setUrlWithoutDomain("/anime/${it.slug}")
                thumbnail_url = "https://static.anroll.net/images/animes/capas/${it.slug}.jpg"
                title = it.title
            }
        }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$API_URL/lancamentos.json")

    // ============================= Utilities ==============================

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
