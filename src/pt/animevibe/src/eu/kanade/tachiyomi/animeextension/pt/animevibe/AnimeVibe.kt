package eu.kanade.tachiyomi.animeextension.pt.animevibe

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class AnimeVibe : AnimeHttpSource() {
    override val name = "AnimeVibe"
    override val baseUrl = "https://animevibe.cc"
    override val lang = "pt-BR"
    override val supportsLatest = false

    private val format = Json { ignoreUnknownKeys = true }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json")

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(5, 1, TimeUnit.SECONDS)
        .addInterceptor(::requestIntercept)
        .build()

    private val requestCache: MutableMap<String, String> = mutableMapOf()

    private fun requestIntercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toString()
        if (!url.contains("data?page=medias")) return chain.proceed(chain.request())

        if (requestCache.containsKey(url)) {
            val body = requestCache[url]!!.toResponseBody("application/json; charset=UTF-8".toMediaTypeOrNull())
            return Response.Builder()
                .code(200)
                .protocol(Protocol.HTTP_1_1)
                .request(chain.request())
                .message("OK")
                .body(body)
                .build()
        }

        val response = chain.proceed(chain.request())
        val contentType = response.body!!.contentType()
        val body = response.body!!.string()
        requestCache[url] = body
        return response.newBuilder()
            .body(body.toResponseBody(contentType))
            .build()
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun getAnimeTitle(anime: AnimeVibeAnimeDto): String = (anime.title["romaji"] ?: anime.title["english"] ?: anime.title["native"]!!) + (if (anime.audio == "Dublado") " (DUBLADO)" else "")

    private fun getAnimeFromObject(anime: AnimeVibeAnimeDto): SAnime = SAnime.create().apply {
        initialized = true
        thumbnail_url = "$CDN_URL/img/animes/${anime.slug}-large.webp"
        url = "$baseUrl/anime/${anime.id}"
        title = getAnimeTitle(anime)
        description = anime.description
        genre = anime.genres?.joinToString(", ")
        status = when (anime.status) {
            "Completo" -> SAnime.COMPLETED
            "Em lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun popularAnimeRequest(page: Int): Request {
        val headers = headersBuilder().build()
        return GET("$baseUrl/$API_PATH/data?page=medias", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = format.decodeFromString<AnimeVibePopularDto>(response.body!!.string())

        if (animes.data.isNullOrEmpty()) {
            return AnimesPage(emptyList(), hasNextPage = false)
        }

        val animeList = animes.data
            .sortedByDescending { it.views }
            .take(50)
            .map(::getAnimeFromObject)
        return AnimesPage(animeList, hasNextPage = false)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("/anime/")
            .substringBefore("/")
        val headers = headersBuilder()
            .add("X-id", id)
            .build()
        return GET("$baseUrl/$API_PATH/data?page=medias", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val animes = format.decodeFromString<AnimeVibePopularDto>(response.body!!.string())
        if (animes.data.isNullOrEmpty()) throw Exception(COULD_NOT_PARSE_ANIME)

        val id = response.request.header("X-id")!!.toInt()
        val anime = animes.data.find { it.id == id } ?: throw Exception(COULD_NOT_PARSE_ANIME)

        return getAnimeFromObject(anime)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("/anime/")
            .substringBefore("/")
        return GET("$baseUrl/$API_PATH/data?page=episode&mediaID=$id")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = format.decodeFromString<AnimeVibeEpisodeListDto>(response.body!!.string())
        if (episodes.data.isNullOrEmpty()) return emptyList()

        return episodes.data
            .map {
                SEpisode.create().apply {
                    url = "$baseUrl/anime/${it.mediaID}?episode=${it.number}"
                    name = "Episódio ${it.number.toInt()}"
                    date_upload = parseDate(it.datePublished)
                    episode_number = it.number
                }
            }
            .reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val id = episode.url.substringAfter("/anime/")
            .substringBefore("?")
        val headers = headersBuilder()
            .add("X-mediaid", id)
            .add("X-number", episode.episode_number.toString())
            .add("X-url", episode.url)
            .build()
        return GET("$baseUrl/$API_PATH/data?page=episode&mediaID=$id", headers)
    }

    private fun getVideoFromSource(source: String): List<Video> {
        return if (source.startsWith('/')) {
            val url = "$VIDEO_URL$source"
            listOf(Video(url, source.substringBeforeLast('/').substringAfterLast('/').uppercase(), url))
        } else if (source.startsWith("https://www.blogger.com/")) {
            val headers = headersBuilder()
                .add("User-Agent", USER_AGENT)
                .build()
            val response = client.newCall(GET(source, headers)).execute()
            val streams = response.body!!.string().substringAfter("\"streams\":[").substringBefore("]")
            return streams.split("},")
                .map {
                    val url = it.substringAfter("{\"play_url\":\"").substringBefore('"')
                    val quality = when (it.substringAfter("\"format_id\":").substringBefore("}")) {
                        "18" -> "360p"
                        "22" -> "720p"
                        else -> "Unknown Resolution"
                    }
                    Video(url, quality, url, null, headers)
                }
        } else throw Exception("UNKOWN VIDEO SOURCE")
    }

    override fun videoListParse(response: Response): List<Video> {
        val number = response.request.header("X-number")!!.toFloat()
        val episodes = format.decodeFromString<AnimeVibeEpisodeListDto>(response.body!!.string())
        if (episodes.data.isNullOrEmpty()) throw Exception("NO DATA ${response.request.header("X-mediaid")} ${response.request.header("X-url")}")

        val episode = episodes.data.find { it.number == number } ?: throw Exception("NO EPISODE $number")
        return episode.videoSource
            .flatMap(::getVideoFromSource)
            .reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val headers = headersBuilder()
            .add("X-page", page.toString())
            .add("X-query", query)
            .build()
        return GET("$baseUrl/$API_PATH/data?page=medias", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val query = response.request.header("X-query")!!
        val animes = format.decodeFromString<AnimeVibePopularDto>(response.body!!.string())
        if (animes.data.isNullOrEmpty()) {
            return AnimesPage(emptyList(), hasNextPage = false)
        }

        val animeList = animes.data
            .filter { getAnimeTitle(it).contains(query, ignoreCase = true) }
            .sortedByDescending { it.views }
            .map(::getAnimeFromObject)
        return AnimesPage(animeList, hasNextPage = false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        throw UnsupportedOperationException("Not used.")
    }

    companion object {
        private const val API_PATH = "animevibe/api/v1"
        private const val CDN_URL = "https://animefire.net"
        private const val VIDEO_URL = "https://akumaharu.org"
        // blogger.com videos needs an user agent to work
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

        private const val COULD_NOT_PARSE_ANIME = "Ocorreu um erro ao obter as informações do anime."

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-ddTHH:mm:ss", Locale.ENGLISH)
        }
    }
}
