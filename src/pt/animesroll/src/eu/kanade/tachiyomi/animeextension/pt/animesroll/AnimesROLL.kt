package eu.kanade.tachiyomi.animeextension.pt.animesroll

import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.AnimeDataDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.AnimeInfoDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.LatestAnimeDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.PagePropDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class AnimesROLL : AnimeHttpSource() {

    override val name = "AnimesROLL"

    override val baseUrl = "https://www.anroll.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder().add("Referer", baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val API_URL by lazy {
        val home = client.newCall(GET(baseUrl)).execute()
        val body = home.body?.string().orEmpty()
        val buildId = body.substringAfter("\"buildId\":")
            .substringAfter('"')
            .substringBefore('"')
        "$baseUrl/_next/data/$buildId"
    }

    private val NEW_API_URL = "https://apiv2-prd.anroll.net"

    // ============================== Popular ===============================
    // The site doesn't have a popular anime tab, so we use the home page instead (latest anime).
    override fun popularAnimeRequest(page: Int) = GET("$API_URL/index.json")
    override fun popularAnimeParse(response: Response) = latestUpdatesParse(response)

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val originalUrl = response.request.url.toString()
        return if ("/f/" in originalUrl) {
            val od = response.body?.string().orEmpty()
                .substringAfter("\"od\":")
                .substringAfter('"')
                .substringBefore('"')

            val episode = SEpisode.create().apply {
                url = "$NEW_API_URL/od/$od/filme.mp4"
                name = "Filme"
                episode_number = 0F
            }
            listOf(episode)
        } else {
            val id = originalUrl.substringAfter("/a/").substringBefore("/")
            val epdata = client.newCall(GET("$NEW_API_URL/a/$id"))
                .execute()
                .parseAs<EpisodeDto>()

            val urlStart = "https://cdn-01.animesroll.com/hls/animes/${epdata.anime.slug}"

            (epdata.total_ep downTo 1).map {
                SEpisode.create().apply {
                    episode_number = it.toFloat()
                    val fixedNum = it.toString().padStart(3, '0')
                    name = "Episódio #$fixedNum"
                    url = "$urlStart/$fixedNum.mp4/media-1/stream.m3u8"
                }
            }
        }
    }
    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val epUrl = episode.url
        return Observable.just(listOf(Video(epUrl, "default", epUrl)))
    }

    override fun videoListRequest(episode: SEpisode): Request {
        TODO("Not yet implemented")
    }

    override fun videoListParse(response: Response): List<Video> {
        TODO("Not yet implemented")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val nextData = doc.selectFirst("script#__NEXT_DATA__")
            .data()
            .substringAfter(":")
            .substringBeforeLast(",\"page\"")
        val anime = json.decodeFromString<PagePropDto<AnimeInfoDto>>(nextData).data.animeData
        return anime.toSAnime().apply {
            author = anime.director
            var desc = anime.description + "\n"
            desc += anime.duration.ifNotEmpty { "\nDuração: $it" }
            desc += anime.animeCalendar?.let {
                it.ifNotEmpty { "\nLança toda(o) $it" }
            } ?: ""
            description = desc
            genre = doc.select("div#generos > a").joinToString(", ") { it.text() }
            status = if (anime.animeCalendar == null) SAnime.COMPLETED else SAnime.ONGOING
        }
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
        val animes = parsed.data.animes.map { it.toSAnime() }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$API_URL/lancamentos.json")

    // ============================= Utilities ==============================

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body?.string().orEmpty()
        return json.decodeFromString(responseBody)
    }

    private fun String.ifNotEmpty(block: (String) -> String): String {
        return if (isNotEmpty() && this != "0") block(this) else this
    }

    fun AnimeDataDto.toSAnime(): SAnime {
        return SAnime.create().apply {
            val ismovie = slug == ""
            url = if (ismovie) "/f/$id" else "/anime/$slug"
            thumbnail_url = "https://static.anroll.net/images/".let {
                if (ismovie) it + "filmes/capas/$slug_movie.jpg"
                else it + "animes/capas/$slug.jpg"
            }
            title = anititle
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
