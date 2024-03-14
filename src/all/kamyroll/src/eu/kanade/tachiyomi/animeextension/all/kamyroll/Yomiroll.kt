package eu.kanade.tachiyomi.animeextension.all.kamyroll

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapNotNullBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale

@ExperimentalSerializationApi
class Yomiroll : ConfigurableAnimeSource, AnimeHttpSource() {

    // No more renaming, no matter what 3rd party service is used :)
    override val name = "Yomiroll"

    override val baseUrl = "https://crunchyroll.com"

    private val crUrl = "https://beta-api.crunchyroll.com"
    private val crApiUrl = "$crUrl/content/v2"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 7463514907068706782

    private val json: Json by injectLazy()

    private val mainScope by lazy { MainScope() }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val tokenInterceptor by lazy {
        AccessTokenInterceptor(crUrl, json, preferences, PREF_USE_LOCAL_TOKEN_KEY)
    }

    override val client by lazy {
        super.client.newBuilder().addInterceptor(tokenInterceptor).build()
    }

    private val noTokenClient = super.client

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        return GET("$crApiUrl/discover/browse?${start}n=36&sort_by=popularity&locale=en-US")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<AnimeResult>(response.body.string())
        val animeList = parsed.data.mapNotNull { it.toSAnimeOrNull() }
        val position = response.request.url.queryParameter("start")?.toIntOrNull() ?: 0
        return AnimesPage(animeList, position + 36 < parsed.total)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        return GET("$crApiUrl/discover/browse?${start}n=36&sort_by=newly_added&locale=en-US")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = YomirollFilters.getSearchParameters(filters)
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        val url = if (query.isNotBlank()) {
            val cleanQuery = query.replace(" ", "+").lowercase()
            "$crApiUrl/discover/search?${start}n=36&q=$cleanQuery&type=${params.type}"
        } else {
            "$crApiUrl/discover/browse?${start}n=36${params.media}${params.language}&sort_by=${params.sort}${params.category}"
        }
        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val bod = response.body.string()
        val total: Int
        val items =
            if (response.request.url.encodedPath.contains("search")) {
                val parsed = json.decodeFromString<SearchAnimeResult>(bod).data.first()
                total = parsed.count
                parsed.items
            } else {
                val parsed = json.decodeFromString<AnimeResult>(bod)
                total = parsed.total
                parsed.data
            }

