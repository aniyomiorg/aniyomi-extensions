package eu.kanade.tachiyomi.animeextension.de.fireanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.AbsSourceBaseDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.AnimeBaseDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.AnimeDetailsDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.AnimeDetailsWrapperDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.CdnSourceDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.EpisodeListingWrapperDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.EpisodeSourcesDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.HosterSourceDto
import eu.kanade.tachiyomi.animeextension.de.fireanime.dto.VideoLinkDto
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.animeextension.de.fireanime.extractors.FireCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class FireAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "FireAnime"

    override val baseUrl = "https://api.fireani.me"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(120, 1, TimeUnit.MINUTES)
        .build()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ===== POPULAR ANIME =====
    override fun popularAnimeRequest(page: Int): Request = POST(
        "$baseUrl/api/public/airing",
        body = FormBody.Builder()
            .add("langs[0]", "de-DE")
            .build()
    )

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeListJson(response, true)

    // ===== LATEST ANIME =====
    override fun latestUpdatesRequest(page: Int): Request = POST(
        "$baseUrl/api/public/new",
        body = FormBody.Builder()
            .add("langs[0]", "de-DE")
            .add("limit", "30")
            .add("offset", (page - 1).toString())
            .build()
    )

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeListJson(response)

    // ===== ANIME SEARCH =====
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = POST(
        "$baseUrl/api/public/search",
        body = FormBody.Builder()
            .add("q", query)
            .build()
    )

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeListJson(response, true)

    // ===== ANIME LIST PARSING =====
    private fun parseAnimeListJson(response: Response, singlePage: Boolean = false): AnimesPage {
        val animes = json.decodeFromString(ListSerializer(AnimeBaseDto.serializer()), response.body!!.string())
            .distinctBy { it.url }
        return AnimesPage(animes.map { createAnime(it) }, animes.count() > 0 && !singlePage)
    }

    // ===== ANIME DETAILS =====
    override fun animeDetailsRequest(anime: SAnime): Request = POST(
        "$baseUrl/api/public/anime",
        body = FormBody.Builder()
            .add("url", anime.url)
            .build()
    )

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequest(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response, anime).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")

    private fun animeDetailsParse(response: Response, baseAnime: SAnime): SAnime {
        val anime = json.decodeFromString(AnimeDetailsWrapperDto.serializer(), response.body!!.string()).response
        return createAnime(baseAnime, anime)
    }

    // ===== CREATE ANIME =====
    private fun createAnime(anime: AnimeBaseDto): SAnime {
        return SAnime.create().apply {
            title = anime.title
            url = anime.url
            thumbnail_url = "$baseUrl/api/get/img/" + anime.imgPoster + "-normal-poster.webp"
            status = SAnime.UNKNOWN
        }
    }

    private fun createAnime(baseAnime: SAnime, details: AnimeDetailsDto): SAnime {
        return baseAnime.apply {
            description = details.description
            genre = "FSK ${details.fsk}, " + (if (details.votingDouble != null) "%.1f/5 ⭐, ".format(details.votingDouble) else "") + details.genres.joinToString(", ") { it.genre }
        }
    }

    // ===== EPISODE =====
    override fun episodeListRequest(anime: SAnime): Request = POST(
        "$baseUrl/api/public/episodes",
        body = FormBody.Builder()
            .add("url", anime.url)
            .build()
    )

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return if (anime.status != SAnime.LICENSED) {
            client.newCall(episodeListRequest(anime))
                .asObservableSuccess()
                .map { response ->
                    episodeListParse(response, anime.url)
                }
        } else {
            Observable.error(Exception("Licensed - No episodes to show"))
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    private fun episodeListParse(response: Response, animeUrl: String): List<SEpisode> {
        val episodes = json.decodeFromString(EpisodeListingWrapperDto.serializer(), response.body!!.string()).response
        return episodes.mapIndexed { i, ep ->
            SEpisode.create().apply {
                episode_number = ep.episode.toFloat()
                name = if (ep.title.startsWith("Episode")) ep.title else "Episode ${i + 1}: ${ep.title}"
                url = animeUrl + (-1..i).joinToString("") { " " } // Add some spaces so that all episodes are shown
            }
        }.reversed()
    }

    // ===== VIDEO SOURCES =====
    override fun videoListRequest(episode: SEpisode): Request = POST(
        "$baseUrl/api/public/episode",
        body = FormBody.Builder()
            .add("url", episode.url.trim())
            .add("ep", "%.0f".format(episode.episode_number))
            .build()
    )

    override fun videoListParse(response: Response): List<Video> {
        val episode = json.decodeFromString(EpisodeSourcesDto.serializer(), response.body!!.string().substringAfter("\"ep\":").substringBefore(",\"next\""))

        val videos = mutableListOf<Video>()
        videos.addAll(getVideos(episode.cdns, "$baseUrl/api/public/cdn"))
        videos.addAll(getVideos(episode.hosters, "$baseUrl/api/public/link"))

        return videos
    }

    private fun getVideos(sources: List<AbsSourceBaseDto>, apiUrl: String): List<Video> {
        return sources.mapNotNull { source ->
            val linkRequest = POST(
                apiUrl,
                body = FormBody.Builder()
                    .add("id", source.id.toString())
                    .build()
            )
            val link = json.decodeFromString(VideoLinkDto.serializer(), client.newCall(linkRequest).execute().body!!.string()).url

            val sourceName = if (source is CdnSourceDto) FAConstants.NAME_FIRECDN else (source as HosterSourceDto).hoster
            val lang = if (source.isSub == 1) FAConstants.LANG_SUB else FAConstants.LANG_DUB
            val quality = "$sourceName, $lang"

            val sourceSelection = preferences.getStringSet(FAConstants.SOURCE_SELECTION, FAConstants.SOURCE_NAMES.toSet())

            fun isSource(source: String): Boolean = sourceName == source && sourceSelection?.contains(source) == true
            when {
                isSource(FAConstants.NAME_FIRECDN) -> {
                    FireCdnExtractor(client, json).videoFromUrl(link, quality)
                }
                isSource(FAConstants.NAME_DOOD) -> {
                    val video = try {
                        DoodExtractor(client).videoFromUrl(link, quality, false)
                    } catch (e: Exception) {
                        null
                    }
                    video
                }
                else -> null
            }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(FAConstants.PREFERRED_SOURCE, null)
        val subPreference = preferences.getString(FAConstants.PREFERRED_LANG, FAConstants.LANG_SUB)!!
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

    // ===== PREFERENCES ======
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = FAConstants.PREFERRED_SOURCE
            title = "Standard-Quelle"
            entries = FAConstants.SOURCE_NAMES
            entryValues = FAConstants.SOURCE_URLS
            setDefaultValue(FAConstants.URL_FIRECDN)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = FAConstants.PREFERRED_LANG
            title = "Standardmäßig Sub oder Dub?"
            entries = FAConstants.LANGS
            entryValues = FAConstants.LANGS
            setDefaultValue(FAConstants.LANG_SUB)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = FAConstants.SOURCE_SELECTION
            title = "Quellen auswählen"
            entries = FAConstants.SOURCE_NAMES
            entryValues = FAConstants.SOURCE_NAMES
            setDefaultValue(FAConstants.SOURCE_NAMES.toSet())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
