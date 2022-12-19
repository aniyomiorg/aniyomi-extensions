package eu.kanade.tachiyomi.animeextension.pt.animetv

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response

class AnimeTV : AnimeHttpSource() {
    override val name = "AnimeTV"
    override val baseUrl = "https://appanimeplus.tk/play-api.php"
    override val lang = "pt-BR"
    override val supportsLatest = true

    private val cdnUrl = "https://cdn.appanimeplus.tk/img/"

    // == POPULAR ANIME ==
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl?populares")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = Json.decodeFromString<JsonArray>(response.body!!.string())
        if (animes.isEmpty()) return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        for (anime in animes) {
            val newAnime = SAnime.create()
            newAnime.title = anime.jsonObject["category_name"]!!.jsonPrimitive.content
            newAnime.thumbnail_url = "$cdnUrl" + anime.jsonObject["category_image"]!!.jsonPrimitive.content
            newAnime.url = "$baseUrl?info=" + anime.jsonObject["id"]!!.jsonPrimitive.content
            animeList.add(newAnime)
        }
        return AnimesPage(animeList, false)
    }

    // == LATEST ANIME == / DEPRECATED
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl?latest")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = Json.decodeFromString<JsonArray>(response.body!!.string())
        if (animes.isEmpty()) return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        for (anime in animes) {
            val newAnime = SAnime.create()
            newAnime.title = anime.jsonObject["title"]!!.jsonPrimitive.content
            newAnime.thumbnail_url = "$cdnUrl" + anime.jsonObject["category_image"]!!.jsonPrimitive.content
            newAnime.url = "$baseUrl?info=" + anime.jsonObject["category_id"]!!.jsonPrimitive.content
            animeList.add(newAnime)
        }
        return AnimesPage(animeList, false)
    }

    // == SEARCH ANIME ==
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl?search=$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val animes = Json.decodeFromString<JsonArray>(response.body!!.string())
        if (animes.isEmpty()) return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        for (anime in animes) {
            val newAnime = SAnime.create()
            newAnime.title = anime.jsonObject["category_name"]!!.jsonPrimitive.content
            newAnime.thumbnail_url = "$cdnUrl" + anime.jsonObject["category_image"]!!.jsonPrimitive.content
            newAnime.url = "$baseUrl?info=" + anime.jsonObject["id"]!!.jsonPrimitive.content
            animeList.add(newAnime)
        }
        return AnimesPage(animeList, false)
    }

    // == PARSE ANIME ==
    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url)
    override fun animeDetailsParse(response: Response): SAnime {
        val animes = Json.decodeFromString<JsonArray>(response.body!!.string())
        if (animes.isEmpty()) return SAnime.create()
        val animeData = animes.first()
        val anime = SAnime.create()
        anime.url = "$baseUrl?info=" + animeData.jsonObject["id"]!!.jsonPrimitive.content
        anime.title = animeData.jsonObject["category_name"]!!.jsonPrimitive.content
        anime.description = animeData.jsonObject["category_description"]!!.jsonPrimitive.content
        anime.genre = animeData.jsonObject["category_genres"]!!.jsonPrimitive.content
        anime.status = 0
        anime.thumbnail_url = "$cdnUrl" + animeData.jsonObject["category_image"]!!.jsonPrimitive.content
        anime.initialized = true
        return anime
    }
    // == PARSE EPISODES ==
    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl?cat_id=" + anime.url.substring(42))

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodesData = Json.decodeFromString<JsonArray>(response.body!!.string())
        if (episodesData.isEmpty()) return emptyList()
        val episodes = mutableListOf<SEpisode>()
        val ovas = mutableListOf<SEpisode>()
        for (episode in episodesData) {
            val newEpisode = SEpisode.create()
            newEpisode.url = "$baseUrl?episodios=" + episode.jsonObject["video_id"]!!.jsonPrimitive.content
            newEpisode.name = episode.jsonObject["title"]!!.jsonPrimitive.content
            newEpisode.date_upload = System.currentTimeMillis()
            if (newEpisode.name.contains("ova", ignoreCase = true)) {
                ovas.add(newEpisode)
            } else {
                episodes.add(newEpisode)
            }
        }
        if (episodes.first().name.last() === '1') episodes.reverse()
        for (ova in ovas) { episodes.add(0, ova) }
        return episodes
    }
    // == PARSE VIDEOS ==
    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl?episodios=" + episode.url.substring(47))
    }
    override fun videoListParse(response: Response): List<Video> {
        val videosData = Json.decodeFromString<JsonArray>(response.body!!.string())
        val videoData = videosData.first()

        val videos = mutableListOf<Video>()
        val hasSD = videoData.jsonObject["locationsd"]!!.jsonPrimitive.content.length > 0
        if (hasSD) {
            val url = videoData.jsonObject["locationsd"]!!.jsonPrimitive.content
            videos.add(Video(url, "HD", url))
        }
        val url = videoData.jsonObject["location"]!!.jsonPrimitive.content
        videos.add(Video(url, "SD", url))
        return videos
    }

    // == FILTERS ==
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(emptyList())
}