        val animeList = items.mapNotNull { it.toSAnimeOrNull() }
        val position = response.request.url.queryParameter("start")?.toIntOrNull() ?: 0
        return AnimesPage(animeList, position + 36 < total)
    }

    override fun getFilterList(): AnimeFilterList = YomirollFilters.FILTER_LIST

    // =========================== Anime Details ============================

    // Function to fetch anime status using AniList GraphQL API ispired by OppaiStream.kt
    private fun fetchStatusByTitle(title: String): Int {
        val query = """
            query {
            	Media(
                  search: "$title",
                  sort: STATUS_DESC,
                  status_not_in: [NOT_YET_RELEASED],
                  format_not_in: [SPECIAL, MOVIE],
                  isAdult: false,
                  type: ANIME
                ) {
                  id
                  idMal
                  title {
                    romaji
                    native
                    english
                  }
                  status
                }
            }
        """.trimIndent()

        val requestBody = FormBody.Builder()
            .add("query", query)
            .build()

        val response = noTokenClient.newCall(
            POST("https://graphql.anilist.co", body = requestBody),
        ).execute().body.string()

        val responseParsed = json.decodeFromString<AnilistResult>(response)

        return when (responseParsed.data.media?.status) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            "CANCELLED" -> SAnime.CANCELLED
            "HIATUS" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        val resp = client.newCall(
            if (mediaId.media_type == "series") {
                GET("$crApiUrl/cms/series/${mediaId.id}?locale=en-US")
            } else {
                GET("$crApiUrl/cms/movie_listings/${mediaId.id}?locale=en-US")
            },
        ).execute().body.string()
        val info = json.decodeFromString<AnimeResult>(resp)
        return info.data.first().toSAnimeOrNull(anime) ?: anime
    }

    override fun animeDetailsParse(response: Response): SAnime =
        throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        return if (mediaId.media_type == "series") {
            GET("$crApiUrl/cms/series/${mediaId.id}/seasons")
        } else {
            GET("$crApiUrl/cms/movie_listings/${mediaId.id}/movies")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seasons = json.decodeFromString<SeasonResult>(response.body.string())
        val series = response.request.url.encodedPath.contains("series/")
        val chunkSize = Runtime.getRuntime().availableProcessors()
        return if (series) {
            seasons.data.sortedBy { it.season_number }.chunked(chunkSize).flatMap { chunk ->
                chunk.parallelCatchingFlatMapBlocking(::getEpisodes)
            }.reversed()
        } else {
            seasons.data.mapIndexed { index, movie ->
                SEpisode.create().apply {
                    url = EpisodeData(listOf(Pair(movie.id, ""))).toJsonString()
                    name = "Movie ${index + 1}"
                    episode_number = (index + 1).toFloat()
                    date_upload = movie.date?.let(::parseDate) ?: 0L
                }
            }
        }
    }

    private fun getEpisodes(seasonData: SeasonResult.Season): List<SEpisode> {
        val body =
            client.newCall(GET("$crApiUrl/cms/seasons/${seasonData.id}/episodes"))
                .execute().body.string()
        val episodes = json.decodeFromString<EpisodeResult>(body)

        return episodes.data.sortedBy { it.episode_number }.mapNotNull EpisodeMap@{ ep ->
            SEpisode.create().apply {
                url = EpisodeData(
                    ep.versions?.map { Pair(it.mediaId, it.audio_locale) }
                        ?: listOf(
                            Pair(
                                ep.streams_link?.substringAfter("videos/")
                                    ?.substringBefore("/streams")
                                    ?: return@EpisodeMap null,
                                ep.audio_locale,
                            ),
                        ),
                ).toJsonString()
                name = if (ep.episode_number > 0 && ep.episode.isNumeric()) {
                    "Season ${seasonData.season_number} Ep ${df.format(ep.episode_number)}: " + ep.title
                } else {
                    ep.title
                }
                episode_number = ep.episode_number
                date_upload = ep.airDate?.let(::parseDate) ?: 0L
                scanlator = ep.versions?.sortedBy { it.audio_locale }
                    ?.joinToString { it.audio_locale.substringBefore("-") }
                    ?: ep.audio_locale.substringBefore("-")
            }
        }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlJson = json.decodeFromString<EpisodeData>(episode.url)
        val dubLocale = preferences.getString(PREF_AUD_KEY, PREF_AUD_DEFAULT)!!

        if (urlJson.ids.isEmpty()) throw Exception("No IDs found for episode")
        val isUsingLocalToken = preferences.getBoolean(PREF_USE_LOCAL_TOKEN_KEY, false)

        val videoList = urlJson.ids.filter {
            it.second == dubLocale ||
                it.second == "ja-JP" ||
                it.second == "en-US" ||
                it.second == "" ||
                if (isUsingLocalToken) it.second == urlJson.ids.first().second else false
        }.parallelCatchingFlatMap(::extractVideo)

        return videoList.sort()
    }

    // ============================= Utilities ==============================

    private fun extractVideo(media: Pair<String, String>): List<Video> {
        val (mediaId, aud) = media
        val response = client.newCall(getVideoRequest(mediaId)).execute().body.string()
        val streams = json.decodeFromString<VideoStreams>(response)

        val subLocale = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!.getLocale()
        val subsList = runCatching {
            streams.subtitles?.entries?.map { (_, value) ->
                val sub = json.decodeFromString<Subtitle>(value.jsonObject.toString())
                Track(sub.url, sub.locale.getLocale())
            }?.sortedWith(
                compareByDescending<Track> { it.lang.contains(subLocale) }
                    .thenBy { it.lang },
            )
        }.getOrNull() ?: emptyList()

        val audLang = aud.ifBlank { streams.audio_locale } ?: "ja-JP"
        return getStreams(streams, audLang, subsList)
    }

    private fun getStreams(
        streams: VideoStreams,
        audLang: String,
        subsList: List<Track>,
    ): List<Video> {
        return streams.streams?.adaptiveHls?.entries?.parallelMapNotNullBlocking { (_, value) ->
            val stream = json.decodeFromString<HlsLinks>(value.jsonObject.toString())
            runCatching {
                val playlist = client.newCall(GET(stream.url)).execute()
                if (playlist.code != 200) return@parallelMapNotNullBlocking null
                playlist.body.string().substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").map {
                        val hardsub = stream.hardsub_locale.let { hs ->
                            if (hs.isNotBlank()) " - HardSub: $hs" else ""
                        }
                        val quality = it.substringAfter("RESOLUTION=")
                            .split(",")[0].split("\n")[0].substringAfter("x") +
                            "p - Aud: ${audLang.getLocale()}$hardsub"

                        val videoUrl = it.substringAfter("\n").substringBefore("\n")

                        try {
                            Video(
                                videoUrl,
                                quality,
                                videoUrl,
                                subtitleTracks = if (hardsub.isNotBlank()) emptyList() else subsList,
                            )
                        } catch (_: Error) {
                            Video(videoUrl, quality, videoUrl)
                        }
                    }
            }.getOrNull()
        }?.flatten() ?: emptyList()
    }

    private fun getVideoRequest(mediaId: String): Request {
        return GET("$crUrl/cms/v2{0}/videos/$mediaId/streams?Policy={1}&Signature={2}&Key-Pair-Id={3}")
    }

    private val df by lazy { DecimalFormat("0.#") }

    private fun String.getLocale(): String {
        return locale.firstOrNull { it.first == this }?.second ?: ""
    }

    private fun String?.isNumeric() = this?.toDoubleOrNull() != null

    // Add new locales to the bottom so it doesn't mess with pref indexes
    private val locale = arrayOf(
        Pair("ar-ME", "Arabic"),
        Pair("ar-SA", "Arabic (Saudi Arabia)"),
        Pair("de-DE", "German"),
        Pair("en-US", "English"),
        Pair("en-IN", "English (India)"),
        Pair("es-419", "Spanish (América Latina)"),
        Pair("es-ES", "Spanish (España)"),
        Pair("fr-FR", "French"),
        Pair("ja-JP", "Japanese"),
        Pair("hi-IN", "Hindi"),
        Pair("it-IT", "Italian"),
        Pair("ko-KR", "Korean"),
        Pair("pt-BR", "Português (Brasil)"),
        Pair("pt-PT", "Português (Portugal)"),
        Pair("pl-PL", "Polish"),
        Pair("ru-RU", "Russian"),
        Pair("tr-TR", "Turkish"),
        Pair("uk-UK", "Ukrainian"),
        Pair("he-IL", "Hebrew"),
        Pair("ro-RO", "Romanian"),
        Pair("sv-SE", "Swedish"),
        Pair("zh-CN", "Chinese (PRC)"),
        Pair("zh-HK", "Chinese (Hong Kong)"),
        Pair("zh-TW", "Chinese (Taiwan)"),
        Pair("ca-ES", "Català"),
        Pair("id-ID", "Bahasa Indonesia"),
        Pair("ms-MY", "Bahasa Melayu"),
        Pair("ta-IN", "Tamil"),
        Pair("te-IN", "Telugu"),
        Pair("th-TH", "Thai"),
        Pair("vi-VN", "Vietnamese"),
    )

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun EpisodeData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun Anime.toSAnimeOrNull(anime: SAnime? = null) =
        runCatching { toSAnime(anime) }.getOrNull()

    private fun Anime.toSAnime(anime: SAnime? = null): SAnime =
        SAnime.create().apply {
            title = this@toSAnime.title
            thumbnail_url = images.poster_tall?.getOrNull(0)?.thirdLast()?.source
                ?: images.poster_tall?.getOrNull(0)?.last()?.source
            url = anime?.url ?: LinkData(id, type!!).toJsonString()
            genre = anime?.genre ?: (series_metadata?.genres ?: movie_metadata?.genres ?: genres)
                ?.joinToString { gen -> gen.replaceFirstChar { it.uppercase() } }
            status = anime?.let {
                val media = json.decodeFromString<LinkData>(anime.url)
                if (media.media_type == "series") {
                    fetchStatusByTitle(this@toSAnime.title)
                } else {
                    SAnime.COMPLETED
                }
            } ?: SAnime.UNKNOWN
            author = content_provider
            description = StringBuilder().apply {
                appendLine(this@toSAnime.description)
                appendLine()

                append("Language:")
                if ((
                        subtitle_locales ?: (
                            series_metadata
                                ?: movie_metadata
                            )?.subtitle_locales
                        )?.any() == true ||
                    (series_metadata ?: movie_metadata)?.is_subbed == true ||
                    is_subbed == true
                ) {
                    append(" Sub")
                }
                if (((series_metadata?.audio_locales ?: audio_locales)?.size ?: 0) > 1 ||
                    (series_metadata ?: movie_metadata)?.is_dubbed == true ||
                    is_dubbed == true
                ) {
                    append(" Dub")
                }
                appendLine()

                append("Maturity Ratings: ")
                appendLine(
                    ((series_metadata ?: movie_metadata)?.maturity_ratings ?: maturity_ratings)
                        ?.joinToString() ?: "-",
                )
                if (series_metadata?.is_simulcast == true) appendLine("Simulcast")
                appendLine()

                append("Audio: ")
                appendLine(
                    (series_metadata?.audio_locales ?: audio_locales ?: listOf(audio_locale ?: "-"))
                        .sortedBy { it.getLocale() }
                        .joinToString { it.getLocale() },
                )
                appendLine()

                append("Subs: ")
                append(
                    (
                        subtitle_locales ?: series_metadata?.subtitle_locales
                            ?: movie_metadata?.subtitle_locales
                        )
                        ?.sortedBy { it.getLocale() }
                        ?.joinToString { it.getLocale() },
                )
            }.toString()
        }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QLT_KEY, PREF_QLT_DEFAULT)!!
        val dubLocale = preferences.getString(PREF_AUD_KEY, PREF_AUD_DEFAULT)!!
        val subLocale = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!
        val subType = preferences.getString(PREF_SUB_TYPE_KEY, PREF_SUB_TYPE_DEFAULT)!!
        val shouldContainHard = subType == "hard"

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains("Aud: ${dubLocale.getLocale()}") },
                { it.quality.contains("HardSub") == shouldContainHard },
                { it.quality.contains(subLocale) },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QLT_KEY
            title = PREF_QLT_TITLE
            entries = PREF_QLT_ENTRIES
            entryValues = PREF_QLT_VALUES
            setDefaultValue(PREF_QLT_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val audLocalePref = ListPreference(screen.context).apply {
            key = PREF_AUD_KEY
            title = PREF_AUD_TITLE
            entries = locale.map { it.second }.toTypedArray()
            entryValues = locale.map { it.first }.toTypedArray()
            setDefaultValue(PREF_AUD_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subLocalePref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = locale.map { it.second }.toTypedArray()
            entryValues = locale.map { it.first }.toTypedArray()
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subTypePref = ListPreference(screen.context).apply {
            key = PREF_SUB_TYPE_KEY
            title = PREF_SUB_TYPE_TITLE
            entries = PREF_SUB_TYPE_ENTRIES
            entryValues = PREF_SUB_TYPE_VALUES
            setDefaultValue(PREF_SUB_TYPE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(audLocalePref)
        screen.addPreference(subLocalePref)
        screen.addPreference(subTypePref)
        screen.addPreference(localSubsPreference(screen))
    }

    // From Jellyfin
    private abstract class LocalSubsPreference(context: Context) : SwitchPreferenceCompat(context) {
        abstract fun reload()
    }

    private fun localSubsPreference(screen: PreferenceScreen) =
        object : LocalSubsPreference(screen.context) {
            override fun reload() {
                this.apply {
                    key = PREF_USE_LOCAL_TOKEN_KEY
                    title = PREF_USE_LOCAL_TOKEN_TITLE
                    mainScope.launch(Dispatchers.IO) {
                        getTokenDetail().let {
                            withContext(Dispatchers.Main) {
                                summary = it
                            }
                        }
                    }
                    setDefaultValue(false)
                    setOnPreferenceChangeListener { _, newValue ->
                        val new = newValue as Boolean
                        preferences.edit().putBoolean(key, new).commit().also {
                            mainScope.launch(Dispatchers.IO) {
                                getTokenDetail(true).let {
                                    withContext(Dispatchers.Main) {
                                        summary = it
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.apply { reload() }

    private fun getTokenDetail(force: Boolean = false): String {
        return runCatching {
            val storedToken = tokenInterceptor.getAccessToken(force)
            "Token location: " + storedToken.bucket?.substringAfter("/")?.substringBefore("/")
        }.getOrElse {
            tokenInterceptor.removeToken()
            "Error: ${it.localizedMessage ?: "Something Went Wrong"}"
        }
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QLT_KEY = "preferred_quality"
        private const val PREF_QLT_TITLE = "Preferred quality"
        private const val PREF_QLT_DEFAULT = "1080p"
        private val PREF_QLT_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
        private val PREF_QLT_VALUES = PREF_QLT_ENTRIES

        private const val PREF_AUD_KEY = "preferred_audio"
        private const val PREF_AUD_TITLE = "Preferred Audio Language"
        private const val PREF_AUD_DEFAULT = "en-US"

        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_TITLE = "Preferred Sub Language"
        private const val PREF_SUB_DEFAULT = "en-US"

        private const val PREF_SUB_TYPE_KEY = "preferred_sub_type"
        private const val PREF_SUB_TYPE_TITLE = "Preferred Sub Type"
        private const val PREF_SUB_TYPE_DEFAULT = "soft"
        private val PREF_SUB_TYPE_ENTRIES = arrayOf("Softsub", "Hardsub")
        private val PREF_SUB_TYPE_VALUES = arrayOf("soft", "hard")

        private const val PREF_USE_LOCAL_TOKEN_KEY = "preferred_local_Token"
        private const val PREF_USE_LOCAL_TOKEN_TITLE = "Use Local Token (Don't Spam this please!)"
    }
}
