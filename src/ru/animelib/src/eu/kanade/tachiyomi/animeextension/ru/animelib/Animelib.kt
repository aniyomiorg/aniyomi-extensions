package eu.kanade.tachiyomi.animeextension.ru.animelib

import android.app.Application
import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

class Animelib : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Animelib"

    override val lang = "ru"

    override val supportsLatest = true

    private val domain = "anilib.me"
    override val baseUrl = "https://$domain/ru"
    private val apiUrl = "https://api.lib.social/api"

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val dateFormatter by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("360", "720", "1080", "2160")

        private const val PREF_USE_MAX_QUALITY_KEY = "pref_use_max_quality"
        private const val PREF_USE_MAX_QUALITY_DEFAULT = true

        private const val PREF_SERVER_KEY = "pref_server"
        private val PREF_SERVER_ENTRIES = arrayOf("Основной", "Резервный 1", "Резервный 2")

        private const val PREF_DUB_TEAM_KEY = "prev_dub_team"

        private const val PREF_IGNORE_SUBS_KEY = "pref_ignore_subs"
        private const val PREF_IGNORE_SUBS_DEFAULT = true

        private const val PREF_USE_KODIK_KEY = "pref_use_kodik"
        private const val PREF_USE_KODIK_DEFAULT = true

        private val ATOB_REGEX = Regex("atob\\([^\"]")
    }

    // =============================== Preference ===============================
    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Предпочитаемый сервер плеера Animelib"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
            summary = "%s"
            setDefaultValue(PREF_SERVER_ENTRIES[0])

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_MAX_QUALITY_KEY
            title = "Использовать максимальное доступное качество"
            summary = "Для каждой студии озвучки будет выбрано максимальное качество"
            setDefaultValue(PREF_USE_MAX_QUALITY_DEFAULT)

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

        if (!preferences.getBoolean(PREF_USE_MAX_QUALITY_KEY, true)) {
            MultiSelectListPreference(screen.context).apply {
                key = PREF_QUALITY_KEY
                title = "Предпочитаемое качество"
                entries = PREF_QUALITY_ENTRIES
                entryValues = PREF_QUALITY_ENTRIES
                summary = "При отсутствии нужного качества могут возникать ошибки!"
                setDefaultValue(PREF_QUALITY_ENTRIES.toSet())

                setOnPreferenceChangeListener { _, newValue ->
                    @Suppress("UNCHECKED_CAST")
                    preferences.edit().putStringSet(key, newValue as Set<String>).commit()
                }
            }.also(screen::addPreference)
        }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_KODIK_KEY
            title = "Включить парсинг видео из плеера Kodik"
            summary = "Некоторые видео доступны только в нем, но он может работать нестабильно"
            setDefaultValue(PREF_USE_KODIK_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_IGNORE_SUBS_KEY
            title = "Игнорировать субтитры"
            summary = "Исключает видео с субтитрами"
            setDefaultValue(PREF_IGNORE_SUBS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DUB_TEAM_KEY
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

    override fun animeDetailsParse(response: Response) = response.parseAs<AnimeInfo>().data.toSAnime()

    // =============================== Episodes ===============================
    override fun episodeListRequest(anime: SAnime): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
        url.addPathSegment("episodes")
        url.addQueryParameter("anime_id", anime.url)

        return GET(url.build())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = response.parseAs<EpisodeList>()

        return episodeList.data.map { it.toSEpisode() }.reversed()
    }

    // =============================== Video List ===============================
    override fun videoListParse(response: Response): List<Video> {
        val episodeData = response.parseAs<EpisodeVideoData>()
        val videoServer = fetchPreferredVideoServer()
        val teams = preferences.getString(PREF_DUB_TEAM_KEY, "")?.split(',')

        val preferredTeams = episodeData.data.players?.filter { videoInfo ->
            teams.isNullOrEmpty() || teams.any { videoInfo.team.name.contains(it.trim(), true) }
        } ?: episodeData.data.players

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val videoInfoList = preferredTeams?.filter { videoInfo ->
            val quality = bestQuality(videoInfo)
            val noneBetter = preferredTeams.none {
                bestQuality(it) > quality && it.team.name == videoInfo.team.name
            }

            noneBetter || !useMaxQuality
        } ?: preferredTeams

        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS_KEY, PREF_IGNORE_SUBS_DEFAULT)
        return videoInfoList?.flatMap { videoInfo ->
            if (ignoreSubs && videoInfo.translationInfo.id == 1) {
                return@flatMap emptyList()
            }
            val playerName = videoInfo.player.lowercase()
            when (playerName) {
                "kodik" -> kodikVideoLinks(videoInfo.src, videoInfo.team.name)
                "animelib" -> animelibVideoLinks(videoInfo, videoServer)
                else -> emptyList()
            }
        } ?: emptyList()
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
        val animeList = response.parseAs<AnimeList>()

        val hasNext = !animeList.links?.next.isNullOrEmpty()
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
        val videoServers = videoServerResponse.parseAs<VideoServerData>()

        val serverPreference = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_ENTRIES[0])
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

    private fun kodikVideoLinks(playerUrl: String?, teamName: String): List<Video> {
        val useKodik = preferences.getBoolean(PREF_USE_KODIK_KEY, PREF_USE_KODIK_DEFAULT)
        if (playerUrl.isNullOrEmpty() || !useKodik) {
            return emptyList()
        }

        val kodikPage = "https:$playerUrl"
        val headers = Headers.Builder()
        headers.add("Referer", baseUrl)
        val kodikPageResponse = client.newCall(GET(kodikPage, headers.build())).execute()

        // Parse form parameters for video link request
        val page = kodikPageResponse.asJsoup()
        val urlParams = page.selectFirst("script:containsData($domain)")?.data()
            ?: return emptyList()

        val formData = urlParams.substringAfter("urlParams = '")
            .substringBefore("'")
            .parseAs<KodikForm>()

        if (formData.dSign.isEmpty()) {
            return emptyList()
        }

        val kodikDomain = formData.pd
        val formBody = FormBody.Builder()
        formBody.add("d", formData.d)
        formBody.add("d_sign", URLDecoder.decode(formData.dSign, "utf-8"))
        formBody.add("pd", formData.pd)
        formBody.add("pd_sign", URLDecoder.decode(formData.pdSign, "utf-8"))
        formBody.add("ref", URLDecoder.decode(formData.ref, "utf-8"))
        formBody.add("ref_sign", URLDecoder.decode(formData.refSign, "utf-8"))

        val urlParts = playerUrl.split('/')
        formBody.add("type", urlParts[3])
        formBody.add("id", urlParts[4])
        formBody.add("hash", urlParts[5])

        val videoInfoRequest = POST("https://$kodikDomain/ftor", body = formBody.build())
        val videoInfoResponse = client.newCall(videoInfoRequest).execute()
        val kodikData = videoInfoResponse.parseAs<KodikData>()

        // Load js with encode algorithm and parse it
        val scriptUrl = page.selectFirst("script[src*=player_single]")?.attr("abs:src")
            ?: return emptyList()
        val jsScript = client.newCall(GET(scriptUrl)).execute().body.string()
        val atob = ATOB_REGEX.find(jsScript) ?: return emptyList()

        var encodeScript = ""
        val deque = ArrayDeque<Char>()
        deque.addFirst('(')
        for (i in atob.range.last..jsScript.length) {
            val char = jsScript[i]
            if (char in arrayOf('(', '{')) {
                deque.addFirst(char)
            } else if (char in arrayOf(')', '}')) {
                if (deque.isNotEmpty()) {
                    deque.removeFirst()
                }
            }

            if (deque.isNotEmpty()) {
                encodeScript += char
            } else {
                break
            }
        }

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val qualityPreference = preferences.getStringSet(PREF_QUALITY_KEY, emptySet())
        val qualityList = if (useMaxQuality) {
            listOf("720")
        } else if (!qualityPreference.isNullOrEmpty()) {
            qualityPreference.toList()
        } else {
            listOf("360", "480", "720")
        }

        val videoList = qualityList.flatMap { quality ->
            val quickJs = QuickJs.create()
            val videoInfo = when (quality) {
                "360" -> kodikData.links.ugly[0].src
                "480" -> kodikData.links.bad[0].src
                "720" -> kodikData.links.good[0].src
                else -> return@flatMap emptyList()
            }

            val base64Url = quickJs.use {
                it.evaluate("t='$videoInfo'; $encodeScript")
            }.toString()
            val hlsUrl = Base64.decode(base64Url, Base64.DEFAULT).toString(Charsets.UTF_8)
            playlistUtils.extractFromHls(
                "https:$hlsUrl",
                videoNameGen = { "$teamName (${quality}p Kodik)" },
            )
        }

        return videoList
    }

    private fun animelibVideoLinks(videoInfo: VideoInfo, serverUrl: String): List<Video> {
        if (videoInfo.video == null) {
            return emptyList()
        }

        val subtitles = videoInfo.subtitles?.map {
            val url = it.src
            val lang = "${videoInfo.team.name} (${it.format})"
            Track(url, lang)
        } ?: emptyList()

        val useMaxQuality = preferences.getBoolean(
            PREF_USE_MAX_QUALITY_KEY,
            PREF_USE_MAX_QUALITY_DEFAULT,
        )
        val maxQuality = bestQuality(videoInfo)
        val qualityPreference = preferences.getStringSet(PREF_QUALITY_KEY, emptySet())

        val videoList = videoInfo.video.quality.mapNotNull {
            if (useMaxQuality && it.quality != maxQuality) {
                return@mapNotNull null
            } else if (!useMaxQuality && !qualityPreference.isNullOrEmpty()) {
                if (!qualityPreference.contains(it.quality.toString())) {
                    return@mapNotNull null
                }
            }

            val url = "$serverUrl${it.href}"
            val quality = "${videoInfo.team.name} (${it.quality}p Animelib)"
            Video(url, quality, url, subtitleTracks = subtitles)
        }

        return videoList
    }

    private fun bestQuality(videoInfo: VideoInfo): Int {
        return when (videoInfo.player.lowercase()) {
            "animelib" -> videoInfo.video?.quality?.maxBy { it.quality }?.quality ?: 0
            "kodik" -> 720
            else -> 0
        }
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
        author = publisher?.joinToString { it.name }
        artist = authors?.joinToString { it.name }
    }

    private fun EpisodeInfo.toSEpisode() = SEpisode.create().apply {
        url = "$apiUrl/episodes/$id"
        name = "Сезон $season Серия $number $episodeName"
        episode_number = number.toFloat()
        date_upload = dateFormatter.parse(date)?.time ?: 0L
    }
}
