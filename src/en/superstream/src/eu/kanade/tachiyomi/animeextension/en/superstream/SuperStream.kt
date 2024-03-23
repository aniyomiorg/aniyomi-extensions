package eu.kanade.tachiyomi.animeextension.en.superstream

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Date

@ExperimentalSerializationApi
class SuperStream : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "SuperStream"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private val hideNsfw = if (preferences.getBoolean(PREF_HIDE_NSFW_KEY, PREF_HIDE_NSFW_DEFAULT)) 1 else 0

    private val superStreamAPI = SuperStreamAPI(json, hideNsfw)

    override val baseUrl = superStreamAPI.apiUrl

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val animes = superStreamAPI.getMainPage(page)
        return animes
    }

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val data = superStreamAPI.load(anime.url)
        val episodes = mutableListOf<SEpisode>()
        val (movie, seriesData) = data
        val (_, series) = seriesData
        movie?.let { mov ->
            mov.id?.let {
                episodes.add(
                    SEpisode.create().apply {
                        url = LinkData(mov.id, mov.box_type ?: 1, 0, 1).toJson()
                        name = "Movie"
                        date_upload = getDateTime(mov.update_time)
                    },
                )
            }
        }
        series?.mapNotNull { ser ->
            ser.id?.let {
                if (ser.source_file!! == 1) {
                    episodes.add(
                        SEpisode.create().apply {
                            url = LinkData(ser.tid ?: ser.id, 2, ser.season, ser.episode).toJson()
                            episode_number = ser.episode?.toFloat() ?: 0F
                            name = "Season ${ser.season} Ep ${ser.episode}: ${ser.title}"
                            date_upload = getDateTime(ser.update_time)
                        },
                    )
                }
            }
        }
        return episodes
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val animes = superStreamAPI.getLatest(page)
        return animes
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videos = superStreamAPI.loadLinks(episode.url)
        val sortedVideos = videos.sort()
        return sortedVideos
    }

    override fun videoListParse(response: Response) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")

        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val searchResult = superStreamAPI.search(page, query)
        return AnimesPage(searchResult, searchResult.size == 20)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val data = superStreamAPI.load(anime.url)
        val ani = SAnime.create()
        val (movie, seriesData) = data
        val (detail, _) = seriesData
        if (movie != null) {
            ani.title = movie.title ?: "Movie"
            ani.genre = movie.cats!!.split(",").let { genArray ->
                genArray.joinToString { genList -> genList.replaceFirstChar { gen -> gen.uppercase() } }
            }
            ani.status = SAnime.COMPLETED
            ani.author = movie.writer?.substringBefore(",")
            ani.artist = movie.director?.substringBefore(",")

            ani.description = (if (movie.description.isNullOrBlank()) "" else movie.description + "\n\n") +
                (if (movie.released.isNullOrBlank().not()) "Released: " + movie.released else "") +
                (
                    "\n\nWriters: " + (
                        movie.writer?.substringBefore("\n")
                            ?.split(",")?.distinct()?.joinToString { it } ?: ""
                        ) +
                        "\n\nDirectors: " + (
                            movie.director?.substringBefore("\n")
                                ?.split(",")?.distinct()?.joinToString { it } ?: ""
                            )
                    )
        } else {
            detail?.let {
                ani.title = it.title ?: "Series"
                ani.genre = it.cats!!.split(",").let { genArray ->
                    genArray.joinToString { genList -> genList.replaceFirstChar { gen -> gen.uppercase() } }
                }
                ani.description = it.description
                ani.status = SAnime.COMPLETED
                ani.author = it.writer?.substringBefore("\n")?.substringBefore(",")
                ani.artist = it.director?.substringBefore("\n")?.substringBefore(",")

                ani.description = (if (it.description.isNullOrBlank()) "" else it.description + "\n\n") +
                    (if (it.released.isNullOrBlank().not()) "Released: " + it.released else "") +
                    (
                        "\n\nWriters: " + (
                            it.writer?.substringBefore("\n")
                                ?.split(",")?.distinct()?.joinToString { wrt -> wrt } ?: ""
                            ) +
                            "\n\nDirectors: " + (
                                it.director?.substringBefore("\n")
                                    ?.split(",")?.distinct()?.joinToString { dir -> dir } ?: ""
                                )
                        )
            }
        }
        return ani
    }
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("4k", "1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("4k", "1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_NSFW_KEY
            title = "Hide NSFW content"
            setDefaultValue(PREF_HIDE_NSFW_DEFAULT)
            summary = "requires restart"

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)
    }

    private fun LinkData.toJson(): String {
        return json.encodeToString(this)
    }

    private fun getDateTime(s: Int?): Long {
        return try {
            Date(s!!.toLong() * 1000).time
        } catch (e: Exception) {
            0L
        }
    }
}

private const val PREF_HIDE_NSFW_KEY = "pref_hide_nsfw"
private const val PREF_HIDE_NSFW_DEFAULT = true
