package eu.kanade.tachiyomi.animeextension.uk.uakino

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class UAKino : ParsedAnimeHttpSource() {

    override val lang = "uk"
    override val name = "UAKino"
    override val supportsLatest = true
    private val animeSelector = "div.movie-item"
    private val nextPageSelector = "a:contains(Далі)"

    override val baseUrl = "https://uakino.club"
    private val animeUrl = "/animeukr"
    private val popularUrl = "/f/c.year=1921,2024/sort=rating;desc"

    private val episodesAPI = "https://uakino.club/engine/ajax/playlists.php?news_id=%s&xfield=playlist" // %s - ID title

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.select("h1 span.solototle").text()

        // Poster can be /upload... or https://...
        val posterUrl = document.select("a[data-fancybox=gallery]").attr("href")
        if (posterUrl.contains("https://uakino.club")) {
            anime.thumbnail_url = posterUrl
        } else {
            anime.thumbnail_url = baseUrl + posterUrl
        }

        anime.description = document.select("div.full-text[itemprop=description]").text()
        Log.d("animeDetailsParse", anime.thumbnail_url!!)

        return anime
    }

    // ============================== Popular ===============================

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("a.movie-title").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("div.movie-img img").attr("src")
        anime.title = element.select("a.movie-title").text()
        return anime
    }

    override fun popularAnimeNextPageSelector() = nextPageSelector

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl$animeUrl$popularUrl/page/$page")
    }

    override fun popularAnimeSelector(): String = animeSelector

    // =============================== Latest ===============================

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl + animeUrl)

    override fun latestUpdatesSelector() = animeSelector

    // =============================== Search ===============================

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = nextPageSelector

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", query)
            .build()
        return POST(baseUrl, body = body)
    }

    override fun searchAnimeSelector() = animeSelector

    // ============================== Episode ===============================

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    private val json = Json { ignoreUnknownKeys = true }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animePage = response.asJsoup()

        // Get ID title
        var titleID = animePage.select("input[id=post_id]").attr("value")

        // Do call
        val episodesList = client.newCall(GET(episodesAPI.format(titleID)))
            .execute()
            .body.string()

        // Parse JSON
        Log.d("episodeListParse", episodesAPI.format(titleID))
        val jsonObject = JSONTokener(episodesList).nextValue() as JSONObject

        // List episodes
        val episodeList = mutableListOf<SEpisode>()

        // If "success" is false - is not anime serial(or another player)
        if (jsonObject.getBoolean("success")) {
            Jsoup.parse(jsonObject.getString("response")).select("div.playlists-videos li").forEach {
                val episode = SEpisode.create()
                episode.name = it.text() + " " + it.select("li").attr("data-voice")
                var episodeUrl = it.select("li").attr("data-file")

                // Can be without https:
                if (episodeUrl.contains("https://")) {
                    episode.url = episodeUrl
                } else {
                    episode.url = "https:" + episodeUrl
                }

                episodeList.add(episode)
            }
        } else {
            val playerUrl = animePage.select("iframe#pre").attr("src")
            // Another player
            if (playerUrl.contains("/serial/")) {
                Log.d("episodeListParse", playerUrl)
                val playerScript = client.newCall(GET(playerUrl))
                    .execute()
                    .asJsoup()
                    .select("script")
                    .html()

                // Get m3u8 url
                val regexM3u8 = """file:'(.*?)'""".toRegex()
                val m3u8JSONString = regexM3u8.find(playerScript)!!.value.substring(6).dropLast(1) // Drop file:"..."
                Log.d("episodeListParse", m3u8JSONString)
                val episodesJSON = json.decodeFromString<List<AshdiModel>>(m3u8JSONString)
                for (itemVoice in episodesJSON) { // Voice
                    for (itemSeason in itemVoice.folder) { // Season
                        for (itemVideo in itemSeason.folder) { // Video

                            val episode = SEpisode.create()
                            episode.name = "${itemSeason.title} ${itemVideo.title} ${itemVoice.title}" // "Сезон 1 Серія 1 Озвучення"
                            episode.url = itemVideo.file
                            episodeList.add(episode)
                        }
                    }
                }
            } else { // Search as one video
                val episode = SEpisode.create()
                episode.name = animePage.select("span.solototle").text()
                episode.url = playerUrl
                episodeList.add(episode)
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video ===============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.d("fetchVideoList", episode.url)

        val videoList = mutableListOf<Video>()
        var m3u8Episode = episode.url
        if (!episode.url.contains(".m3u8")) { // If not from another player
            // Get player script
            val playerScript = client.newCall(GET(episode.url)).execute().asJsoup().select("script").html()

            // Get m3u8 url
            val regexM3u8 = """file:"(.*?)"""".toRegex()
            m3u8Episode = regexM3u8.find(playerScript)!!.value.substring(6).dropLast(1) // Drop file:"..."
        }

        // Parse m3u (480p/720p/1080p)
        // GET Calll m3u8 url
        val masterPlaylist = client.newCall(GET(m3u8Episode)).execute().body.string()
        // Parse quality and videoUrl from m3u8 file
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n")

            videoList.add(Video(videoUrl, quality, videoUrl))
        }

        return videoList
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
}
