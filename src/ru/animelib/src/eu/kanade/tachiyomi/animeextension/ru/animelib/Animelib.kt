package eu.kanade.tachiyomi.animeextension.ru.animelib

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Animelib : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Animelib"

    override val lang = "ru"

    override val supportsLatest = true

    private val domain = "anilib.me"
    override val baseUrl = "https://$domain/ru"
    private val apiUrl = "https://api.lib.social/api"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val dateFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    }

    // =============================== Preference ===============================

    object PrefConstants {
        const val PREF_QUALITY_KEY = "pref_quality"
        val PREF_QUALITY_ENTRIES = arrayOf("360", "720", "1080", "2160")

        const val PREF_USE_MAX_QUALITY_KEY = "pref_use_max_quality"
        const val PREF_USE_MAX_QUALITY_DEFAULT = true

        const val PREF_SERVER_KEY = "pref_server"
        val PREF_SERVER_ENTRIES = arrayOf("Основной", "Резервный 1", "Резервный 2")

        const val PREF_DUB_TEAM_KEY = "prev_dub_team"

        const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        const val PREF_IGNORE_SUBS_DEFAULT = true
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PrefConstants.PREF_SERVER_KEY
            title = "Предпочитаемый сервер плеера Animelib"
            entries = PrefConstants.PREF_SERVER_ENTRIES
            entryValues = PrefConstants.PREF_SERVER_ENTRIES
            summary = "%s"
            setDefaultValue(PrefConstants.PREF_SERVER_ENTRIES[0])

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PrefConstants.PREF_USE_MAX_QUALITY_KEY
            title = "Использовать максимальное доступное качество"
            summary = "Для каждой студии озвучки будет выбрано максимальное качество"
            setDefaultValue(PrefConstants.PREF_USE_MAX_QUALITY_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as Boolean

                val text = if (value) {
                    "Предпочитаемое качество пропадет после закрытия окна настроек"
                } else {
                    "Откройте настройки заново чтобы выбрать предпочитаемое качество"
                }
                Toast.makeText(screen.context, text, Toast.LENGTH_LONG).show()

                preferences.edit().putBoolean(key, value).commit()
            }
        }.also(screen::addPreference)

        if (!preferences.getBoolean(PrefConstants.PREF_USE_MAX_QUALITY_KEY, true)) {
            MultiSelectListPreference(screen.context).apply {
                key = PrefConstants.PREF_QUALITY_KEY
                title = "Предпочитаемое качество"
                entries = PrefConstants.PREF_QUALITY_ENTRIES
                entryValues = PrefConstants.PREF_QUALITY_ENTRIES
                summary = "При отсутствии нужного качества могут возникать ошибки!"
                setDefaultValue(PrefConstants.PREF_QUALITY_ENTRIES.toSet())

                setOnPreferenceChangeListener { _, newValue ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.edit().putStringSet(key, newValue as Set<String>).commit()
                }
            }.also(screen::addPreference)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = PrefConstants.PREF_IGNORE_SUBS_KEY
            title = "Игнорировать субтитры"
            summary = "Исключает из списка озвучек субтитры"
            setDefaultValue(PrefConstants.PREF_IGNORE_SUBS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PrefConstants.PREF_DUB_TEAM_KEY
            title = "Предпочитаемые студии озвучки"
            summary = "Список студий или ключевых слов через запятую (экспериментальная функция)"
            setDefaultValue("")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // =============================== Details ===============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addPathSegment(anime.url)
        url.addQueryParameter("fields[]", "genres")
        url.addQueryParameter("fields[]", "summary")
        url.addQueryParameter("fields[]", "authors")
        url.addQueryParameter("fields[]", "publisher")
        url.addQueryParameter("fields[]", "otherNames")
        url.addQueryParameter("fields[]", "anime_status_id")

        return GET(url.build())
    }

    override fun getAnimeUrl(anime: SAnime) = "$baseUrl/${anime.url}"

    override fun animeDetailsParse(response: Response): SAnime {
        val animeInfo = json.decodeFromString<AnimeInfo>(response.body.string())

        return animeInfo.data.toSAnime()
    }

    // =============================== Episodes ===============================
    override fun episodeListRequest(anime: SAnime): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("episodes")
        url.addQueryParameter("anime_id", anime.url)

        return GET(url.build())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = json.decodeFromString<EpisodeList>(response.body.string())

        return episodeList.data.map { it.toSEpisode() }.reversed()
    }

    // =============================== Video List ===============================
    override fun videoListParse(response: Response): List<Video> {
        val episodeData = json.decodeFromString<EpisodeVideoData>(response.body.string())
        val videoServer = fetchPreferredVideoServer()

        val videos = mutableListOf<Video>()
        if (episodeData.data.players.isNullOrEmpty()) {
            return videos
        }

        for (videoInfo in episodeData.data.players) {
            val playerName = videoInfo.player.lowercase()
            if (playerName == "kodik") {
                // TODO maybe in future
                // videos.addAll(kodikVideoLink(videoInfo.src))
            } else if (playerName == "animelib") {
                videos.addAll(animelibVideoLinks(videoInfo, videoServer))
            }
        }

        // Filter by dub team preference
        val prefDubTeam = preferences.getString(PrefConstants.PREF_DUB_TEAM_KEY, "")
        val teams = prefDubTeam?.split(',')

        if (teams.isNullOrEmpty()) {
            return videos
        }

        videos.removeAll {
            var remove = true
            for (team in teams) {
                if (it.quality.contains(team, true)) {
                    remove = false
                    break
                }
            }

            remove
        }

        return videos
    }

    override fun videoListRequest(episode: SEpisode) = GET(episode.url)

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", "5")
        url.addQueryParameter("links[]", "")
        url.addQueryParameter("sort_by", "last_episode_at")

        return GET(url.build())
    }

    // =============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = json.decodeFromString<AnimeList>(response.body.string())

        var hasNext = false
        if (animeList.links != null) {
            hasNext = !animeList.links.next.isNullOrEmpty()
        }

        val animes = animeList.data.map { it.toSAnime() }
        return AnimesPage(animes, hasNext)
    }

    override fun popularAnimeRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", "5")
        url.addQueryParameter("links[]", "")

        return GET(url.build())
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = AnimelibFilters.getSearchParameters(filters)

        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("anime")
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", "5")
        url.addQueryParameter("links[]", "")

        searchParams.genres.include.forEach { url.addQueryParameter("genres[]", it) }
        searchParams.genres.exclude.forEach { url.addQueryParameter("genres_exclude[]", it) }
        searchParams.format.forEach { url.addQueryParameter("types[]", it) }
        searchParams.pegi.forEach { url.addQueryParameter("caution[]", it) }
        searchParams.ongoingStatus.forEach { url.addQueryParameter("status[]", it) }

        url.addQueryParameter("sort_by", searchParams.sortOrder)
        if (searchParams.sortDirection.isNotEmpty()) {
            url.addQueryParameter("sort_type", searchParams.sortDirection)
        }

        url.addQueryParameter("q", query)

        return GET(url.build())
    }

    override fun getFilterList() = AnimelibFilters.FILTER_LIST

    // =============================== Utils ===============================
    private fun fetchPreferredVideoServer(): String {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("constants")
        url.addQueryParameter("fields[]", "videoServers")

        val videoServerResponse = client.newCall(GET(url.build())).execute()
        val videoServers = json.decodeFromString<VideoServerData>(videoServerResponse.body.string())

        val serverPreference = preferences.getString(
            PrefConstants.PREF_SERVER_KEY,
            PrefConstants.PREF_SERVER_ENTRIES[0],
        )
        if (serverPreference.isNullOrEmpty()) {
            return videoServers.data.videoServers[0].url
        }

        for (videoServer in videoServers.data.videoServers) {
            if (videoServer.label == serverPreference) {
                return videoServer.url
            }
        }

        return videoServers.data.videoServers[0].url
    }

    private fun kodikVideoLink(playerUrl: String?): List<Video> {
        val videos = mutableListOf<Video>()
        // TODO extract video from kodik player

        return videos
    }

    private fun animelibVideoLinks(videoInfo: VideoInfo, serverUrl: String): List<Video> {
        val videos = mutableListOf<Video>()

        if (videoInfo.video == null) {
            return videos
        }

        val ignoreSubs = preferences.getBoolean(
            PrefConstants.PREF_IGNORE_SUBS_KEY,
            PrefConstants.PREF_IGNORE_SUBS_DEFAULT,
        )

        if (ignoreSubs && videoInfo.translationInfo.id == 1) {
            return videos
        }

        val subtitles = mutableListOf<Track>()
        if (videoInfo.subtitles != null) {
            videoInfo.subtitles.forEach {
                val url = it.src
                val lang = "${videoInfo.team.name} (${it.format})"
                subtitles.add(Track(url, lang))
            }
        }

        val useMaxQuality = preferences.getBoolean(
            PrefConstants.PREF_USE_MAX_QUALITY_KEY,
            PrefConstants.PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val qualityPreference = preferences.getStringSet(PrefConstants.PREF_QUALITY_KEY, emptySet())

        var maxQuality = 0
        for (qualityVideo in videoInfo.video.quality) {
            val url = "$serverUrl${qualityVideo.href}"
            val quality = "${videoInfo.team.name} (${qualityVideo.quality}p)"

            val video = Video(url, quality, url, subtitleTracks = subtitles)
            if (useMaxQuality && qualityVideo.quality > maxQuality) {
                maxQuality = qualityVideo.quality
                videos.clear()
                videos.add(video)
            } else if (!useMaxQuality && !qualityPreference.isNullOrEmpty()) {
                if (qualityVideo.quality.toString() in qualityPreference) {
                    videos.add(video)
                }
            } else if (!useMaxQuality) {
                videos.add(video)
            }
        }

        return videos
    }

    // =============================== Converters ===============================
    private fun convertStatus(status: Int): Int {
        return when (status) {
            1 -> SAnime.ONGOING
            2 -> SAnime.COMPLETED
            4 -> SAnime.ON_HIATUS
            5 -> SAnime.CANCELLED
            else -> {
                SAnime.UNKNOWN
            }
        }
    }

    private fun AnimeData.toSAnime() = SAnime.create().apply {
        url = href
        title = rusName
        thumbnail_url = cover.thumbnail
        description = summary
        status = convertStatus(animeStatus.id)
        author = publisher?.joinToString { it.name } ?: ""
        artist = authors?.joinToString { it.name } ?: ""
    }

    private fun EpisodeInfo.toSEpisode() = SEpisode.create().apply {
        url = "$apiUrl/episodes/$id"
        name = "Сезон $season Серия $number $episodeName"
        episode_number = number.toFloat()
        date_upload = dateFormatter.parse(date)?.time ?: 0L
    }
}
