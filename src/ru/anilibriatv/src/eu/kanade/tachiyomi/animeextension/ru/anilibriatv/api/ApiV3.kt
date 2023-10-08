package eu.kanade.tachiyomi.animeextension.ru.anilibriatv.api

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ru.anilibriatv.dto.FilteredEpisode
import eu.kanade.tachiyomi.animeextension.ru.anilibriatv.dto.SingleTitle
import eu.kanade.tachiyomi.animeextension.ru.anilibriatv.dto.TitleList
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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class AnimeDescription(
        val year: String? = null,
        val type: String? = null,
        val rating: Int? = null,
        val votes: Int? = null,
        val description: String? = null,
)

class AniLibriaTVApiV3(
        override val name: String,
        override val baseUrl: String,
) : ConfigurableAnimeSource, AnimeHttpSource() {
    override val lang: String = "ru"

    override val supportsLatest: Boolean = true

    override val client: OkHttpClient = network.cloudflareClient

    private val siteurl: String = "https://anilibria.tv"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiheaders: Headers =
            Headers.Builder()
                    .add("User-Agent", "Aniyomi Anilibria extension v1")
                    .add("Accept", "application/json")
                    .add("Charset", "UTF-8")
                    .build()

    private val json = Json { ignoreUnknownKeys = true }
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Качество по умолчанию"
        private const val PREF_QUALITY_DEFAULT = "480p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref =
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
                }
        screen.addPreference(videoQualityPref)
    }

    override fun popularAnimeRequest(page: Int): Request =
            GET("$baseUrl/title/updates?page=$page&items_per_page=8", apiheaders)

    fun buildDescription(details: SingleTitle): String {
        val description = StringBuilder()
        if (details.blocked?.blocked == true) {
            description.append("🔴 ДАННОЕ АНИМЕ ЗАБЛОКИРОВАНО НА ТЕРРИТОРИИ РФ 🔴\n")
        }
        if (details.blocked?.bakanim == true) {
            description.append("🔴 ДАННОЕ АНИМЕ ЗАБЛОКИРОВАНО КОМПАНИЕЙ ВАКАНИМ 🔴\n")
        }
        description.append("Ссылка: ${siteurl + "/release/" + details.code + ".html"}\n")
        description.append("Год: ${details.season.year}\n")
        description.append("Тип: ${details.type.fullString}\n")
        description.append("Голоса: ${details.team.voice.joinToString(", ")}\n")
        description.append("В избранном: ${details.inFavorites}")
        if (details.description != null) {
            description.append("\n\n${details.description ?: ""}")
        }
        return description.toString()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val details = json.decodeFromString<SingleTitle>(response.body.string())
        return SAnime.create().apply {
            url = siteurl + "/release/" + details.code + ".html"
            title = details.names?.ru ?: details.names?.en ?: "Неизвестно"
            description = buildDescription(details)
            thumbnail_url = siteurl + details.posters?.small?.url
            genre = details.genres.joinToString(", ")
            status =
                    when (details.status.code) {
                        1 -> SAnime.ONGOING
                        2 -> SAnime.COMPLETED
                        4 -> SAnime.ON_HIATUS
                        else -> SAnime.UNKNOWN
                    }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val details = json.decodeFromString<SingleTitle>(response.body.string())
        val episodes = mutableListOf<SEpisode>()
        Log.d("episodeListParse", "------------------------")
        Log.d(
                "episodeListParse",
                "Title: ${details.names?.ru ?: details.names?.en ?: "Неизвестно"}",
        )
        Log.d("episodeListParse", "Code: ${details.code}")
        Log.d("episodeListParse", "Episodes: ${details.player.list}")
        Log.d("episodeListParse", "------------------------")
        details.player.list.forEach {
            val current = it.value
            episodes.add(
                    SEpisode.create().apply {
                        name = "Серия " + current.episode + " - " + (current.name ?: "Без названия")
                        episode_number = current.episode.toFloat()
                        url =
                                "" +
                                        response.request.url +
                                        "&filter=player.list[\"" +
                                        current.episode +
                                        "\"],player.host"
                        date_upload = current.createdTimestamp * 1000
                    },
            )
        }
        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val filtered = json.decodeFromString<FilteredEpisode>(response.body.string())
        Log.d("videoListParse", "------------------------")
        Log.d("videoListParse", "$filtered")
        Log.d("videoListParse", "------------------------")
        val videos = mutableListOf<Video>()
        val host = "https://" + filtered.player.host
        filtered.player.list.forEach {
            val current = it
            if (current != null) {
                val fhd = host + (current.hls?.fhd ?: "")
                val hd = host + (current.hls?.hd ?: "")
                val sd = host + (current.hls?.sd ?: "")
                Log.d("videoListParse", "------------------------")
                Log.d("videoListParse", "FHD: $fhd")
                Log.d("videoListParse", "HD: $hd")
                Log.d("videoListParse", "SD: $sd")
                Log.d("videoListParse", "------------------------")
                if (current.hls?.fhd != null) {
                    videos.add(Video(fhd, "1080p", fhd))
                }
                if (current.hls?.hd != null) {
                    videos.add(Video(hd, "720p", hd))
                }
                if (current.hls?.sd != null) {
                    videos.add(Video(sd, "480p", sd))
                }
            }
        }

        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage =
            throw Exception("latestUpdatesParse")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("latestUpdatesRequest")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = mutableListOf<SAnime>()
        val responseJson = json.decodeFromString<TitleList>(response.body.string())
        responseJson.list.forEach {
            val anime = SAnime.create()
            anime.title = it.names?.ru ?: it.names?.en ?: it.names?.alternative ?: "Неизвестно"
            // if it.posters?.small?.url
            if (it.posters?.small?.url != null) {
                anime.thumbnail_url = siteurl + it.posters?.medium?.url
            }
            anime.url = "/title?id=" + it.id
            animes.add(anime)
        }
        val hasNextPage = responseJson.pagination.currentPage < responseJson.pagination.pages
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Search ==============================
    
    //TODO: add filter by year, genre, text, team, etc
    // override fun getFilterList() =
    //         AnimeFilterList(
    //                 TitleFilter(),
    //                 AnimeFilter.Separator(),
    //         )

    // private class TitleFilter : AnimeFilter.Text("Название", "")

    override fun searchAnimeParse(response: Response): AnimesPage =
            throw Exception("Еще не сделано searchAnimeParse")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
            throw Exception("Еще не сделано searchAnimeRequest")
}
