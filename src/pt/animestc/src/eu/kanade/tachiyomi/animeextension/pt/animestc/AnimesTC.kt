package eu.kanade.tachiyomi.animeextension.pt.animestc

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.ResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.LinkBypasser
import eu.kanade.tachiyomi.animeextension.pt.animestc.extractors.SendcmExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AnimesTC : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimesTC"

    override val baseUrl = "https://api2.animestc.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$HOST_URL/")

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series?order=id&direction=asc&page=1&top=true", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<List<AnimeDto>>()
        val animes = data.map(::searchAnimeFromObject)
        return AnimesPage(animes, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(HOST_URL, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div > article.episode").map {
            SAnime.create().apply {
                val ahref = it.selectFirst("h3 > a.episode-info-title-orange")!!
                title = ahref.text()
                val slug = ahref.attr("href").substringAfterLast("/")
                setUrlWithoutDomain("/series?slug=$slug")
                thumbnail_url = it.selectFirst("img.episode-image")?.attr("abs:data-src")
            }
        }
            .filter { it.thumbnail_url?.contains("/_nuxt/img/") == false }
            .distinctBy { it.url }

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = ATCFilters.getSearchParameters(filters)
        val url = "$baseUrl/series?order=title&direction=asc&page=$page".toHttpUrl()
            .newBuilder()
            .addQueryParameter("type", params.type)
            .addQueryParameter("search", query)
            .addQueryParameter("year", params.year)
            .addQueryParameter("releaseStatus", params.status)
            .addQueryParameter("tag", params.genre)
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<ResponseDto<AnimeDto>>()
        val animes = data.items.map(::searchAnimeFromObject)
        val hasNextPage = data.lastPage > data.page
        return AnimesPage(animes, hasNextPage)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/series?slug=$slug"))
                .awaitSuccess()
                .use(::searchAnimeBySlugParse)
        } else {
            return super.getSearchAnime(page, query, filters)
        }
    }

    override fun getFilterList(): AnimeFilterList = ATCFilters.FILTER_LIST

    private fun searchAnimeFromObject(anime: AnimeDto) = SAnime.create().apply {
        thumbnail_url = anime.cover.url
        title = anime.title
        setUrlWithoutDomain("/series/${anime.id}")
    }

    private fun searchAnimeBySlugParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val anime = response.getAnimeDto()
        setUrlWithoutDomain("/series/${anime.id}")
        title = anime.title
        status = anime.status
        thumbnail_url = anime.cover.url
        artist = anime.producer
        genre = anime.genres
        description = buildString {
            append(anime.synopsis + "\n")

            anime.classification?.also { append("\nClassificação: ", it, " anos") }
            anime.year?.also { append("\nAno de lançamento: ", it) }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val id = response.getAnimeDto().id
        return getEpisodeList(id)
    }

    private fun episodeListRequest(animeId: Int, page: Int) =
        GET("$baseUrl/episodes?order=id&direction=desc&page=$page&seriesId=$animeId&specialOrder=true")

    private fun getEpisodeList(animeId: Int, page: Int = 1): List<SEpisode> {
        val response = client.newCall(episodeListRequest(animeId, page)).execute()
        val parsed = response.parseAs<ResponseDto<EpisodeDto>>()
        val episodes = parsed.items.map(::episodeFromObject)

        if (parsed.page < parsed.lastPage) {
            return episodes + getEpisodeList(animeId, page + 1)
        } else {
            return episodes
        }
    }

    private fun episodeFromObject(episode: EpisodeDto) = SEpisode.create().apply {
        name = episode.title
        setUrlWithoutDomain("/episodes?slug=${episode.slug}")
        episode_number = episode.number.toFloat()
        date_upload = episode.created_at.toDate()
    }

    // ============================ Video Links =============================
    private val sendcmExtractor by lazy { SendcmExtractor(client) }
    private val gdriveExtractor by lazy { GoogleDriveExtractor(client, headers) }
    private val linkBypasser by lazy { LinkBypasser(client, json) }

    private val supportedPlayers = listOf("send", "drive")

    override fun videoListParse(response: Response): List<Video> {
        val videoDto = response.parseAs<ResponseDto<VideoDto>>().items.first()
        val links = videoDto.links

        val allLinks = listOf(links.low, links.medium, links.high).flatten()
            .filter { it.name in supportedPlayers }

        val online = links.online?.run {
            filterNot { "mega" in it }.map {
                Video(it, "Player ATC", it, headers)
            }
        }.orEmpty()

        val videoId = videoDto.id

        return online + allLinks.parallelCatchingFlatMapBlocking { extractVideosFromLink(it, videoId) }
    }

    private fun extractVideosFromLink(video: VideoDto.VideoLink, videoId: Int): List<Video> {
        val playerUrl = linkBypasser.bypass(video, videoId)
            ?: return emptyList()

        val quality = when (video.quality) {
            "low" -> "SD"
            "medium" -> "HD"
            "high" -> "FULLHD"
            else -> "SD"
        }

        return when (video.name) {
            "send" -> sendcmExtractor.videosFromUrl(playerUrl, quality)
            "drive" -> {
                val id = GDRIVE_REGEX.find(playerUrl)?.groupValues?.get(0) ?: return emptyList()
                gdriveExtractor.videosFromUrl(id, "GDrive - $quality")
            }
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = PREF_PLAYER_TITLE
            entries = PREF_PLAYER_VALUES
            entryValues = PREF_PLAYER_VALUES
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun Response.getAnimeDto(): AnimeDto {
        val jsonString = body.string()
        return try {
            jsonString.parseAs<AnimeDto>()
        } catch (e: Exception) {
            // URL intent handler moment
            jsonString.parseAs<ResponseDto<AnimeDto>>().items.first()
        }
    }

    private fun String.toDate(): Long {
        return try {
            DATE_FORMATTER.parse(this)?.time
        } catch (_: Throwable) { null } ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(player) },
                { it.quality.contains("- $quality") },
            ),
        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "slug:"

        private const val HOST_URL = "https://www.animestc.net"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("SD", "HD", "FULLHD")

        private const val PREF_PLAYER_KEY = "pref_player"
        private const val PREF_PLAYER_TITLE = "Player preferido"
        private const val PREF_PLAYER_DEFAULT = "Sendcm"
        private val PREF_PLAYER_VALUES = arrayOf("Sendcm", "GDrive", "Player ATC")

        private val GDRIVE_REGEX = Regex("[\\w-]{28,}")
    }
}
