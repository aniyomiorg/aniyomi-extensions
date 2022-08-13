package eu.kanade.tachiyomi.animeextension.all.animeonsen

import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListItem
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.AnimeListResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.SearchResponse
import eu.kanade.tachiyomi.animeextension.all.animeonsen.dto.VideoData
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.Exception

class AnimeOnsen : AnimeHttpSource() {

    override val name = "AnimeOnsen"

    override val baseUrl = "https://animeonsen.xyz"

    private val apiUrl = "https://api.animeonsen.xyz/v4"

    override val lang = "all"

    override val supportsLatest = false

    private val cfClient = network.cloudflareClient

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(AOAPIInterceptor(cfClient))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("referer", baseUrl)
        .add("user-agent", AO_USER_AGENT)

    // ============================== Popular ===============================
    // The site doesn't have a popular anime tab, so we use the home page instead (latest anime).
    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiUrl/content/index?start=${(page - 1) * 20}&limit=20")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<AnimeListResponse>(response.body!!.string())
        val animes = responseJson.content.map {
            it.toSAnime()
        }
        val hasNextPage = responseJson.cursor.next.firstOrNull()?.jsonPrimitive?.boolean == true
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = response.asJsoup().select("div.episode-list > a")
        return episodes.map {
            val num = it.attr("data-episode")
            val episodeSpan = it.select("div.episode > span.general")
            val titleSpan = it.select("div.episode > span.title")
            SEpisode.create().apply {
                url = it.attr("href")
                    .substringAfter("/watch/")
                    .replace("?episode=", "/video/")
                episode_number = num.toFloat()
                name = episodeSpan.text() + ": " + titleSpan.text()
            }
        }.reversed()
    }

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl/details/${anime.url}")
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val videoData = json.decodeFromString<VideoData>(response.body!!.string())
        val videoUrl = videoData.uri.stream
        val subtitleLangs = videoData.metadata.subtitles
        val headers = Headers.headersOf(
            "referer", baseUrl,
            "user-agent", AO_USER_AGENT,
        )
        val video = try {
            val subtitles = videoData.uri.subtitles.keys.map {
                val lang = subtitleLangs[it]!!.jsonPrimitive.content
                val url = videoData.uri.subtitles[it]!!.jsonPrimitive.content
                Track(url, lang)
            }
            Video(videoUrl, "Default (720p)", videoUrl, headers = headers, subtitleTracks = subtitles)
        } catch (e: Error) {
            Video(videoUrl, "Default (720p)", videoUrl, headers = headers)
        }
        return listOf(video)
    }

    override fun videoListRequest(episode: SEpisode) = GET("$apiUrl/content/${episode.url}")

    override fun videoUrlParse(response: Response) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchResult = json.decodeFromString<SearchResponse>(response.body!!.string()).result
        val results = searchResult.map {
            it.toSAnime()
        }
        return AnimesPage(results, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$apiUrl/search/$query")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val details = json.decodeFromString<AnimeDetails>(response.body!!.string())
        val anime = SAnime.create().apply {
            url = details.content_id
            title = details.content_title ?: details.content_title_en!!
            status = parseStatus(details.mal_data?.status)
            author = details.mal_data?.studios?.joinToString { it.name }
            genre = details.mal_data?.genres?.joinToString { it.name }
            description = details.mal_data?.synopsis
            thumbnail_url = "https://api.animeonsen.xyz/v4/image/420x600/${details.content_id}"
        }
        return anime
    }

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(GET("$apiUrl/content/${anime.url}/extensive"))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl/details/${anime.url}")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")
    override fun latestUpdatesParse(response: Response) = throw Exception("not used")

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "finished_airing" -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun AnimeListItem.toSAnime() = SAnime.create().apply {
        url = content_id
        title = content_title ?: content_title_en!!
        thumbnail_url = "https://api.animeonsen.xyz/v4/image/420x600/$content_id"
    }
}

const val AO_USER_AGENT = "Aniyomi/app (mobile)"
