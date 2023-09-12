package eu.kanade.tachiyomi.animeextension.tr.animeler

import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.FullAnimeDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SearchRequestDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SearchResponseDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SingleDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Animeler : AnimeHttpSource() {

    override val name = "Animeler"

    override val baseUrl = "https://animeler.me"

    override val lang = "tr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = searchOrderBy("total_kiranime_views", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val results = response.parseAs<SearchResponseDto>()
        val animes = results.data.map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.url)
                thumbnail_url = it.image
                title = it.title
            }
        }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = page < results.pages
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = searchOrderBy("kiranime_anime_updated", page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
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

    override fun getFilterList() = AnimelerFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimelerFilters.getSearchParameters(filters)
        val (meta, orderBy) = when (params.orderBy) {
            "date", "title" -> Pair(null, params.orderBy)
            else -> Pair(params.orderBy, "meta_value_num")
        }

        val single = SingleDto(
            paged = page,
            key = meta,
            order = params.order,
            orderBy = orderBy,
            season = params.season.ifEmpty { null },
            year = params.year.ifEmpty { null },
        )

        val taxonomies = with(params) {
            listOf(genres, status, producers, studios, types).filter {
                it.terms.isNotEmpty()
            }
        }

        val requestDto = SearchRequestDto(single, query, query, taxonomies)
        val requestData = json.encodeToString(requestDto)
        return searchRequest(requestData, page)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val body = response.use { it.body.string() }
            .substringAfter("var anime = ")
            .substringBefore("}<") + "}"
        val animeDto = json.decodeFromString<FullAnimeDto>(body)

        setUrlWithoutDomain(animeDto.url)
        thumbnail_url = animeDto.image
        title = animeDto.title
        artist = animeDto.studios
        author = animeDto.producers
        genre = animeDto.genres
        status = when {
            animeDto.meta.aired.orEmpty().contains(" to ") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        description = buildString {
            animeDto.post.post_content?.also { append(it + "\n") }

            with(animeDto.meta) {
                score?.takeIf(String::isNotBlank)?.also { append("\nScore: $it") }
                native?.takeIf(String::isNotBlank)?.also { append("\nNative: $it") }
                synonyms?.takeIf(String::isNotBlank)?.also { append("\nDiğer İsimleri: $it") }
                rate?.takeIf(String::isNotBlank)?.also { append("\nRate: $it") }
                premiered?.takeIf(String::isNotBlank)?.also { append("\nPremiered: $it") }
                aired?.takeIf(String::isNotBlank)?.also { append("\nYayınlandı: $it") }
                duration?.takeIf(String::isNotBlank)?.also { append("\nSüre: $it") }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.use { it.body.string() }
            .substringAfter("var episodes = ")
            .substringBefore("];") + "]"

        val episodes = json.decodeFromString<List<EpisodeDto>>(body)

        return episodes.reversed().map {
            SEpisode.create().apply {
                setUrlWithoutDomain(it.url)
                name = "Bölüm " + it.meta.number
                episode_number = it.meta.number.toFloat()
                date_upload = it.date.toDate()
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        return body.string().let(json::decodeFromString)
    }

    private fun searchOrderBy(order: String, page: Int): Request {
        val body = """
            {
              "keyword": "",
              "query": "",
              "single": {
                "paged": $page,
                "orderby": "meta_value_num",
                "meta_key": "$order",
                "order": "desc"
              },
              "tax": []
            }
        """.trimIndent()
        return searchRequest(body, page)
    }

    private fun searchRequest(data: String, page: Int): Request {
        val body = data.toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/wp-json/kiranime/v1/anime/advancedsearch?_locale=user&page=$page", headers, body)
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "id:"
    }
}
