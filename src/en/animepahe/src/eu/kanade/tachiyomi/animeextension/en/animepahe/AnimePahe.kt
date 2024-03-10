package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.LatestAnimeDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.ResponseDto
import eu.kanade.tachiyomi.animeextension.en.animepahe.dto.SearchResultDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

class AnimePahe : ConfigurableAnimeSource, AnimeHttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val interceptor = DdosGuardInterceptor(network.client)

    override val client = network.client.newBuilder()
        .addInterceptor(interceptor)
        .build()

    override val name = "AnimePahe"

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    // =========================== Anime Details ============================
    /**
     * This override is necessary because AnimePahe does not provide permanent
     * URLs to its animes, so we need to fetch the anime session every time.
     *
     * @see episodeListRequest
     */
    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.getId()
        // We're using coroutines here to run it inside another thread and
        // prevent android.os.NetworkOnMainThreadException when trying to open
        // webview or share it.
        val session = runBlocking {
            withContext(Dispatchers.IO) {
                fetchSession(anime.title, animeId)
            }
        }
        return GET("$baseUrl/anime/$session?anime_id=$animeId")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("div.title-wrapper > h1 > span")!!.text()
            author = document.selectFirst("div.col-sm-4.anime-info p:contains(Studio:)")
                ?.text()
                ?.replace("Studio: ", "")
            status = parseStatus(document.selectFirst("div.col-sm-4.anime-info p:contains(Status:) a")!!.text())
            thumbnail_url = document.selectFirst("div.anime-poster a")!!.attr("href")
            genre = document.select("div.anime-genre ul li").joinToString { it.text() }
            val synonyms = document.selectFirst("div.col-sm-4.anime-info p:contains(Synonyms:)")
                ?.text()
            description = document.select("div.anime-summary").text() +
                if (synonyms.isNullOrEmpty()) "" else "\n\n$synonyms"
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api?m=airing&page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val latestData = response.parseAs<ResponseDto<LatestAnimeDto>>()
        val hasNextPage = latestData.currentPage < latestData.lastPage
        val animeList = latestData.items.map { anime ->
            SAnime.create().apply {
                title = anime.title
                thumbnail_url = anime.snapshot
                val animeId = anime.id
                setUrlWithoutDomain("/anime/?anime_id=$animeId")
                artist = anime.fansub
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api?m=search&l=8&q=$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchData = response.parseAs<ResponseDto<SearchResultDto>>()
        val animeList = searchData.items.map { anime ->
            SAnime.create().apply {
                title = anime.title
                thumbnail_url = anime.poster
                val animeId = anime.id
                setUrlWithoutDomain("/anime/?anime_id=$animeId")
            }
        }
        return AnimesPage(animeList, false)
    }

    // ============================== Popular ===============================
    // This source doesnt have a popular animes page,
    // so we use latest animes page instead.
    override suspend fun getPopularAnime(page: Int) = getLatestUpdates(page)
    override fun popularAnimeParse(response: Response): AnimesPage = TODO()
    override fun popularAnimeRequest(page: Int): Request = TODO()

    // ============================== Episodes ==============================
    /**
     * This override is necessary because AnimePahe does not provide permanent
     * URLs to its animes, so we need to fetch the anime session every time.
     *
     * @see animeDetailsRequest
     */
    override fun episodeListRequest(anime: SAnime): Request {
        val session = fetchSession(anime.title, anime.getId())
        return GET("$baseUrl/api?m=release&id=$session&sort=episode_desc&page=1")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val session = url.substringAfter("&id=").substringBefore("&")
        return recursivePages(response, session)
    }

    private fun parseEpisodePage(episodes: List<EpisodeDto>, animeSession: String): MutableList<SEpisode> {
        return episodes.map { episode ->
            SEpisode.create().apply {
                date_upload = episode.createdAt.toDate()
                val session = episode.session
                setUrlWithoutDomain("/play/$animeSession/$session")
                val epNum = episode.episodeNumber
                episode_number = epNum
                val epName = if (floor(epNum) == ceil(epNum)) {
                    epNum.toInt().toString()
                } else {
                    epNum.toString()
                }
                name = "Episode $epName"
            }
        }.toMutableList()
    }

    private fun recursivePages(response: Response, animeSession: String): List<SEpisode> {
        val episodesData = response.parseAs<ResponseDto<EpisodeDto>>()
        val page = episodesData.currentPage
        val hasNextPage = page < episodesData.lastPage
        val returnList = parseEpisodePage(episodesData.items, animeSession)
        if (hasNextPage) {
            val nextPage = nextPageRequest(response.request.url.toString(), page + 1)
            returnList += recursivePages(nextPage, animeSession)
        }
        return returnList
    }

    private fun nextPageRequest(url: String, page: Int): Response {
        val request = GET(url.substringBeforeLast("&page=") + "&page=$page")
        return client.newCall(request).execute()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val downloadLinks = document.select("div#pickDownload > a")
        return document.select("div#resolutionMenu > button").mapIndexed { index, btn ->
            val kwikLink = btn.attr("data-src")
            val quality = btn.text()
            val paheWinLink = downloadLinks[index].attr("href")
            getVideo(paheWinLink, kwikLink, quality)
        }
    }

    private fun getVideo(paheUrl: String, kwikUrl: String, quality: String): Video {
        return if (preferences.getBoolean(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT)) {
            val videoUrl = KwikExtractor(client).getHlsStreamUrl(kwikUrl, referer = baseUrl)
            Video(
                videoUrl,
                quality,
                videoUrl,
                headers = Headers.headersOf("referer", "https://kwik.cx"),
            )
        } else {
            val videoUrl = KwikExtractor(client).getStreamUrlFromKwik(paheUrl)
            Video(videoUrl, quality, videoUrl)
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val subPreference = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val shouldBeAv1 = preferences.getBoolean(PREF_AV1_KEY, PREF_AV1_DEFAULT)
        val shouldEndWithEng = subPreference == "eng"

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""\beng\b""").containsMatchIn(it.quality.lowercase()) == shouldEndWithEng },
                { it.quality.lowercase().contains("av1") == shouldBeAv1 },
            ),
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
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
        }
        val domainPref = ListPreference(screen.context).apply {
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
        }
        val subPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val linkPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_LINK_TYPE_KEY
            title = PREF_LINK_TYPE_TITLE
            summary = PREF_LINK_TYPE_SUMMARY
            setDefaultValue(PREF_LINK_TYPE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        val av1Pref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AV1_KEY
            title = PREF_AV1_TITLE
            summary = PREF_AV1_SUMMARY
            setDefaultValue(PREF_AV1_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(domainPref)
        screen.addPreference(subPref)
        screen.addPreference(linkPref)
        screen.addPreference(av1Pref)
    }

    // ============================= Utilities ==============================
    private fun fetchSession(title: String, animeId: String): String {
        return client.newCall(GET("$baseUrl/api?m=search&q=$title"))
            .execute()
            .body.string()
            .substringAfter("\"id\":$animeId")
            .substringAfter("\"session\":\"")
            .substringBefore("\"")
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun SAnime.getId() = url.substringAfterLast("?anime_id=").substringBefore("\"")

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        }.getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preffered_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "360p")

        private const val PREF_DOMAIN_KEY = "preffered_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private const val PREF_DOMAIN_DEFAULT = "https://animepahe.com"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animepahe.com", "animepahe.ru", "animepahe.org")
        private val PREF_DOMAIN_VALUES by lazy {
            PREF_DOMAIN_ENTRIES.map { "https://" + it }.toTypedArray()
        }

        private const val PREF_SUB_KEY = "preffered_sub"
        private const val PREF_SUB_TITLE = "Prefer subs or dubs?"
        private const val PREF_SUB_DEFAULT = "jpn"
        private val PREF_SUB_ENTRIES = arrayOf("sub", "dub")
        private val PREF_SUB_VALUES = arrayOf("jpn", "eng")

        private const val PREF_LINK_TYPE_KEY = "preffered_link_type"
        private const val PREF_LINK_TYPE_TITLE = "Use HLS links"
        private const val PREF_LINK_TYPE_DEFAULT = false
        private val PREF_LINK_TYPE_SUMMARY by lazy {
            """Enable this if you are having Cloudflare issues.
            |Note that this will break the ability to seek inside of the video unless the episode is downloaded in advance.
            """.trimMargin()
        }

        // Big slap to whoever misspelled `preferred`
        private const val PREF_AV1_KEY = "preffered_av1"
        private const val PREF_AV1_TITLE = "Use AV1 codec"
        private const val PREF_AV1_DEFAULT = false
        private val PREF_AV1_SUMMARY by lazy {
            """Enable to use AV1 if available
            |Turn off to never select av1 as preferred codec
            """.trimMargin()
        }
    }
}
