package eu.kanade.tachiyomi.animeextension.de.moflixstream

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.AnimeDetailsDto
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.EpisodeListDto
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.EpisodePageDto
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.ItemInfo
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.PopularPaginationDto
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.SearchDto
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.SeasonPaginationDto
import eu.kanade.tachiyomi.animeextension.de.moflixstream.dto.VideoResponseDto
import eu.kanade.tachiyomi.animeextension.de.moflixstream.extractors.UnpackerExtractor
import eu.kanade.tachiyomi.animeextension.de.moflixstream.extractors.VidGuardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamvidextractor.StreamVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.Exception

class MoflixStream : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Moflix-Stream"

    override val baseUrl = "https://moflix-stream.xyz"

    override val lang = "de"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    private val apiUrl = "$baseUrl/api/v1"

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(
        "$apiUrl/channel/345?returnContentOnly=true&restriction=&order=rating:desc&paginate=simple&perPage=50&query=&page=$page",
        headers = Headers.headersOf("referer", "$baseUrl/movies?order=rating%3Adesc"),
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val pagination = response.parseAs<PopularPaginationDto>().pagination

        val animeList = pagination.data.parseItems()
        val hasNextPage = pagination.current_page < pagination.next_page ?: 1
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET(
        "$apiUrl/search/$query?query=$query",
        headers = Headers.headersOf("referer", "$baseUrl/search/$query"),
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<SearchDto>()
        val animeList = data.results.parseItems()
        return AnimesPage(animeList, false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val data = response.parseAs<AnimeDetailsDto>().title

        setUrlWithoutDomain("$apiUrl/titles/${data.id}?$ANIME_URL_QUERIES")
        title = data.name
        thumbnail_url = data.thumbnail
        genre = data.genres.joinToString { it.name }
        description = data.description
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<EpisodePageDto>()
        val id = data.title.id
        val seasonsUrl = "$apiUrl/titles/$id/seasons"

        val seasons = data.seasons

        return when (seasons) {
            null -> {
                SEpisode.create().apply {
                    name = "Film"
                    episode_number = 1F
                    setUrlWithoutDomain(response.request.url.toString())
                }.let(::listOf)
            }
            else -> {
                val seasonsList = buildList {
                    addAll(seasons.data)

                    var nextPage = seasons.next_page
                    while (nextPage != null) {
                        val req = GET("$seasonsUrl?perPage=8&query=&page=$nextPage", headers)
                        val res = client.newCall(req).execute().parseAs<SeasonPaginationDto>()
                        addAll(res.pagination.data)
                        nextPage = res.pagination.next_page
                    }
                }

                seasonsList.flatMap { season ->
                    val seasonNum = season.number
                    val episodesRequest = GET("$seasonsUrl/$seasonNum?load=episodes,primaryVideo", headers)
                    val episodesData = client.newCall(episodesRequest).execute()
                        .parseAs<EpisodeListDto>()
                        .episodes
                        .data
                        .reversed()

                    episodesData.map { episode ->
                        SEpisode.create().apply {
                            val epNum = episode.episode_number
                            episode_number = epNum.toFloat()
                            name = "Staffel $seasonNum Folge $epNum : " + episode.name
                            setUrlWithoutDomain("$seasonsUrl/$seasonNum/episodes/$epNum?load=videos,compactCredits,primaryVideo")
                        }
                    }
                }
            }
        }
    }

    // ============================ Video Links =============================
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamvidExtractor by lazy { StreamVidExtractor(client) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val luluExtractor by lazy { UnpackerExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val selection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        val data = response.parseAs<VideoResponseDto>().run { episode ?: title }

        return data!!.videos.flatMap { video ->
            val name = video.name
            val url = video.src
            runCatching { getVideosFromUrl(url, name, selection) }.getOrElse { emptyList() }
        }.ifEmpty { throw Exception("No videos!") }
    }

    private fun getVideosFromUrl(url: String, name: String, selection: Set<String>): List<Video> {
        return when {
            name.contains("Streamtape") && selection.contains("stape") -> {
                streamtapeExtractor.videoFromUrl(url)?.let(::listOf) ?: emptyList()
            }
            name.contains("Streamvid") && selection.contains("svid") -> {
                streamvidExtractor.videosFromUrl(url)
            }
            name.contains("Highstream") && selection.contains("hstream") -> {
                streamvidExtractor.videosFromUrl(url, prefix = "Highstream - ")
            }
            name.contains("VidGuard") && selection.contains("vidg") -> {
                vidguardExtractor.videosFromUrl(url)
            }
            name.contains("Filelions") && selection.contains("flions") -> {
                streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions - $it" })
            }
            name.contains("LuluStream") && selection.contains("lstream") -> {
                luluExtractor.videosFromUrl(url, "LuluStream")
            }
            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(hoster) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun List<ItemInfo>.parseItems() = map {
        SAnime.create().apply {
            title = it.name
            setUrlWithoutDomain("$apiUrl/titles/${it.id}?$ANIME_URL_QUERIES")
            thumbnail_url = it.thumbnail
        }
    }

    companion object {
        private const val ANIME_URL_QUERIES = "load=images,genres,productionCountries,keywords,videos,primaryVideo,seasons,compactCredits"

        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_TITLE = "Standard-Hoster"
        private const val PREF_HOSTER_DEFAULT = "https://streamtape"
        private val PREF_HOSTER_ENTRIES = arrayOf("Streamtape", "VidGuard", "Streamvid", "Highstream", "Filelions", "LuluStream")
        private val PREF_HOSTER_VALUES = arrayOf("https://streamtape", "https://moflix-stream", "https://streamvid", "https://highstream", "https://moflix-stream", "https://luluvdo")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "auswählen"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Streamtape", "VidGuard", "Streamvid", "Highstream", "Filelions", "LuluStream")
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("stape", "vidg", "svid", "hstream", "flions", "lstream")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_VALUES.toSet() }
    }
}
