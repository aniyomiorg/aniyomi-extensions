package eu.kanade.tachiyomi.animeextension.fr.animesama

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mytvextractor.MytvExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.lang.Exception

// To Anime-Sama admins : THIS is an actual application, enjoy your webview nonetheless :troll:

class AnimeSama : ParsedAnimeHttpSource() {

    override val name = "Anime-Sama"

    override val baseUrl = "https://www.anime-sama.fr"

    override val lang = "fr"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    // ============================== Popular ===============================
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        val seasons = mutableListOf<SAnime>()
        val animes = client.newCall(popularAnimeRequest(page)).execute().asJsoup()
        animes.select(popularAnimeSelector()).forEach { animeElement ->
            val animeUrl = animeElement.getElementsByTag("a").attr("href")
            val animeDoc = client.newCall(GET(animeUrl)).execute().asJsoup()
            val animeName = animeDoc.getElementById("titreOeuvre")?.text()
            val animeThumb = animeDoc.getElementById("coverOeuvre")?.attr("src")
            val animeDesc = animeDoc.select("h2:contains(synopsis) + p").text()
            val animeGenres = animeDoc.select("h2:contains(genres) + p").text()
            val seasonRegex = Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", setOf(RegexOption.MULTILINE))
            seasonRegex.findAll(animeDoc.toString()).forEach { match ->
                val (seasonName, seasonStem) = match.destructured
                if (seasonName.contains("film", true)) {
                    // Todo: Movies are inside a single "Season" we want a single SAnime for each one.
                } else {
                    val seasonUrl = "$animeUrl/$seasonStem"
                    val season = SAnime.create().apply {
                        title = "$animeName $seasonName"
                        thumbnail_url = animeThumb
                        description = animeDesc
                        genre = animeGenres
                        status = SAnime.UNKNOWN // Todo: Determine status based on /planning page
                        setUrlWithoutDomain(seasonUrl.toHttpUrl().encodedPath)
                        initialized = true
                    }
                    seasons += season
                }
            }
        }
        return Observable.just(AnimesPage(seasons, false))
    }

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeSelector(): String = "h2:contains(les classiques) + .scrollBarStyled > div"

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

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
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    // =========================== Anime Details ============================
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = Observable.just(anime)

    override fun animeDetailsParse(document: Document): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================== Episodes ==============================
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val episodesUrl = "$baseUrl/${anime.url}/episodes.js"
        val doc = client.newCall(GET(episodesUrl)).execute().body.string()
        val sanitizedDoc = doc.replace("'", "\"").replace(Regex(",\\s*]"), "]")

        val players = mutableListOf<List<String>>()
        val episodes = mutableListOf<SEpisode>()

        val asPlayers = getPlayers("epsAS", sanitizedDoc)
        if (asPlayers != null) players.add(asPlayers)
        for (i in 1..8) {
            val numPlayers = getPlayers("eps$i", sanitizedDoc)
            if (numPlayers != null) players.add(numPlayers)
        }

        for (i in players[0].indices) {
            val jsonArray = "[${players.toSet().joinToString { "\"${it[i]}\"" }}]"
            val episode = SEpisode.create().apply {
                name = "Episode ${i + 1}" // Todo: Some episodes (notably OAVs) may have names
                episode_number = (i + 1).toFloat()
                url = jsonArray
            }
            episodes += episode
        }
        return Observable.just(episodes.reversed())
    }

    override fun episodeListSelector(): String = throw Exception("not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val playerUrls = Json.decodeFromString<List<String>>(episode.url)
        val videos = mutableListOf<Video>()
        playerUrls.forEach { playerUrl ->
            val playerVideos = when {
                playerUrl.contains("anime-sama.fr") -> listOf(Video(playerUrl, "AS Player", playerUrl))
                playerUrl.contains("sibnet.ru") -> SibnetExtractor(client).videosFromUrl(playerUrl)
                playerUrl.contains("myvi.top") -> MytvExtractor(client).videosFromUrl(playerUrl)
                else -> null
            }
            if (playerVideos != null) videos.addAll(playerVideos)
        }
        return Observable.just(videos)
    }

    override fun videoListSelector(): String = throw Exception("not used")

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document): String = throw Exception("not used")

    // ============================ Utils =============================
    private fun getPlayers(playerName: String, doc: String): List<String>? {
        val playerRegex = Regex("$playerName\\s*=\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
        val string = playerRegex.find(doc)?.groupValues?.get(1)
        return when {
            string != null -> Json.decodeFromString<List<String>>(string)
            else -> null
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
