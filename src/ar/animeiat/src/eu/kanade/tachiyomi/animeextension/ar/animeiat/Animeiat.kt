package eu.kanade.tachiyomi.animeextension.ar.animeiat

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.animeiat.dto.AnimeEpisodesList
import eu.kanade.tachiyomi.animeextension.ar.animeiat.dto.AnimePageResponse
import eu.kanade.tachiyomi.animeextension.ar.animeiat.dto.LatestAnimeResponse
import eu.kanade.tachiyomi.animeextension.ar.animeiat.dto.PopularAnimeResponse
import eu.kanade.tachiyomi.animeextension.ar.animeiat.dto.StreamLinks
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Base64

class Animeiat : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Animeiat"

    override val baseUrl = "https://api.animeiat.co/v1"

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<PopularAnimeResponse>(response.body.string())
        val animeList = responseJson.data.map {
            SAnime.create().apply {
                url = "/anime/${it.slug}"
                title = it.anime_name
                thumbnail_url = "https://api.animeiat.co/storage/${it.poster_path}"
            }
        }
        val hasNextPage = responseJson.meta.current_page < responseJson.meta.last_page
        return AnimesPage(animeList, hasNextPage)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime?page=$page")

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val pagesUrl = response.request.url.toString() + "/episodes"
        val firstPage = client.newCall(GET(pagesUrl)).execute()
        fun addEpisodes(res: Response) {
            val jr = json.decodeFromString<AnimeEpisodesList>(res.body.string())
            episodeList.addAll(
                jr.data.map {
                    SEpisode.create().apply {
                        name = it.title
                        episode_number = it.number
                        url = "episode/${it.slug}"
                    }
                },
            )
            jr.links.next?.let {
                addEpisodes(client.newCall(GET(it)).execute())
            }
        }
        addEpisodes(firstPage)
        return episodeList.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl/${episode.url}")

    @RequiresApi(Build.VERSION_CODES.O)
    override fun videoListParse(response: Response): List<Video> {
        val playerHash = response.body.string().substringAfter("\"hash\":\"").substringBefore("\"")
        val playerID = String(Base64.getDecoder().decode(playerHash)).split("\"").reversed()[1]
        val playerUrls = client.newCall(GET("$baseUrl/video/$playerID")).execute()
        val jr = json.decodeFromString<StreamLinks>(playerUrls.body.string()).data
        return jr.sources.map {
            Video(
                it.file,
                "${it.label} ${it.quality}",
                it.file,
            )
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val details = json.decodeFromString<AnimePageResponse>(response.body.string()).data
        val anime = SAnime.create().apply {
            url = "/anime/${details.slug}"
            title = details.anime_name
            status = when (details.status) {
                "ongoing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            author = details.studios.joinToString { it.name }
            genre = details.genres.joinToString { it.name }
            description = details.story
            thumbnail_url = "https://api.animeiat.co/storage/${details.poster_path}"
        }
        return anime
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            return GET("$baseUrl/anime?q=$query&page=$page")
        } else {
            var type = ""
            var status = ""
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is TypeCategoryList -> {
                        type = getTypeFilterList()[filter.state].query
                    }
                    is StatCategoryList -> {
                        status = getStatFilterList()[filter.state].query
                    }
                    else -> {}
                }
            }
            type = if (type.isEmpty()) "" else "&type=$type"
            status = if (status.isEmpty()) "" else "&status=$status"
            return GET("$baseUrl/anime?page=$page$type$status")
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<LatestAnimeResponse>(response.body.string())
        val animeList = responseJson.data.map {
            SAnime.create().apply {
                url = "/anime/${it.slug.substringBefore("-episode-")}"
                title = it.title
                thumbnail_url = "https://api.animeiat.co/storage/${it.poster_path}"
            }
        }
        val hasNextPage = responseJson.meta.current_page < responseJson.meta.last_page
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home/sticky-episodes?page=$page")

    // ============================== filters ==============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("فلترة الموقع"),
        TypeCategoryList(typeFilterList),
        StatCategoryList(statFilterList),
    )
    private class TypeCategoryList(categories: Array<String>) : AnimeFilter.Select<String>("النوع", categories)
    private class StatCategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الحالة", categories)

    private data class CatUnit(val name: String, val query: String)

    private val typeFilterList = getTypeFilterList().map { it.name }.toTypedArray()
    private val statFilterList = getStatFilterList().map { it.name }.toTypedArray()

    private fun getTypeFilterList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("فيلم", "movie"),
        CatUnit("اوفا", "ova"),
        CatUnit("اونا", "ona"),
        CatUnit("حلقة خاصة", "special"),
    )
    private fun getStatFilterList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("جارى رفعة", "uploading"),
        CatUnit("مكتمل", "completed"),
        CatUnit("يعرض حاليا", "ongoing"),
        CatUnit("قريبا", "upcoming"),
    )

    // =============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
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
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
