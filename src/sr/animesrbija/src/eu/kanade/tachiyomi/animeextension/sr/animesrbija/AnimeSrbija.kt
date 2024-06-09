package eu.kanade.tachiyomi.animeextension.sr.animesrbija

import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.AnimeDetailsDto
import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.EpisodeVideo
import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.EpisodesDto
import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.LatestUpdatesDto
import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.PagePropsDto
import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.SearchAnimeDto
import eu.kanade.tachiyomi.animeextension.sr.animesrbija.dto.SearchPageDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

class AnimeSrbija : AnimeHttpSource() {

    override val name = "Anime Srbija"

    override val baseUrl = "https://www.animesrbija.com"

    override val lang = "sr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.parseAs<SearchPageDto>().anime.map(::parseAnime)

        val hasNextPage = doc.selectFirst("ul.pagination span.next-page:not(.disabled)") != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/filter?sort=popular&page=$page")

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.asJsoup().parseAs<EpisodesDto>()
        return data.episodes.map {
            SEpisode.create().apply {
                setUrlWithoutDomain("/epizoda/${it.slug}")
                name = "Epizoda ${it.number}"
                episode_number = it.number.toFloat()
                if (it.filler) scanlator = "filler"
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val links = response.asJsoup().parseAs<EpisodeVideo>().links
        return links.flatMap(::getVideosFromURL)
    }

    private fun getVideosFromURL(url: String): List<Video> {
        val trimmedUrl = url.trim('!')
        return runCatching {
            when {
                "filemoon" in trimmedUrl ->
                    FilemoonExtractor(client).videosFromUrl(trimmedUrl)
                ".m3u8" in trimmedUrl ->
                    listOf(Video(trimmedUrl, "Internal Player", trimmedUrl))
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.asJsoup().parseAs<AnimeDetailsDto>().anime

        return SAnime.create().apply {
            setUrlWithoutDomain("/anime/${anime.slug}")
            thumbnail_url = baseUrl + anime.imgPath
            title = anime.title
            status = when (anime.status) {
                "Završeno" -> SAnime.COMPLETED
                "Emituje se" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            artist = anime.studios.joinToString()
            genre = anime.genres.joinToString()

            description = buildString {
                anime.season?.let { append("Sezona: $it\n") }
                anime.aired?.let { append("Datum: $it\n") }
                anime.subtitle?.let { append("Alternativni naziv: $it\n") }
                anime.desc?.let { append("\n\n$it") }
            }
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList() = AnimeSrbijaFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeSrbijaFilters.getSearchParameters(filters)
        val url = buildString {
            append("$baseUrl/filter?page=$page&sort=${params.sortby}")
            if (query.isNotBlank()) append("&search=$query")
            params.parsedCheckboxes.forEach {
                if (it.isNotBlank()) append("&$it")
            }
        }

        return GET(url)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.asJsoup().parseAs<LatestUpdatesDto>()
        val animes = data.animes.map(::parseAnime)
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    // ============================= Utilities ==============================
    private inline fun <reified T> Document.parseAs(): T {
        val nextData = selectFirst("script#__NEXT_DATA__")!!
            .data()
            .substringAfter(":")
            .substringBeforeLast("},\"page\"") + "}"
        return json.decodeFromString<PagePropsDto<T>>(nextData).data
    }

    private fun parseAnime(item: SearchAnimeDto): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain("/anime/${item.slug}")
            thumbnail_url = baseUrl + item.imgPath
            title = item.title
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
