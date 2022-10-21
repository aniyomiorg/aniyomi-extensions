package eu.kanade.tachiyomi.animeextension.de.aniflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.AnimeDetailsDto
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.AnimeDto
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.Episode
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.Release
import eu.kanade.tachiyomi.animeextension.de.aniflix.dto.Season
import eu.kanade.tachiyomi.animeextension.de.aniflix.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Aniflix : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Aniflix"

    override val baseUrl = "https://aniflix.cc"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val refererHeader = Headers.headersOf("Referer", baseUrl)

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(GET(baseUrl + anime.url))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url.replace("api/", ""))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string())
        val newAnime = SAnime.create().apply {
            title = anime.name!!
            setUrlWithoutDomain("$baseUrl/api/show/" + anime.url!!)
            if (anime.coverPortrait != null) {
                thumbnail_url = "$baseUrl/storage/" + anime.coverPortrait
            }
            description = anime.description
            if (anime.airing == 0) {
                status = SAnime.COMPLETED
            } else if (anime.airing == 1) {
                status = SAnime.ONGOING
            }
            genre = anime.genres?.joinToString { it.name!! }
        }
        return newAnime
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/show/new/${page - 1}", refererHeader)

    override fun popularAnimeParse(response: Response) = parseAnimePage(response)

    private fun parseAnimePage(response: Response, singlePage: Boolean = false): AnimesPage {
        val animes = json.decodeFromString(ListSerializer(AnimeDto.serializer()), response.body!!.string())
        if (animes.isEmpty()) return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        for (anime in animes) {
            val newAnime = createAnime(anime)
            animeList.add(newAnime)
        }
        return AnimesPage(animeList, !singlePage)
    }

    private fun createAnime(anime: AnimeDto): SAnime {
        return SAnime.create().apply {
            title = anime.name!!
            setUrlWithoutDomain("$baseUrl/api/show/" + anime.url!!)
            if (anime.coverPortrait != null) {
                thumbnail_url = "$baseUrl/storage/" + anime.coverPortrait
            }
            description = anime.description
            if (anime.airing == 0) {
                status = SAnime.COMPLETED
            } else if (anime.airing == 1) {
                status = SAnime.ONGOING
            }
        }
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/api/show/airing/${page - 1}", refererHeader)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val releases = json.decodeFromString(ListSerializer(Release.serializer()), response.body!!.string()).toMutableList()
        if (releases.isEmpty()) return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        val releaseList = mutableListOf<Int>()
        for (release in releases) {
            if (release.season!!.anime!!.id in releaseList) continue
            releaseList.add(release.season.anime!!.id!!)
            val anime = release.season.anime
            val newAnime = SAnime.create().apply {
                title = anime.name!!
                setUrlWithoutDomain("$baseUrl/api/show/" + anime.url!!)
                if (anime.coverPortrait != null) {
                    thumbnail_url = "$baseUrl/storage/" + anime.coverPortrait
                }
                description = anime.description
                if (anime.airing == 0) {
                    status = SAnime.COMPLETED
                } else if (anime.airing == 1) {
                    status = SAnime.ONGOING
                }
            }
            animeList.add(newAnime)
        }
        return AnimesPage(animeList, true)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = POST(
        url = "$baseUrl/api/show/search",
        headers = refererHeader,
        body = "{\"search\":\"$query\"}".toRequestBody("application/json".toMediaType())
    )

    override fun searchAnimeParse(response: Response) = parseAnimePage(response, singlePage = true)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string())
        if (anime.seasons.isNullOrEmpty()) return emptyList()
        val episodeList = mutableListOf<SEpisode>()
        val animeUrl = anime.url!!
        for (season in anime.seasons) {
            val episodes = season.episodes!!.toMutableList()
            var page = 1
            while (episodes.size < season.length!!) {
                val seasonPart = json.decodeFromString(
                    Season.serializer(),
                    client.newCall(
                        GET("$baseUrl/api/show/$animeUrl/${season.id!!}/$page")
                    ).execute().body!!.string()
                )
                page++
                episodes.addAll(seasonPart.episodes!!)
            }
            for (episode in episodes) {
                val newEpisode = SEpisode.create().apply {
                    setUrlWithoutDomain("$baseUrl/api/episode/show/$animeUrl/season/${season.number!!}/episode/${episode.number}")
                    episode_number = episode.number!!.toFloat()
                    name = "Staffel ${season.number}: Folge ${episode.number}"
                }
                episodeList.add(newEpisode)
            }
        }
        return episodeList.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val streams = json.decodeFromString(Episode.serializer(), response.body!!.string()).streams
        if (streams.isNullOrEmpty()) return emptyList()
        val videoList = mutableListOf<Video>()
        for (stream in streams) {
            val quality = "${stream.hoster?.name}, ${stream.lang}"
            val link = stream.link ?: return emptyList()
            val hosterSelection = preferences.getStringSet("hoster_selection", setOf("dood", "stape", "voe", "slare"))
            when {
                link.contains("https://dood") && hosterSelection?.contains("dood") == true -> {
                    val video = try { DoodExtractor(client).videoFromUrl(link, quality, false) } catch (e: Exception) { null }
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                link.contains("https://streamtape") && hosterSelection?.contains("stape") == true -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(link, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                link.contains("https://voe.sx") && hosterSelection?.contains("voe") == true -> {
                    val video = VoeExtractor(client).videoFromUrl(link, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                link.contains("https://streamlare") && hosterSelection?.contains("slare") == true -> {
                    videoList.addAll(StreamlareExtractor(client).videosFromUrl(link, stream))
                }
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", null)
        val subPreference = preferences.getString("preferred_sub", "SUB")!!
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else otherList += this
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        for (video in otherList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        return newList
    }

    override fun videoUrlParse(response: Response) = throw Exception("not used")

    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Streamtape", "Doodstream", "Voe", "Streamlare")
            entryValues = arrayOf("https://streamtape.com", "https://dood", "https://voe.sx", "https://streamlare.com")
            setDefaultValue("https://streamtape.com")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = "preferred_sub"
            title = "Standardmäßig Sub oder Dub?"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("SUB", "DUB")
            setDefaultValue("SUB")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Hoster auswählen"
            entries = arrayOf("Streamtape", "Doodstream", "Voe", "Streamlare")
            entryValues = arrayOf("stape", "dood", "voe", "slare")
            setDefaultValue(setOf("stape", "dood", "voe", "slare"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
