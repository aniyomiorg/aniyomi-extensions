package eu.kanade.tachiyomi.animeextension.de.aniflix

import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.AnimeDetailsDto
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.Episode
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.Release
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class Aniflix : AnimeHttpSource() {

    override val name = "Aniflix"

    override val baseUrl = "https://www2.aniflix.tv"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://dood.la/")
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string())
        val newAnime = SAnime.create().apply {
            title = anime.name!!
            setUrlWithoutDomain("$baseUrl/api/show/" + anime.url!!)
            if (anime.coverPortrait != null) {
                thumbnail_url = "$baseUrl/storage/" + anime.coverPortrait
            }
            description = anime.description
            if (anime.airing == 0) {
                status = SAnime.COMPLETED
            } else if (anime.airing == 1) {
                status = SAnime.ONGOING
            }
            genre = anime.genres?.joinToString { it.name!! }
        }
        return newAnime
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/show/new/${page - 1}")

    override fun popularAnimeParse(response: Response) = parseAnimePage(response)

    private fun parseAnimePage(response: Response, singlePage: Boolean = false): AnimesPage {
        val animes = json.decodeFromString(ListSerializer(AnimeDto.serializer()), response.body!!.string())
        if (animes.isEmpty()) return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        for (anime in animes) {
            val newAnime = createAnime(anime)
            animeList.add(newAnime)
        }
        return AnimesPage(animeList, !singlePage)
    }

    private fun createAnime(anime: AnimeDto): SAnime {
        return SAnime.create().apply {
            title = anime.name!!
            setUrlWithoutDomain("$baseUrl/api/show/" + anime.url!!)
            if (anime.coverPortrait != null) {
                thumbnail_url = "$baseUrl/storage/" + anime.coverPortrait
            }
            description = anime.description
            if (anime.airing == 0) {
                status = SAnime.COMPLETED
            } else if (anime.airing == 1) {
                status = SAnime.ONGOING
            }
        }
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/api/show/airing/${page - 1}")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val releases = json.decodeFromString(ListSerializer(Release.serializer()), response.body!!.string()).toMutableList()
        if (releases.isEmpty()) return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        val releaseList = mutableListOf<Int>()
        for (release in releases) {
            if (release.season!!.anime!!.id in releaseList) continue
            releaseList.add(release.season.anime!!.id!!)
            val anime = release.season.anime
            val newAnime = SAnime.create().apply {
                title = anime.name!!
                setUrlWithoutDomain("$baseUrl/api/show/" + anime.url!!)
                if (anime.coverPortrait != null) {
                    thumbnail_url = "$baseUrl/storage/" + anime.coverPortrait
                }
                description = anime.description
                if (anime.airing == 0) {
                    status = SAnime.COMPLETED
                } else if (anime.airing == 1) {
                    status = SAnime.ONGOING
                }
            }
            animeList.add(newAnime)
        }
        return AnimesPage(animeList, true)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = POST(
        "$baseUrl/api/show/search",
        body = "{\"search\":\"$query\"}".toRequestBody("application/json".toMediaType())
    )

    override fun searchAnimeParse(response: Response) = parseAnimePage(response, singlePage = true)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string())
        if (anime.seasons.isNullOrEmpty()) return emptyList()
        val episodeList = mutableListOf<SEpisode>()
        val animeUrl = anime.url!!
        for (season in anime.seasons) {
            val lastSeasonLength = episodeList.size
            for (episodeNumber in 1..season.episodes!!.size) {
                val newEpisode = SEpisode.create().apply {
                    setUrlWithoutDomain("$baseUrl/api/episode/show/$animeUrl/season/${season.number!!}/episode/$episodeNumber")
                    episode_number = lastSeasonLength + episodeNumber.toFloat()
                    name = "Staffel ${season.number}: Folge $episodeNumber"
                    date_upload = System.currentTimeMillis()
                }
                episodeList.add(newEpisode)
            }
        }
        return episodeList.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val streams = json.decodeFromString(Episode.serializer(), response.body!!.string()).streams
        if (streams.isNullOrEmpty()) return emptyList()
        val videoList = mutableListOf<Video>()
        for (stream in streams) {
            val quality = "${stream.hoster!!.name}, ${stream.lang!!}"
            if (stream.link!!.contains("https://dood.la/e/")) {
                videoList.add(Video(stream.link, quality, null, null))
            }
        }
        return videoList
    }

    override fun videoUrlParse(response: Response): String {
        val url = response.request.url.toString()
        response.priorResponse
        if (url.contains("https://dood.la/e/")) {
            val content = response.body!!.string()
            val md5 = content.substringAfter("'/pass_md5/").substringBefore("',")
            val token = md5.substringAfterLast("/")
            val randomString = getRandomString()
            val expiry = System.currentTimeMillis()
            val videoUrlStart = client.newCall(
                GET(
                    "https://dood.la/pass_md5/$md5",
                    Headers.headersOf("referer", url)
                )
            ).execute().body!!.string()
            return "$videoUrlStart$randomString?token=$token&expiry=$expiry"
        }
        return response.request.url.toString()
    }

    private fun getRandomString(length: Int = 10): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }
}
