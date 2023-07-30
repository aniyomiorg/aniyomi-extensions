package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mytvextractor.MytvExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.Normalizer

class AnimeSama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime-Sama"

    override val baseUrl = "https://www.anime-sama.fr"

    override val lang = "fr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        val animes = client.newCall(popularAnimeRequest(page)).execute().use { it.asJsoup() }
        val seasons = animes.select(popularAnimeSelector()).flatMap {
            val animeUrl = it.getElementsByTag("a").attr("href")
            fetchAnimeSeasons(animeUrl)
        }
        return Observable.just(AnimesPage(seasons, false))
    }

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeSelector(): String = "h2:contains(les classiques) + .scrollBarStyled > div"

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        val animes = client.newCall(latestUpdatesRequest(page)).execute().use { it.asJsoup() }
        val seasons = animes.select(latestUpdatesSelector()).flatMap {
            val animeUrl = it.getElementsByTag("a").attr("href")
            fetchAnimeSeasons(animeUrl)
        }
        return Observable.just(AnimesPage(seasons, false))
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "h2:contains(derniers ajouts) + .scrollBarStyled > div"

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("not used")

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            val seasons = fetchAnimeSeasons("$baseUrl/catalogue/$id")
            Observable.just(AnimesPage(seasons, false))
        } else {
            val doc = client.newCall(searchAnimeRequest(page, query, filters)).execute().asJsoup()
            val elements = doc.select(".cardListAnime").chunked(5)
            val animes = elements[page - 1].flatMap {
                fetchAnimeSeasons(it.getElementsByTag("a").attr("href"))
            }
            Observable.just(AnimesPage(animes, page < elements.size))
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        POST("$baseUrl/catalogue/searchbar.php", headers, FormBody.Builder().add("query", query).build())

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

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("not used")

    // ============================== Episodes ==============================
    @OptIn(ExperimentalStdlibApi::class)
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val animeUrl = "$baseUrl${anime.url.substringBeforeLast("/")}".toHttpUrl()

        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!.split(",")
        val episodes = voices.mapNotNull {
            playersToEpisodes(fetchPlayers("$animeUrl/$it"), it.uppercase())
        }.ifEmpty {
            VOICES_VALUES.filterNot(voices::contains).mapNotNull {
                playersToEpisodes(fetchPlayers("$animeUrl/$it"), it.uppercase())
            }
        }
        if (preferences.getString(PREF_MERGE_KIND_KEY, PREF_MERGE_KIND_DEFAULT) == "zip") {
            val zipped = mutableListOf<SEpisode>()
            for (i in 0..<episodes.fold(0) { acc, list -> maxOf(acc, list.size) }) {
                for (list in episodes) {
                    val episode = list.getOrNull(i)
                    if (episode != null) zipped.add(episode)
                }
            }
            return Observable.just(zipped.reversed())
        }
        return Observable.just(episodes.flatten().reversed())
    }

    override fun episodeListSelector(): String = throw Exception("not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val playerUrls = json.decodeFromString<List<String>>(episode.url)
        val videos = playerUrls.flatMap { playerUrl ->
            with(playerUrl) {
                when {
                    contains("anime-sama.fr") -> listOf(Video(playerUrl, "AS Player", playerUrl))
                    contains("sibnet.ru") -> SibnetExtractor(client).videosFromUrl(playerUrl)
                    contains("myvi.") -> MytvExtractor(client).videosFromUrl(playerUrl)
                    contains("vk.") -> VkExtractor(client, headers).videosFromUrl(playerUrl)
                    contains("sendvid.com") -> SendvidExtractor(client, headers).videosFromUrl(playerUrl)
                    else -> emptyList()
                }
            }
        }
        return Observable.just(videos)
    }

    override fun videoListSelector(): String = throw Exception("not used")

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document): String = throw Exception("not used")

    // ============================ Utils =============================
    private fun removeDiacritics(string: String) = Normalizer.normalize(string, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun sanitizeEpisodesJs(doc: String) = doc
        .replace("'", "\"") // Fix quotes
        .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)), "") // Remove block comments
        .replace(Regex("(^|,|\\[)\\s*//.*?$", RegexOption.MULTILINE), "$1") // Remove line comments
        .replace(Regex(",\\s*]"), "]") // Remove trailing comma

    private fun fetchAnimeSeasons(animeUrl: String): List<SAnime> {
        val animeDoc = client.newCall(GET(animeUrl)).execute().use { it.asJsoup() }
        val animeName = animeDoc.getElementById("titreOeuvre")?.text() ?: ""

        val seasonRegex = Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", RegexOption.MULTILINE)
        val animes = seasonRegex.findAll(animeDoc.toString()).flatMapIndexed { animeIndex, seasonMatch ->
            val (seasonName, seasonStem) = seasonMatch.destructured
            if (seasonStem.contains("film", true)) {
                val moviesUrl = "$animeUrl/$seasonStem"
                val moviesPlayers = fetchPlayers(moviesUrl) ?: return@flatMapIndexed emptyList()
                val movieNameRegex = Regex("^\\s*newSPF\\(\"(.*)\"\\);", RegexOption.MULTILINE)
                val moviesDoc = client.newCall(GET(moviesUrl)).execute().use { it.body.string() }
                val matches = movieNameRegex.findAll(moviesDoc).toList()
                List(moviesPlayers[0].size) { i ->
                    val title = when {
                        animeIndex == 0 && moviesPlayers[0].size == 1 -> animeName
                        matches.size > i -> "$animeName ${matches[i].destructured.component1()}"
                        moviesPlayers[0].size == 1 -> "$animeName Film"
                        else -> "$animeName Film ${i + 1}"
                    }
                    Triple(title, "$moviesUrl#$i", SAnime.COMPLETED)
                }
            } else {
                listOf(Triple("$animeName $seasonName", "$animeUrl/$seasonStem", SAnime.UNKNOWN))
            }
        }

        return animes.map {
            SAnime.create().apply {
                title = it.first
                thumbnail_url = animeDoc.getElementById("coverOeuvre")?.attr("src")
                description = animeDoc.select("h2:contains(synopsis) + p").text()
                genre = animeDoc.select("h2:contains(genres) + a").text()
                setUrlWithoutDomain(it.second)
                status = it.third
                initialized = true
            }
        }.toList()
    }

    private fun playersToEpisodes(players: List<List<String>>?, voices: String = ""): List<SEpisode>? =
        List(players?.getOrNull(0)?.size ?: 0) { i ->
            SEpisode.create().apply {
                name = "Episode ${i + 1}"
                url = "[${players!!.distinct().joinToString { "\"${it[i]}\"" }}]"
                episode_number = i.toFloat()
                scanlator = voices
            }
        }.ifEmpty { null }

    private fun fetchPlayers(url: String): List<List<String>>? {
        val docUrl = "$url/episodes.js"
        val players = mutableListOf<List<String>>()
        val doc = client.newCall(GET(docUrl)).execute().use {
            if (it.code != 200) return null
            it.body.string()
        }
        val sanitizedDoc = sanitizeEpisodesJs(doc)
        for (i in 1..8) {
            val numPlayers = getPlayers("eps$i", sanitizedDoc)
            if (numPlayers != null) players.add(numPlayers)
        }
        val asPlayers = getPlayers("epsAS", sanitizedDoc)
        if (asPlayers != null) players.add(asPlayers)
        return players.ifEmpty { null }
    }

    private fun getPlayers(playerName: String, doc: String): List<String>? {
        val playerRegex = Regex("$playerName\\s*=\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
        val string = playerRegex.find(doc)?.groupValues?.get(1)
        return if (string != null) json.decodeFromString<List<String>>(string) else null
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = VOICES
            entryValues = VOICES_VALUES
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_MERGE_KIND_KEY
            title = "Organisation des différentes voix"
            entries = MERGE_KINDS
            entryValues = MERGE_KINDS_VALUES
            setDefaultValue(PREF_MERGE_KIND_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val VOICES = arrayOf(
            "Préférer VOSTFR",
            "Préférer VF",
            "Afficher VF et VOSTFR",
        )

        private val VOICES_VALUES = arrayOf(
            "vostfr",
            "vf",
            "vostfr,vf",
        )

        private val MERGE_KINDS = arrayOf(
            "Concaténer (ep1VO, ep2VO,..., ep1VF, ep2VF)",
            "Zipper (ep1VO, ep1VF, ep2VO, ep2VF,...)",
        )

        private val MERGE_KINDS_VALUES = arrayOf(
            "concat",
            "zip",
        )

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vostfr"

        private const val PREF_MERGE_KIND_KEY = "merge_kind"
        private const val PREF_MERGE_KIND_DEFAULT = "concat"
    }
}
