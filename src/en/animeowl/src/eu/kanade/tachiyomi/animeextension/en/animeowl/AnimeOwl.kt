package eu.kanade.tachiyomi.animeextension.en.animeowl

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animeowl.extractors.OwlExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMap
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.ceil

@ExperimentalSerializationApi
class AnimeOwl : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeOwl"

    override val baseUrl = "https://animeowl.us"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val owlServersExtractor by lazy { OwlExtractor(client, baseUrl) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page")

    override fun popularAnimeSelector(): String = "div#anime-list > div"

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li > a[rel=next]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a.title-link").attr("href"))
            thumbnail_url = element.select("img[data-src]").attr("data-src")
            title = element.select("a.title-link h3").text()
        }
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        advancedSearchAnime(page, sort = Sort.Latest)

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String =
        throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String =
        throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = advancedSearchAnime(page, sort = Sort.Search, query = query)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException()

    override fun searchAnimeSelector(): String =
        throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String =
        throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime =
        throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        genre = document.select("div.genre > a").joinToString { it.text() }
        author = document.select("div.type > a").text()
        status = parseStatus(document.select("div.status > span").text())
        description = buildString {
            document.select("div.anime-desc.desc-content").text()
                .takeIf { it.isNotBlank() }
                ?.let {
                    appendLine(it)
                    appendLine()
                }
            document.select("h4.anime-alternatives").text()
                .takeIf { it.isNotBlank() }
                ?.let {
                    append("Other name(s): ")
                    append(it)
                }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeId = response.asJsoup().select("div#unq-anime-id").attr("animeId")
        val episodes = client.newCall(
            GET("$baseUrl/api/anime/$animeId/episodes"),
        ).execute()
            .parseAs<EpisodeResponse>()

        return listOf(
            episodes.sub.map { it.copy(lang = "Sub") },
            episodes.dub.map { it.copy(lang = "Dub") },
        ).flatten()
            .groupBy { it.name }
            .map { (epNum, epList) ->
                SEpisode.create().apply {
                    url = LinkData(
                        epList.map { ep ->
                            Link(
                                ep.buildUrl(episodes.subSlug, episodes.dubSlug),
                                ep.lang!!,
                            )
                        },
                    ).toJsonString()
                    episode_number = epNum.toFloatOrNull() ?: 0F
                    name = "Episode $epNum"
                }
            }
            .sortedByDescending { it.episode_number }
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        json.decodeFromString<LinkData>(episode.url)
            .links.parallelFlatMap { owlServersExtractor.extractOwlVideo(it) }.sort()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    enum class Sort(val code: String) {
        Latest("1"),
        Search("4"),
    }

    private fun advancedSearchAnime(
        page: Int,
        sort: Sort,
        query: String? = "",
        limit: Int? = 30,
    ): AnimesPage {
        val body = buildJsonObject {
            put("lang22", 3)
            put("value", query)
            put("sortt", sort.code)
            put("limit", limit)
            put("page", page - 1)
            putJsonObject("selected") {
                putJsonArray("type") { emptyList<String>() }
                putJsonArray("sort") { emptyList<String>() }
                putJsonArray("year") { emptyList<String>() }
                putJsonArray("genre") { emptyList<String>() }
                putJsonArray("season") { emptyList<String>() }
                putJsonArray("status") { emptyList<String>() }
                putJsonArray("country") { emptyList<String>() }
                putJsonArray("language") { emptyList<String>() }
            }
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val result = client.newCall(
            POST("$baseUrl/api/advance-search", body = body, headers = headers),
        ).execute()
            .parseAs<SearchResponse>()

        val nextPage = ceil(result.total.toFloat() / limit!!).toInt() > page
        val animes = result.results.map { anime ->
            SAnime.create().apply {
                setUrlWithoutDomain("/anime/${anime.animeSlug}?mal=${anime.malId}")
                thumbnail_url = "$baseUrl${anime.image}"
                title = anime.animeName
            }
        }
        return AnimesPage(animes, nextPage)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun EpisodeResponse.Episode.buildUrl(subSlug: String, dubSlug: String): String =
        when (lang) {
            "dub" -> dubSlug
            else -> subSlug
        }.let { "$baseUrl/watch/$it/$episodeIndex" }

    private fun parseStatus(statusString: String): Int =
        when (statusString) {
            "Currently Airing", "Not yet aired" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_LIST
            entryValues = PREF_QUALITY_LIST
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
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_TYPES
            entryValues = PREF_LANG_TYPES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_LIST
            entryValues = PREF_SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
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
        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_TITLE = "Preferred type"
        private const val PREF_LANG_DEFAULT = "Sub"
        private val PREF_LANG_TYPES = arrayOf("Sub", "Dub")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "Luffy"
        private val PREF_SERVER_LIST = arrayOf("Luffy", "Kaido", "Boa")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")
    }
}
