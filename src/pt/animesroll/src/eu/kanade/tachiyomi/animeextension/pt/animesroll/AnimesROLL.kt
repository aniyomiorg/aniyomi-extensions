package eu.kanade.tachiyomi.animeextension.pt.animesroll

import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.AnimeDataDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.EpisodeListDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.LatestAnimeDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.MovieInfoDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.PagePropDto
import eu.kanade.tachiyomi.animeextension.pt.animesroll.dto.SearchResultsDto
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

class AnimesROLL : AnimeHttpSource() {

    override val name = "AnimesROLL"

    override val baseUrl = "https://www.anroll.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ============================== Popular ===============================
    // The site doesn't have a popular anime tab, so we use the home page instead (latest anime).
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)
    override fun popularAnimeParse(response: Response) = latestUpdatesParse(response)

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val originalUrl = response.request.url.toString()
        return if ("/f/" in originalUrl) {
            val od = response.asJsoup().parseAs<MovieInfoDto>().movieData.od
            SEpisode.create().apply {
                url = "$OLD_API_URL/od/$od/filme.mp4"
                name = "Filme"
                episode_number = 0F
            }.let(::listOf)
        } else {
            val anime = response.asJsoup().parseAs<AnimeDataDto>()
            val urlStart = "https://cdn-01.gamabunta.xyz/hls/animes/${anime.slug}"

            return fetchEpisodesRecursively(anime.id).map { episode ->
                SEpisode.create().apply {
                    val epNum = episode.episodeNumber
                    name = "Episódio #$epNum"
                    episode_number = epNum.toFloat()
                    url = "$urlStart/$epNum.mp4/media-1/stream.m3u8"
                }
            }
        }
    }

    private fun fetchEpisodesRecursively(animeId: String, page: Int = 1): List<EpisodeDto> {
        val response = client.newCall(episodeListRequest(animeId, page))
            .execute()
            .parseAs<EpisodeListDto>()

        return response.episodes.let { episodes ->
            when {
                response.meta.totalOfPages > page ->
                    episodes + fetchEpisodesRecursively(animeId, page + 1)
                else -> episodes
            }
        }
    }

    private fun episodeListRequest(animeId: String, page: Int) =
        GET("$NEW_API_URL/animes/$animeId/episodes?page=$page%order=desc")

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
        val anime = when {
            response.request.url.toString().contains("/f/") ->
                doc.parseAs<MovieInfoDto>().movieData
            else -> doc.parseAs<AnimeDataDto>()
        }
        return anime.toSAnime().apply {
            author = if (anime.director != "0") anime.director else null
            var desc = anime.description.ifNotEmpty { it + "\n" }
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

    private fun searchAnimeByPathParse(response: Response, path: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/$path"
        return AnimesPage(listOf(details), false)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByPathParse(response, path)
                }
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }
    override fun searchAnimeParse(response: Response): AnimesPage {
        val results = response.parseAs<SearchResultsDto>()
        val animes = (results.animes + results.movies).map { it.toSAnime() }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$OLD_API_URL/search?q=$query")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val parsed = response.asJsoup().parseAs<LatestAnimeDto>()
        val animes = parsed.episodes.map { it.episode.anime!!.toSAnime() }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos")

    // ============================= Utilities ==============================

    private inline fun <reified T> Document.parseAs(): T {
        val nextData = this.selectFirst("script#__NEXT_DATA__")!!
            .data()
            .substringAfter(":")
            .substringBeforeLast(",\"page\"")
        return json.decodeFromString<PagePropDto<T>>(nextData).data
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = body.string()
        return json.decodeFromString(responseBody)
    }

    private fun String.ifNotEmpty(block: (String) -> String): String {
        return if (isNotEmpty() && this != "0") block(this) else ""
    }

    fun AnimeDataDto.toSAnime(): SAnime {
        return SAnime.create().apply {
            val ismovie = slug == ""
            url = if (ismovie) "/f/$id" else "/anime/$slug"
            thumbnail_url = "https://static.anroll.net/images/".let {
                if (ismovie) {
                    it + "filmes/capas/$slug_movie.jpg"
                } else {
                    it + "animes/capas/$slug.jpg"
                }
            }
            title = anititle
        }
    }

    companion object {
        private const val OLD_API_URL = "https://apiv2-prd.anroll.net"
        private const val NEW_API_URL = "https://apiv3-prd.anroll.net"

        const val PREFIX_SEARCH = "path:"
    }
}
