package eu.kanade.tachiyomi.animeextension.it.aniplay

import eu.kanade.tachiyomi.animeextension.it.aniplay.dto.AnimeInfoDto
import eu.kanade.tachiyomi.animeextension.it.aniplay.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.it.aniplay.dto.LatestItemDto
import eu.kanade.tachiyomi.animeextension.it.aniplay.dto.PopularAnimeDto
import eu.kanade.tachiyomi.animeextension.it.aniplay.dto.PopularResponseDto
import eu.kanade.tachiyomi.animeextension.it.aniplay.dto.VideoDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class AniPlay : AnimeHttpSource() {

    override val name = "AniPlay"

    override val baseUrl = "https://aniplay.co"

    override val lang = "it"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("origin", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) =
        GET("$API_URL/advancedSearch?sort=7&page=$page&origin=,,,,,,", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<PopularResponseDto>()
        val animes = parsed.data.map(PopularAnimeDto::toSAnime)
        return AnimesPage(animes, parsed.pagination.hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$API_URL/latest-episodes?page=$page&type=All")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val items = response.parseAs<List<LatestItemDto>>()
        val animes = items.mapNotNull { it.serie.firstOrNull()?.toSAnime() }
        return AnimesPage(animes, items.size == 20)
    }

    // =============================== Search ===============================
    override fun getFilterList() = AniPlayFilters.FILTER_LIST

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
        val params = AniPlayFilters.getSearchParameters(filters)
        val url = "$API_URL/advancedSearch".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("origin", ",,,,,,")
            .addQueryParameter("sort", params.order)
            .addIfNotBlank("_q", query)
            .addIfNotBlank("genres", params.genres)
            .addIfNotBlank("country", params.countries)
            .addIfNotBlank("types", params.types)
            .addIfNotBlank("studios", params.studios)
            .addIfNotBlank("status", params.status)
            .addIfNotBlank("subbed", params.languages)
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val script = response.getPageScript()
        val jsonString = script.substringAfter("{serie:").substringBefore(",tags") + "}"
        val parsed = jsonString.fixJsonString().parseAs<AnimeInfoDto>()

        title = parsed.title
        genre = parsed.genres.joinToString { it.name }
        artist = parsed.studios.joinToString { it.name }
        thumbnail_url = parsed.thumbnailUrl
        status = when (parsed.status) {
            "Completato" -> SAnime.COMPLETED
            "In corso" -> SAnime.ONGOING
            "Sospeso" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }

        description = buildString {
            parsed.description?.also {
                append(it, "\n\n")
            }

            listOf(
                "Titolo Alternativo" to parsed.alternative,
                "Origine" to parsed.origin,
                "Giorno di lancio" to parsed.release_day,
            ).forEach { (title, value) ->
                if (value != null) append(title, ": ", value, "\n")
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val script = response.getPageScript()
        val jsonString = script.substringAfter(",episodes:").substringBefore("]},") + "]"
        val parsed = jsonString.fixJsonString().parseAs<List<EpisodeDto>>()

        return parsed.map {
            SEpisode.create().apply {
                episode_number = it.number?.toFloatOrNull() ?: 1F
                url = "/watch/${it.id}"
                name = it.title ?: "Episodio ${it.number}"
                date_upload = it.release_date.toDate()
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val script = response.getPageScript()
        val jsonString = script.substringAfter("{episode:").substringBefore(",views") + "}"
        val videoUrl = jsonString.fixJsonString().parseAs<VideoDto>().videoLink

        return when {
            videoUrl.contains(".m3u8") -> playlistUtils.extractFromHls(videoUrl)
            else -> listOf(Video(videoUrl, "Default", videoUrl, headers = headers))
        }
    }

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String) = apply {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
    }

    // {key:"value"} -> {"key":"value"}
    private fun String.fixJsonString() = replace(WRONG_KEY_REGEX) {
        "\"${it.groupValues[1]}\":${it.groupValues[2]}"
    }

    private fun Response.getPageScript() =
        asJsoup().selectFirst("script:containsData(const data = )")!!.data()

    private fun String?.toDate(): Long {
        if (this == null) return 0L
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val API_URL = "https://api.aniplay.co/api/series"

        private val WRONG_KEY_REGEX by lazy { Regex("([a-zA-Z_]+):\\s?([\"|0-9|f|t|n|\\[|\\{])") }

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
