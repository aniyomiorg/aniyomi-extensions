package eu.kanade.tachiyomi.animeextension.en.superstream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.superstream.SuperStreamAPI.LinkData
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.OkHttpClient
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@ExperimentalSerializationApi
class SuperStream : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "SuperStream"

    override val baseUrl by lazy { preferences.getString("preferred_domain", superStreamAPI.apiUrl)!! }

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val superStreamAPI = SuperStreamAPI(client)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        val animes = superStreamAPI.getMainPage(page)
        return Observable.just(animes)
    }

    override fun popularAnimeRequest(page: Int) = throw Exception("not used")

    override fun popularAnimeParse(response: Response) = throw Exception("not used")

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val data = superStreamAPI.load(anime.url)
        val episodes = mutableListOf<SEpisode>()
        val (movie, seriesData) = data
        val (_, series) = seriesData
        movie?.let { mov ->
            mov.id?.let {
                episodes.add(
                    SEpisode.create().apply {
                        url = LinkData(mov.id, mov.boxType ?: 1, 0, 1).toJson()
                        name = "Movie"
                    }
                )
            }
        }
        series?.mapNotNull { ser ->
            ser.id?.let {
                episodes.add(
                    SEpisode.create().apply {
                        url = LinkData(ser.id, 2, ser.season, ser.episode).toJson()
                        episode_number = ser.episode?.toFloat() ?: 0F
                        name = ser.title ?: ""
                    }
                )
            }
        }
        return Observable.just(episodes)
    }

    override fun episodeListParse(response: Response) = throw Exception("not used")

    override fun latestUpdatesParse(response: Response) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val videos = superStreamAPI.loadLinks(episode.url)
        return Observable.just(videos)
    }

    override fun videoListParse(response: Response) = throw Exception("not used")

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

    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Observable<AnimesPage> {
        val searchResult = superStreamAPI.search(page, query)
        return Observable.just(AnimesPage(searchResult, true))
    }
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    override fun searchAnimeParse(response: Response) = throw Exception("not used")

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val data = superStreamAPI.load(anime.url)
        val ani = SAnime.create()
        val (movie, seriesData) = data
        val (detail, _) = seriesData
        if (movie != null) {
            ani.title = movie.title ?: "Movie"
            ani.genre = movie.cats
            ani.description = movie.description
            ani.status = SAnime.COMPLETED
            ani.author = movie.director

            val releasedDate = "Released: "
            movie.released?.let { date ->
                if (date.isEmpty().not()) {
                    ani.description = when {
                        ani.description.isNullOrBlank() -> releasedDate + movie.released
                        else -> ani.description + "\n\n$releasedDate" + movie.released
                    }
                }
            }
        } else {
            detail?.let {
                ani.title = it.title ?: "Series"
                ani.genre = it.cats
                ani.description = it.description
                ani.status = SAnime.UNKNOWN
                ani.author = it.director

                val releasedDate = "Released: "
                it.released?.let { date ->
                    if (date.isEmpty().not()) {
                        ani.description = when {
                            ani.description.isNullOrBlank() -> releasedDate + it.released
                            else -> ani.description + "\n\n$releasedDate" + it.released
                        }
                    }
                }
            }
        }
        return Observable.just(ani)
    }
    override fun animeDetailsParse(response: Response) = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("Default")
            entryValues = arrayOf(superStreamAPI.apiUrl)
            setDefaultValue(superStreamAPI.apiUrl)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
    }

    private fun Any.toJson(): String {
        if (this is String) return this
        return superStreamAPI.mapper.writeValueAsString(this)
    }
}
