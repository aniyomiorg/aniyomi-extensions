package eu.kanade.tachiyomi.animeextension.tr.animeler

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.AnimeEpisodes
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.FullAnimeDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SearchRequestDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SearchResponseDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SingleDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.SourcesDto
import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.VideoDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Animeler : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Animeler"

    override val baseUrl = "https://animeler.me"

    override val lang = "tr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = searchOrderBy("total_kiranime_views", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val results = response.parseAs<SearchResponseDto>()
        val doc = Jsoup.parseBodyFragment(results.data)
        val animes = doc.select("div.w-full:has(div.kira-anime)").map {
            SAnime.create().apply {
                thumbnail_url = it.selectFirst("img")?.attr("src")
                with(it.selectFirst("h3 > a")!!) {
                    title = text()
                    setUrlWithoutDomain(attr("href"))
                }
            }
        }

        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = page < results.pages
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = searchOrderBy("kiranime_anime_updated", page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun getFilterList() = AnimelerFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimelerFilters.getSearchParameters(filters)
        val (meta, orderBy) = when (params.orderBy) {
            "date", "title" -> Pair(null, params.orderBy)
            else -> Pair(params.orderBy, "meta_value_num")
        }

        val single = SingleDto(
            paged = page,
            key = meta,
            order = params.order,
            orderBy = orderBy,
            season = params.season.ifEmpty { null },
            year = params.year.ifEmpty { null },
        )

        val taxonomies = with(params) {
            listOf(genres, status, producers, studios, types).filter {
                it.terms.isNotEmpty()
            }
        }

        val requestDto = SearchRequestDto(single, query, query, taxonomies)
        val requestData = json.encodeToString(requestDto)
        return searchRequest(requestData, page)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    private fun searchOrderBy(order: String, page: Int): Request {
        val body = """
            {
              "keyword": "",
              "query": "",
              "single": {
                "paged": $page,
                "orderby": "meta_value_num",
                "meta_key": "$order",
                "order": "desc"
              },
              "tax": []
            }
        """.trimIndent()
        return searchRequest(body, page)
    }

    private fun searchRequest(data: String, page: Int): Request {
        val body = data.toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/wp-json/kiranime/v1/anime/advancedsearch?_locale=user&page=$page", headers, body)
    }

    // =========================== Anime Details ============================
    private inline fun <reified T> Response.parseBody(): T {
        val body = use { it.body.string() }
            .substringAfter("const anime = ")
            .substringBefore("};") + "}"

        return json.decodeFromString<T>(body)
    }

    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val animeDto = response.parseBody<FullAnimeDto>()

        setUrlWithoutDomain(animeDto.url)
        thumbnail_url = animeDto.thumbnail
        title = animeDto.title
        artist = animeDto.studios
        author = animeDto.producers
        genre = animeDto.genres
        status = when {
            animeDto.meta.aired.orEmpty().contains(" to ") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        description = buildString {
            animeDto.post.post_content?.also { append(it + "\n") }

            with(animeDto.meta) {
                score?.takeIf(String::isNotBlank)?.also { append("\nScore: $it") }
                native?.takeIf(String::isNotBlank)?.also { append("\nNative: $it") }
                synonyms?.takeIf(String::isNotBlank)?.also { append("\nDiğer İsimleri: $it") }
                rate?.takeIf(String::isNotBlank)?.also { append("\nRate: $it") }
                premiered?.takeIf(String::isNotBlank)?.also { append("\nPremiered: $it") }
                aired?.takeIf(String::isNotBlank)?.also { append("\nYayınlandı: $it") }
                duration?.takeIf(String::isNotBlank)?.also { append("\nSüre: $it") }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = response.parseBody<AnimeEpisodes>().episodes

        return episodes.map {
            SEpisode.create().apply {
                setUrlWithoutDomain(it.url)
                name = "Bölüm " + it.meta.number
                episode_number = it.meta.number.toFloat()
                date_upload = it.date.toDate()
            }
        }
    }

    // ============================ Video Links =============================
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val iframeUrl = doc.selectFirst("div.episode-player-box > iframe")
            ?.run { attr("data-src").ifBlank { attr("src") } }
            ?: doc.selectFirst("script:containsData(embedUrl)")
                ?.data()
                ?.substringAfter("\"embedUrl\": \"")
                ?.substringBefore('"')
            ?: throw Exception("No video available.")

        val playerBody = { it: String ->
            FormBody.Builder()
                .add("hash", iframeUrl.substringAfter("/video/"))
                .add("r", "$baseUrl/")
                .add("s", it)
                .build()
        }

        val headers = headersBuilder()
            .add("Origin", "https://" + iframeUrl.toHttpUrl().host) // just to be sure
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val actionUrl = "$iframeUrl?do=getVideo"

        val players = client.newCall(POST(actionUrl, headers, playerBody(""))).execute()
            .parseAs<SourcesDto>()

        val chosenHosts = preferences.getStringSet(PREF_HOSTS_SELECTION_KEY, SUPPORTED_PLAYERS)!!

        val filteredSources = players.sourceList.entries.filter { source ->
            chosenHosts.any { it.contains(source.value, true) }
        }

        return filteredSources.parallelCatchingFlatMapBlocking {
            val body = playerBody(it.key)
            val res = client.newCall(POST(actionUrl, headers, body)).await()
                .parseAs<VideoDto>()
            videosFromUrl(res.videoSrc)
        }
    }

    private fun videosFromUrl(url: String): List<Video> {
        return when {
            "dood" in url -> doodExtractor.videosFromUrl(url)
            "drive.google" in url -> {
                val newUrl = "https://gdriveplayer.to/embed2.php?link=$url"
                gdrivePlayerExtractor.videosFromUrl(newUrl, "GdrivePlayer", headers)
            }
            "filemoon." in url -> filemoonExtractor.videosFromUrl(url)
            "ok.ru" in url || "odnoklassniki.ru" in url -> okruExtractor.videosFromUrl(url)
            "streamtape" in url -> streamtapeExtractor.videoFromUrl(url)?.let(::listOf)
            "sibnet" in url -> sibnetExtractor.videosFromUrl(url)
            "streamlare" in url -> streamlareExtractor.videosFromUrl(url)
            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
            "voe." in url -> voeExtractor.videosFromUrl(url)
            "vudeo." in url -> vudeoExtractor.videosFromUrl(url)
            else -> null
        } ?: emptyList()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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
            key = PREF_HOSTS_SELECTION_KEY
            title = PREF_HOSTS_SELECTION_TITLE
            entries = PREF_HOSTS_SELECTION_ENTRIES
            entryValues = PREF_HOSTS_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTS_SELECTION_DEFAULT)

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

    private val qualityRegex by lazy { Regex("""(\d+)p""") }
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),

        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "id:"

        private val SUPPORTED_PLAYERS = setOf(
            "doodstream.com",
            "G.Drive",
            "Moon",
            "ok.ru",
            "S.Tape",
            "Sibnet",
            "Streamlare",
            "UQload",
            "Voe",
            "vudeo",
        )

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_HOSTS_SELECTION_KEY = "pref_hosts_selection"
        private const val PREF_HOSTS_SELECTION_TITLE = "Disable/enable video hosts"
        private val PREF_HOSTS_SELECTION_ENTRIES = SUPPORTED_PLAYERS.toTypedArray()
        private val PREF_HOSTS_SELECTION_DEFAULT = SUPPORTED_PLAYERS
    }
}
