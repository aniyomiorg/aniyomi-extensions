package eu.kanade.tachiyomi.animeextension.en.animeparadise

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeParadise : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeParadise"

    override val baseUrl = "https://www.animeparadise.moe"

    private val apiUrl = "https://api.animeparadise.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Host", apiUrl.toHttpUrl().host)
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    private val docHeaders = headers.newBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Host", baseUrl.toHttpUrl().host)
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$apiUrl/?sort={\"rate\": -1}", apiHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<AnimeListResponse>().data.map { it.toSAnime(json) }
        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/?sort={\"startDate\": -1 }&type=TV", apiHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        val url = when {
            genreFilter.state != 0 -> apiUrl + genreFilter.toUriPart()
            else -> "$apiUrl/?title=$query"
        }

        return GET(url, headers = apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filters are going to be ignored if using search text"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("<select>", ""),
            Pair("Comedy", "/?genre=\"Comedy\""),
            Pair("Drama", "/?genre=\"Drama\""),
            Pair("Action", "/?genre=\"Action\""),
            Pair("Fantasy", "/?genre=\"Fantasy\""),
            Pair("Supernatural", "/?genre=\"Supernatural\""),
            Pair("Latest Movie", "/?sort={\"startDate\": -1 }&type=MOVIE"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)
        return GET("$baseUrl/anime/${data.slug}", headers = docHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return SAnime.create()

        return json.decodeFromString<AnimeDetails>(data).props.pageProps.data.toSAnime()
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)
        return GET("$apiUrl/anime/${data.id}/episode", headers = apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<EpisodeListResponse>()
        return data.data.map { it.toSEpisode() }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers = docHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val data = json.decodeFromString<VideoData>(
            document.selectFirst("script#__NEXT_DATA__")!!.data(),
        ).props.pageProps

        val subtitleList = data.subtitles?.map {
            Track(it.src, it.label)
        } ?: emptyList()

        val videoListUrl = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("storage")
            addPathSegment(data.animeData.title)
            addPathSegment(data.episode.number)
        }.build().toString()

        val videoObjectList = client.newCall(
            GET(videoListUrl, headers = apiHeaders),
        ).execute().parseAs<VideoList>()

        if (videoObjectList.directUrl == null) {
            throw Exception(videoObjectList.message ?: "Videos not found")
        }

        val videoHeaders = headers.newBuilder().apply {
            add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            add("Host", apiUrl.toHttpUrl().host)
            add("Referer", "$baseUrl/")
        }.build()

        return videoObjectList.directUrl.map {
            val videoUrl = when {
                it.src.startsWith("//") -> "https:${it.src}"
                it.src.startsWith("/") -> apiUrl + it.src
                else -> it.src
            }

            Video(videoUrl, it.label, videoUrl, headers = videoHeaders, subtitleTracks = subtitleList)
        }.ifEmpty { throw Exception("Failed to fetch videos") }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
    // ============================== Settings ==============================

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
    }
}
