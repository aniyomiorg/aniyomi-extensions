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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeSama : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Anime-Sama"

    override val baseUrl = "https://anime-sama.fr"

    override val lang = "fr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val database by lazy {
        client.newCall(GET("$baseUrl/catalogue/listing_all.php", headers)).execute()
            .asJsoup().select(".cardListAnime")
    }

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.body.string()
        val page = response.request.url.fragment?.toInt() ?: 0
        val regex = Regex("^\\s*carteClassique\\(\\s*.*?\\s*,\\s*\"(.*?)\".*\\)", RegexOption.MULTILINE)
        val chunks = regex.findAll(doc).chunked(5).toList()
        val seasons = chunks.getOrNull(page - 1)?.flatMap {
            val animeUrl = "$baseUrl/catalogue/${it.groupValues[1]}"
            fetchAnimeSeasons(animeUrl)
        }?.toList().orEmpty()
        return AnimesPage(seasons, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#$page")

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.asJsoup()
        val seasons = animes.select("#containerAjoutsAnimes > div").flatMap {
            val animeUrl = it.getElementsByTag("a").attr("href").toHttpUrl()
            val url = animeUrl.newBuilder()
                .removePathSegment(animeUrl.pathSize - 2)
                .removePathSegment(animeUrl.pathSize - 3)
                .build()
            fetchAnimeSeasons(url.toString())
        }
        return AnimesPage(seasons, false)
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    // =============================== Search ===============================
    override fun getFilterList() = AnimeSamaFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith(PREFIX_SEARCH)) {
            return AnimesPage(fetchAnimeSeasons("$baseUrl/catalogue/${query.removePrefix(PREFIX_SEARCH)}/"), false)
        }
        val params = AnimeSamaFilters.getSearchFilters(filters)
        val elements = database
            .asSequence()
            .filter { it.select("h1, p").fold(false) { v, e -> v || e.text().contains(query, true) } }
            .filter { params.include.all { p -> it.className().contains(p) } }
            .filter { params.exclude.none { p -> it.className().contains(p) } }
            .filter { params.types.fold(params.types.isEmpty()) { v, p -> v || it.className().contains(p) } }
            .filter { params.language.fold(params.language.isEmpty()) { v, p -> v || it.className().contains(p) } }
            .chunked(5)
            .toList()
        if (elements.isEmpty()) return AnimesPage(emptyList(), false)
        val animes = elements[page - 1].flatMap {
            fetchAnimeSeasons(it.getElementsByTag("a").attr("href"))
        }
        return AnimesPage(animes, page < elements.size)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = "$baseUrl${anime.url.substringBeforeLast("/")}"
        val movie = anime.url.split("#").getOrElse(1) { "" }.toIntOrNull()
        val players = VOICES_VALUES.map { fetchPlayers("$animeUrl/$it") }
        val episodes = playersToEpisodes(players)
        return if (movie == null) episodes.reversed() else listOf(episodes[movie])
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val playerUrls = json.decodeFromString<List<List<String>>>(episode.url)
        val videos = playerUrls.flatMapIndexed { i, it ->
            val prefix = "(${VOICES_VALUES[i].uppercase()}) "
            it.parallelCatchingFlatMap { playerUrl ->
                with(playerUrl) {
                    when {
                        contains("sibnet.ru") -> SibnetExtractor(client).videosFromUrl(playerUrl, prefix)
                        contains("vk.") -> VkExtractor(client, headers).videosFromUrl(playerUrl, prefix)
                        contains("sendvid.com") -> SendvidExtractor(client, headers).videosFromUrl(playerUrl, prefix)
                        else -> emptyList()
                    }
                }
            }
        }.sort()
        return videos
    }

    // ============================ Utils =============================
    private fun sanitizeEpisodesJs(doc: String) = doc
        .replace(Regex("[\"\t]"), "") // Fix trash format
        .replace("'", "\"") // Fix quotes
        .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)), "") // Remove block comments
        .replace(Regex("(^|,|\\[)\\s*//.*?$", RegexOption.MULTILINE), "$1") // Remove line comments
        .replace(Regex(",\\s*]"), "]") // Remove trailing comma

    override fun List<Video>.sort(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(voices, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    private fun fetchAnimeSeasons(animeUrl: String): List<SAnime> {
        val res = client.newCall(GET(animeUrl)).execute()
        return fetchAnimeSeasons(res)
    }

    private fun fetchAnimeSeasons(response: Response): List<SAnime> {
        val animeDoc = response.asJsoup()
        val animeUrl = response.request.url
        val animeName = animeDoc.getElementById("titreOeuvre")?.text() ?: ""

        val seasonRegex = Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", RegexOption.MULTILINE)
        val scripts = animeDoc.select("h2 + p + div > script, h2 + div > script").toString()
        val animes = seasonRegex.findAll(scripts).flatMapIndexed { animeIndex, seasonMatch ->
            val (seasonName, seasonStem) = seasonMatch.destructured
            if (seasonStem.contains("film", true)) {
                val moviesUrl = "$animeUrl/$seasonStem"
                val movies = fetchPlayers(moviesUrl).ifEmpty { return@flatMapIndexed emptyList() }
                val movieNameRegex = Regex("^\\s*newSPF\\(\"(.*)\"\\);", RegexOption.MULTILINE)
                val moviesDoc = client.newCall(GET(moviesUrl)).execute().body.string()
                val matches = movieNameRegex.findAll(moviesDoc).toList()
                List(movies.size) { i ->
                    val title = when {
                        animeIndex == 0 && movies.size == 1 -> animeName
                        matches.size > i -> "$animeName ${matches[i].destructured.component1()}"
                        movies.size == 1 -> "$animeName Film"
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

    private fun playersToEpisodes(list: List<List<List<String>>>): List<SEpisode> =
        List(list.fold(0) { acc, it -> maxOf(acc, it.size) }) { episodeNumber ->
            val players = list.map { it.getOrElse(episodeNumber) { emptyList() } }
            SEpisode.create().apply {
                name = "Episode ${episodeNumber + 1}"
                url = json.encodeToString(players)
                episode_number = (episodeNumber + 1).toFloat()
                scanlator = players.mapIndexedNotNull { i, it -> if (it.isNotEmpty()) VOICES_VALUES[i] else null }.joinToString().uppercase()
            }
        }

    private fun fetchPlayers(url: String): List<List<String>> {
        val docUrl = "$url/episodes.js"
        val players = mutableListOf<List<String>>()
        val doc = client.newCall(GET(docUrl)).execute().run {
            if (code != 200) {
                close()
                return listOf()
            }
            body.string()
        }
        val sanitizedDoc = sanitizeEpisodesJs(doc)
        for (i in 1..8) {
            val numPlayers = getPlayers("eps$i", sanitizedDoc)
            if (numPlayers != null) players.add(numPlayers)
        }
        val asPlayers = getPlayers("epsAS", sanitizedDoc)
        if (asPlayers != null) players.add(asPlayers)
        if (players.isEmpty()) return emptyList()
        return List(players[0].size) { i -> players.mapNotNull { it.getOrNull(i) }.distinct() }
    }

    private fun getPlayers(playerName: String, doc: String): List<String>? {
        val playerRegex = Regex("$playerName\\s*=\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
        val string = playerRegex.find(doc)?.groupValues?.get(1)
        return if (string != null) json.decodeFromString<List<String>>(string) else null
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

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
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val VOICES = arrayOf(
            "Préférer VOSTFR",
            "Préférer VF",
        )

        private val VOICES_VALUES = arrayOf(
            "vostfr",
            "vf",
        )

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vostfr"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
