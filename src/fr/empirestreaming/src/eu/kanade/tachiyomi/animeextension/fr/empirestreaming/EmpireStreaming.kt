package eu.kanade.tachiyomi.animeextension.fr.empirestreaming

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.MovieInfoDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.SearchResultsDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.SerieEpisodesDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.extractors.EplayerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.Exception

class EmpireStreaming : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EmpireStreaming"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.block-forme:has(p:contains(Les plus vus)) div.content-card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.play")!!.attr("abs:href"))
        thumbnail_url = baseUrl + element.selectFirst("picture img")!!.attr("data-src")
        title = element.selectFirst("h3.line-h-s, p.line-h-s")!!.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesSelector() = "div.block-forme:has(p:contains(Ajout récents)) div.content-card"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")
    override fun searchAnimeNextPageSelector() = null
    override fun searchAnimeSelector() = throw Exception("not used")
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")
    override fun searchAnimeParse(response: Response) = throw Exception("not used")

    private val searchItems by lazy {
        client.newCall(GET("$baseUrl/api/views/contenitem", headers)).execute()
            .use {
                json.decodeFromString<SearchResultsDto>(it.body.string()).items
            }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val entriesPages = searchItems.filter { it.title.contains(query, true) }
            .sortedBy { it.title }
            .chunked(30) // to prevent exploding the user screen with 984948984 results

        val hasNextPage = entriesPages.size > page
        val entries = entriesPages.getOrNull(page - 1)?.map {
            SAnime.create().apply {
                title = it.title
                setUrlWithoutDomain("/${it.urlPath}")
                thumbnail_url = "$baseUrl/images/medias/${it.thumbnailPath}"
            }
        } ?: emptyList()

        return AnimesPage(entries, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h3#title_media")!!.text()
        val thumbPath = document.html().substringAfter("backdrop\":\"").substringBefore('"')
        thumbnail_url = "$baseUrl/images/medias/$thumbPath".replace("\\", "")
        genre = document.select("div > button.bc-w.fs-12.ml-1.c-b").eachText().joinToString()
        description = document.selectFirst("div.target-media-desc p.content")!!.text()
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val scriptJson = doc.selectFirst("script:containsData(window.empire):containsData(data:)")!!
            .data()
            .substringAfter("data:")
            .substringBefore("countpremiumaccount:")
            .substringBeforeLast(",")
        return if (doc.location().contains("serie")) {
            val data = json.decodeFromString<SerieEpisodesDto>(scriptJson)
            data.seasons.values
                .flatMap { it.map(::episodeFromObject) }
                .sortedByDescending { it.episode_number }
        } else {
            val data = json.decodeFromString<MovieInfoDto>(scriptJson)
            SEpisode.create().apply {
                name = data.title
                date_upload = data.date.toDate()
                url = data.videos.encode()
                episode_number = 1F
            }.let(::listOf)
        }
    }

    private fun episodeFromObject(obj: EpisodeDto) = SEpisode.create().apply {
        name = "Saison ${obj.season} Épisode ${obj.episode} : ${obj.title}"
        episode_number = "${obj.season}.${obj.episode}".toFloatOrNull() ?: 1F
        url = obj.video.encode()
        date_upload = obj.date.toDate()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // ============================ Video Links =============================
    // val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        val videos = episode.url.split(", ").parallelMap {
            runCatching {
                val (id, type, hoster) = it.split("|")
                if (hoster !in hosterSelection) return@parallelMap emptyList()
                videosFromPath("$id/$type", hoster)
            }.getOrElse { emptyList() }
        }.flatten().sort()
        return videos
    }

    private fun videosFromPath(path: String, hoster: String): List<Video> {
        val url = client.newCall(GET("$baseUrl/player_submit/$path", headers)).execute()
            .use { it.body.string() }
            .substringAfter("window.location.href = \"")
            .substringBefore('"')

        return when (hoster) {
            "doodstream" -> DoodExtractor(client).videosFromUrl(url)
            "voe" -> VoeExtractor(client).videoFromUrl(url)?.let(::listOf)
            "Eplayer" -> EplayerExtractor(client).videosFromUrl(url)
            else -> null
        } ?: emptyList()
    }

    override fun videoListParse(response: Response) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(hoster) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private fun List<VideoDto>.encode() = joinToString { it.encoded }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private const val PREF_DOMAIN_DEFAULT = "https://empire-stream.net"
        private val PREF_DOMAIN_ENTRIES = arrayOf("https://empire-stream.net", "https://empire-streaming.app")
        private val PREF_DOMAIN_VALUES = PREF_DOMAIN_ENTRIES

        private const val PREF_HOSTER_KEY = "preferred_hoster_new"
        private const val PREF_HOSTER_TITLE = "Hébergeur standard"
        private const val PREF_HOSTER_DEFAULT = "Voe"
        private val PREF_HOSTER_ENTRIES = arrayOf("Voe", "Dood", "E-Player")
        private val PREF_HOSTER_VALUES = PREF_HOSTER_ENTRIES

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualité préférée" // DeepL
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "800p", "720p", "480p")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection_new"
        private const val PREF_HOSTER_SELECTION_TITLE = "Sélectionnez l'hôte"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Voe", "Dood", "Eplayer")
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("voe", "doodstream", "Eplayer")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_VALUES.toSet() }
    }
}
