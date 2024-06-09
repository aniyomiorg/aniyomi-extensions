package eu.kanade.tachiyomi.animeextension.pt.flixei

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.flixei.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.pt.flixei.dto.ApiResultsDto
import eu.kanade.tachiyomi.animeextension.pt.flixei.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.pt.flixei.dto.PlayersDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Flixei : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Flixei"

    override val baseUrl = "https://flixei.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val body = "slider=3".toFormBody()
        return POST("$baseUrl/includes/ajax/home.php", body = body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val results = response.parseAs<ApiResultsDto<AnimeDto>>()
        val animes = results.items.values.map(::parseAnimeFromObject)
        return AnimesPage(animes, false)
    }

    private fun parseAnimeFromObject(anime: AnimeDto) = SAnime.create().apply {
        title = anime.title
        setUrlWithoutDomain("/assistir/filme/${anime.url}/online/gratis")
        thumbnail_url = "$baseUrl/content/movies/posterPt/185/${anime.id}.webp"
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun popularAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    override fun popularAnimeSelector(): String {
        throw UnsupportedOperationException()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filmes/estreia/$page")

    override fun latestUpdatesSelector() = "div.generalMoviesList > a.gPoster"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst("div.i span")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
        setUrlWithoutDomain(element.attr("abs:href"))
    }

    override fun latestUpdatesNextPageSelector() = "div.paginationSystem a.next"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/assistir/$path/online/gratis"))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/pesquisar/$query")
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        val container = document.selectFirst("div.moviePresent")!!
        with(container) {
            title = selectFirst("h2.tit")!!.text()
            genre = select("div.genres > span").eachText().joinToString()
            author = getInfo("Diretor")
            artist = getInfo("Produtoras")
            description = buildString {
                selectFirst("p")?.text()?.also { append(it + "\n\n") }
                getInfo("Título")?.also { append("Título original: $it\n") }
                getInfo("Serie de")?.also { append("ano: $it\n") }
                getInfo("Elenco")?.also { append("Elenco: $it\n") }
                getInfo("Qualidade")?.also { append("Qualidade: $it\n") }
            }
        }
    }

    // ============================== Episodes ==============================
    private fun getSeasonEps(seasonElement: Element): List<SEpisode> {
        val id = seasonElement.attr("data-load-episodes")
        val sname = seasonElement.text()
        val body = "getEpisodes=$id".toFormBody()
        val response = client.newCall(POST("$EMBED_WAREZ_URL/serieAjax.php", body = body)).execute()
        val episodes = response.parseAs<ApiResultsDto<EpisodeDto>>().items.values.map {
            SEpisode.create().apply {
                name = "Temp $sname: Ep ${it.name}"
                episode_number = it.name.toFloatOrNull() ?: 0F
                url = it.id
            }
        }
        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val docUrl = response.asJsoup().selectFirst("div#playButton")!!
            .attr("onclick")
            .substringAfter("'")
            .substringBefore("'")
        return if (response.request.url.toString().contains("/serie/")) {
            client.newCall(GET(docUrl)).execute()
                .asJsoup()
                .select("div#seasons div.item[data-load-episodes]")
                .flatMap(::getSeasonEps)
                .reversed()
        } else {
            listOf(
                SEpisode.create().apply {
                    name = "Filme"
                    episode_number = 1F
                    url = "$EMBED_WAREZ_URL/filme/" + docUrl.substringAfter("=")
                },
            )
        }
    }
    override fun episodeFromElement(element: Element): SEpisode {
        throw UnsupportedOperationException()
    }

    override fun episodeListSelector(): String {
        throw UnsupportedOperationException()
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val url = episode.url
        return if (url.startsWith("https")) {
            // Its an real url, maybe from a movie
            GET(url, headers)
        } else {
            POST("$EMBED_WAREZ_URL/serieAjax.php", body = "getAudios=$url".toFormBody())
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body.string()
        // Pair<Language, Query>
        val items = if (body.startsWith("{")) {
            val data = json.decodeFromString<ApiResultsDto<PlayersDto>>(body)
            data.items.values.flatMap {
                val lang = if (it.audio == "1") "LEGENDADO" else "DUBLADO"
                it.iterator().mapNotNull { (server, status) ->
                    if (status == "3") {
                        Pair(lang, "?id=${it.id}&sv=$server")
                    } else {
                        null
                    }
                }
            }
        } else {
            val doc = response.asJsoup(body)
            doc.select("div.selectAudioButton").flatMap {
                val lang = it.text()
                val id = it.attr("data-load-hosts")
                doc.select("div[data-load-embed=$id]").map { element ->
                    lang to "?id=$id&sv=${element.attr("data-load-embed-host")}"
                }
            }.ifEmpty {
                val lang = doc.selectFirst("div.selectAudio > b")!!.text()
                    .substringBefore("/")
                    .uppercase()
                val id = doc.selectFirst("*[data-load-embed]")!!.attr("data-load-embed")
                doc.select("div.buttonLoadHost").map {
                    lang to "?id=$id&sv=${it.attr("data-load-embed-host")}"
                }
            }
        }
        return items.parallelCatchingFlatMapBlocking(::getVideosFromItem)
    }

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }

    private fun getVideosFromItem(item: Pair<String, String>): List<Video> {
        val (lang, query) = item
        val headers = headersBuilder().set("referer", WAREZ_URL).build()
        val hostUrl = if ("warezcdn" in query) {
            "$WAREZ_URL/player/player.php$query"
        } else {
            client.newCall(GET("$WAREZ_URL/embed/getPlay.php$query", headers))
                .execute()
                .body.string()
                .substringAfter("location.href=\"")
                .substringBefore("\";")
        }

        return when (query.substringAfter("sv=")) {
            "streamtape" -> streamtapeExtractor.videosFromUrl(hostUrl, "Streamtape($lang)")
            "mixdrop" -> mixdropExtractor.videoFromUrl(hostUrl, lang)
            else -> null // TODO: Add warezcdn extractor
        }.orEmpty()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_PLAYER_KEY
            title = PREF_PLAYER_TITLE
            entries = PREF_PLAYER_ARRAY
            entryValues = PREF_PLAYER_ARRAY
            setDefaultValue(PREF_PLAYER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun Element.getInfo(item: String) = selectFirst("*:containsOwn($item) b")?.text()

    private fun String.toFormBody() = toRequestBody("application/x-www-form-urlencoded".toMediaType())

    override fun List<Video>.sort(): List<Video> {
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(player) },
                { it.quality.contains(language) },
            ),
        ).reversed()
    }

    companion object {

        const val PREFIX_SEARCH = "path:"

        private const val EMBED_WAREZ_URL = "https://embed.warezcdn.net"
        private const val WAREZ_URL = "https://warezcdn.com"

        private const val PREF_PLAYER_KEY = "pref_player"
        private const val PREF_PLAYER_DEFAULT = "MixDrop"
        private const val PREF_PLAYER_TITLE = "Player/Server favorito"
        private val PREF_PLAYER_ARRAY = arrayOf(
            "MixDrop",
            "Streamtape",
        )

        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_DEFAULT = "LEG"
        private const val PREF_LANGUAGE_TITLE = "Língua/tipo preferido"
        private val PREF_LANGUAGE_ENTRIES = arrayOf("Legendado", "Dublado")
        private val PREF_LANGUAGE_VALUES = arrayOf("LEG", "DUB")
    }
}
