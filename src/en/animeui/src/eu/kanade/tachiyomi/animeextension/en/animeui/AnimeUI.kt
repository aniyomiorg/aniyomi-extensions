package eu.kanade.tachiyomi.animeextension.en.animeui

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeUI : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeUI"

    override val baseUrl = "https://animeui.com"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Host", baseUrl.toHttpUrl().host)
        add("Referer", "$baseUrl/")
    }.build()

    private val docHeaders = headers.newBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Host", baseUrl.toHttpUrl().host)
    }.build()

    private val titlePref by lazy { preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!! }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/home-list", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<HomeListResponse>().trendingAnimes.map { it.toSAnime(baseUrl, titlePref) }
        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animeList = response.parseAs<HomeListResponse>().latestAnimes.map { it.toSAnime(baseUrl, titlePref) }
        return AnimesPage(animeList, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeUIFilters.getSearchParameters(filters)

        val url = "$baseUrl/api/directory".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("genres", params.genres)
            addQueryParameter("years", params.year)
            addQueryParameter("types", params.types)
            addQueryParameter("status", params.status)
            addQueryParameter("title", query)
            addQueryParameter("category", params.category)
        }.build().toString()

        return GET(url, headers = apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<DirectoryResponse>()
        val animeList = data.animes.map { it.toSAnime(baseUrl, titlePref) }
        return AnimesPage(animeList, data.page < data.pages)
    }

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeUIFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime) = GET(baseUrl + anime.url, docHeaders)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.use { it.asJsoup() }
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return SAnime.create()

        return json.decodeFromString<AnimeData>(data).props.pageProps.animeData.toSAnime()
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.use { it.asJsoup() }
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()

        return json.decodeFromString<AnimeData>(data).props.pageProps.animeData.episodes.map {
            it.toSEpisode(response.request.url.pathSegments.last())
        }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers = docHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val parsed = json.decodeFromString<EpisodeData>(data).props.pageProps.episodeData

        val subtitleList = parsed.subtitlesJson?.let {
            json.decodeFromString<List<SubtitleObject>>(it).map { s ->
                Track("$baseUrl/api${s.url}", s.subtitle_name)
            }
        } ?: emptyList()

        val cid = parsed.episode.cid

        return parsed.servers.filter { it.status == 1 }.map {
            val url = it.url.toHttpUrl()
            val videoUrl = url.newBuilder().addPathSegment(cid).build().toString()

            val videoHeaders = headers.newBuilder().apply {
                add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                add("Connection", "keep-alive")
                add("Referer", "$baseUrl/")
            }.build()

            Video(videoUrl, it.name, videoUrl, headers = videoHeaders, subtitleTracks = subtitleList)
        }
    }

    // ============================= Utilities ==============================

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    override fun List<Video>.sort(): List<Video> {
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(server, true) },
        ).reversed()
    }

    companion object {
        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "default"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Tokyo"
        private val SERVER_LIST = arrayOf(
            "Tokyo", "Kyoto", "Nagoya", "Sendai", "Sagara",
            "Nara", "Osaka", "Web", "Noshiro",
        )
    }
    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred title language"
            entries = arrayOf("Site Default", "English", "Japanese")
            entryValues = arrayOf(PREF_TITLE_LANG_DEFAULT, "english", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
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
}
