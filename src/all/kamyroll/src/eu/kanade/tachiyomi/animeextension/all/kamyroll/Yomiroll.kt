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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
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

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 7463514907068706782

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val tokenInterceptor = AccessTokenInterceptor(crUrl, json, preferences)

    override val client: OkHttpClient = OkHttpClient().newBuilder()
        .addInterceptor(tokenInterceptor).build()

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QLT = "preferred_quality"
        private const val PREF_AUD = "preferred_audio"
        private const val PREF_SUB = "preferred_sub"
        private const val PREF_SUB_TYPE = "preferred_sub_type"

        // there is one in AccessTokenInterceptor too for below
        private const val PREF_FETCH_LOCAL_SUBS = "preferred_local_subs"
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        return GET("$crUrl/content/v2/discover/browse?${start}n=36&sort_by=popularity&locale=en-US")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<AnimeResult>(response.body.string())
        val animeList = parsed.data.parallelMap { ani ->
            runCatching {
                ani.toSAnime()
            }.getOrNull()
        }.filterNotNull()
        val queries = response.request.url.encodedQuery ?: "0"
        val position = if (queries.contains("start=")) {
            queries.substringAfter("start=").substringBefore("&").toInt()
        } else {
            0
        }
        return AnimesPage(animeList, position + 36 < parsed.total)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        return GET("$crUrl/content/v2/discover/browse?${start}n=36&sort_by=newly_added&locale=en-US")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = YomirollFilters.getSearchParameters(filters)
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        val url = if (query.isNotBlank()) {
            val cleanQuery = query.replace(" ", "+").lowercase()
            "$crUrl/content/v2/discover/search?${start}n=36&q=$cleanQuery&type=${params.type}"
        } else {
            "$crUrl/content/v2/discover/browse?${start}n=36${params.media}${params.language}&sort_by=${params.sort}${params.category}"
        }
        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val bod = response.body.string()
        val total: Int
        val animeList = (
            if (response.request.url.encodedPath.contains("search")) {
                val parsed = json.decodeFromString<SearchAnimeResult>(bod).data.first()
                total = parsed.count
                parsed.items
            } else {
                val parsed = json.decodeFromString<AnimeResult>(bod)
                total = parsed.total
                parsed.data
            }
            ).parallelMap { ani ->
            runCatching {
                ani.toSAnime()
            }.getOrNull()
        }.filterNotNull()
        val queries = response.request.url.encodedQuery ?: "0"
        val position = if (queries.contains("start=")) {
            queries.substringAfter("start=").substringBefore("&").toInt()
        } else {
            0
        }
        return AnimesPage(animeList, position + 36 < total)
    }

    override fun getFilterList(): AnimeFilterList = YomirollFilters.filterList

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        val resp = client.newCall(
            if (mediaId.media_type == "series") {
                GET("$crUrl/content/v2/cms/series/${mediaId.id}?locale=en-US")
            } else {
                GET("$crUrl/content/v2/cms/movie_listings/${mediaId.id}?locale=en-US")
            },
        ).execute()
        val info = json.decodeFromString<AnimeResult>(resp.body.string())
        return Observable.just(
            anime.apply {
                author = info.data.first().content_provider
                status = SAnime.COMPLETED
                if (genre.isNullOrBlank()) {
                    genre =
                        info.data.first().genres?.joinToString { gen -> gen.replaceFirstChar { it.uppercase() } }
                }
            },
        )
    }

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("not used")

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        return if (mediaId.media_type == "series") {
            GET("$crUrl/content/v2/cms/series/${mediaId.id}/seasons")
        } else {
            GET("$crUrl/content/v2/cms/movie_listings/${mediaId.id}/movies")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seasons = json.decodeFromString<SeasonResult>(response.body.string())
        val series = response.request.url.encodedPath.contains("series/")

        return if (series) {
            seasons.data.sortedBy { it.season_number }.chunked(6).map { chunk ->
                chunk.parallelMap { seasonData ->
                    runCatching {
                        val episodeResp =
                            client.newCall(GET("$crUrl/content/v2/cms/seasons/${seasonData.id}/episodes"))
                                .execute()
                        val body = episodeResp.body.string()
                        val episodes =
                            json.decodeFromString<EpisodeResult>(body)
                        episodes.data.sortedBy { it.episode_number }.parallelMap { ep ->
                            SEpisode.create().apply {
                                url = EpisodeData(
                                    ep.versions?.map { Pair(it.mediaId, it.audio_locale) }
                                        ?: listOf(
                                            Pair(
                                                ep.streams_link!!.substringAfter("videos/")
                                                    .substringBefore("/streams"),
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
                                date_upload = ep.airDate?.let { parseDate(it) } ?: 0L
                                scanlator = ep.versions?.sortedBy { it.audio_locale }
                                    ?.joinToString { it.audio_locale.substringBefore("-") }
                                    ?: ep.audio_locale.substringBefore("-")
                            }
                        }
                    }.getOrNull()
                }.filterNotNull().flatten()
            }.flatten().reversed()
        } else {
            seasons.data.mapIndexed { index, movie ->
                SEpisode.create().apply {
                    this.url = EpisodeData(listOf(Pair(movie.id, ""))).toJsonString()
                    this.name = "Movie"
                    this.episode_number = (index + 1).toFloat()
                    this.date_upload = movie.date?.let { parseDate(it) } ?: 0L
                }
            }
        }
    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val urlJson = json.decodeFromString<EpisodeData>(episode.url)
        val dubLocale = preferences.getString("preferred_audio", "en-US")!!
        val proxyToken = tokenInterceptor.getAccessToken()
        val localToken = tokenInterceptor.getLocalToken()
        val videoList = urlJson.ids.filter {
            it.second == dubLocale || it.second == "ja-JP" || it.second == "en-US" || it.second == ""
        }.parallelMap { media ->
            runCatching {
                extractVideo(media, proxyToken, localToken)
            }.getOrNull()
        }.filterNotNull().flatten()

        return Observable.just(videoList.sort())
    }

    // ============================= Utilities ==============================

    private fun extractVideo(
        media: Pair<String, String>,
        proxyToken: AccessToken,
        localToken: AccessToken?,
    ): List<Video> {
        val (mediaId, aud) = media
        val response = client.newCall(getVideoRequest(mediaId, proxyToken)).execute()
        val streams = json.decodeFromString<VideoStreams>(response.body.string())

        val localStreams =
            if (aud == "ja-JP" && preferences.getBoolean(PREF_FETCH_LOCAL_SUBS, false)) {
                val localResponse = client.newCall(getVideoRequest(mediaId, localToken!!)).execute()
                json.decodeFromString(localResponse.body.string())
            } else {
                VideoStreams()
            }

        var subsList = emptyList<Track>()
        val subLocale = preferences.getString("preferred_sub", "en-US")!!.getLocale()
        try {
            val tempSubs = mutableListOf<Track>()
            streams.subtitles?.entries?.map { (_, value) ->
                val sub = json.decodeFromString<Subtitle>(value.jsonObject.toString())
                tempSubs.add(Track(sub.url, sub.locale.getLocale()))
            }
            localStreams.subtitles?.entries?.map { (_, value) ->
                val sub = json.decodeFromString<Subtitle>(value.jsonObject.toString())
                tempSubs.add(Track(sub.url, sub.locale.getLocale()))
            }
            subsList = tempSubs.sortedWith(
                compareBy(
                    { it.lang },
                    { it.lang.contains(subLocale) },
                ),
            )
        } catch (_: Error) {
        }

        val audLang = aud.ifBlank { streams.audio_locale } ?: localStreams.audio_locale ?: "ja-JP"
        val videoList = mutableListOf<Video>()
        videoList.addAll(
            getStreams(streams, audLang, subsList),
        )
        videoList.addAll(
            getStreams(localStreams, audLang, subsList),
        )

        return videoList.distinctBy { it.quality }
    }

    private fun getStreams(
        streams: VideoStreams,
        audLang: String,
        subsList: List<Track>,
    ): List<Video> {
        return streams.streams?.adaptive_hls?.entries?.parallelMap { (_, value) ->
            val stream = json.decodeFromString<HlsLinks>(value.jsonObject.toString())
            runCatching {
                val playlist = client.newCall(GET(stream.url)).execute()
                if (playlist.code != 200) return@parallelMap null
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
        }?.filterNotNull()?.flatten() ?: emptyList()
    }

    private fun getVideoRequest(mediaId: String, token: AccessToken): Request {
        return GET("$crUrl/cms/v2${token.bucket}/videos/$mediaId/streams?Policy=${token.policy}&Signature=${token.signature}&Key-Pair-Id=${token.key_pair_id}")
    }

    private val df = DecimalFormat("0.#")

    private fun String.getLocale(): String {
        return locale.firstOrNull { it.first == this }?.second ?: ""
    }

    private fun String?.isNumeric(): Boolean {
        return this@isNumeric?.toDoubleOrNull() != null
    }

    // Add new locales to the bottom so it doesn't mess with pref indexes
    private val locale = arrayOf(
        Pair("ar-ME", "Arabic"),
        Pair("ar-SA", "Arabic (Saudi Arabia)"),
        Pair("de-DE", "German"),
        Pair("en-US", "English"),
        Pair("en-IN", "English (India)"),
        Pair("es-419", "Spanish (América Latina)"),
        Pair("es-ES", "Spanish (España)"),
        Pair("es-LA", "Spanish (América Latina)"),
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
    )

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun EpisodeData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DateFormatter.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun Anime.toSAnime(): SAnime =
        SAnime.create().apply {
            title = this@toSAnime.title
            thumbnail_url = this@toSAnime.images.poster_tall?.getOrNull(0)?.thirdLast()?.source
                ?: this@toSAnime.images.poster_tall?.getOrNull(0)?.last()?.source
            url = LinkData(this@toSAnime.id, this@toSAnime.type!!).toJsonString()
            genre = this@toSAnime.series_metadata?.genres?.joinToString()
                ?: this@toSAnime.movie_metadata?.genres?.joinToString() ?: ""
            status = SAnime.COMPLETED
            var desc = this@toSAnime.description + "\n"
            desc += "\nLanguage:" +
                (
                    if (this@toSAnime.series_metadata?.subtitle_locales?.any() == true ||
                        this@toSAnime.movie_metadata?.subtitle_locales?.any() == true ||
                        this@toSAnime.series_metadata?.is_subbed == true
                    ) {
                        " Sub"
                    } else {
                        ""
                    }
                    ) +
                (
                    if (this@toSAnime.series_metadata?.audio_locales?.any() == true ||
                        this@toSAnime.movie_metadata?.is_dubbed == true
                    ) {
                        " Dub"
                    } else {
                        ""
                    }
                    )
            desc += "\nMaturity Ratings: " +
                (
                    this@toSAnime.series_metadata?.maturity_ratings?.joinToString()
                        ?: this@toSAnime.movie_metadata?.maturity_ratings?.joinToString() ?: ""
                    )
            desc += if (this@toSAnime.series_metadata?.is_simulcast == true) "\nSimulcast" else ""
            desc += "\n\nAudio: " + (
                this@toSAnime.series_metadata?.audio_locales?.sortedBy { it.getLocale() }
                    ?.joinToString { it.getLocale() } ?: ""
                )
            desc += "\n\nSubs: " + (
                this@toSAnime.series_metadata?.subtitle_locales?.sortedBy { it.getLocale() }
                    ?.joinToString { it.getLocale() }
                    ?: this@toSAnime.movie_metadata?.subtitle_locales?.sortedBy { it.getLocale() }
                        ?.joinToString { it.getLocale() } ?: ""
                )
            description = desc
        }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val dubLocale = preferences.getString("preferred_audio", "en-US")!!
        val subLocale = preferences.getString("preferred_sub", "en-US")!!
        val subType = preferences.getString("preferred_sub_type", "soft")!!
        val shouldContainHard = subType == "hard"

        return this.sortedWith(
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
            key = PREF_QLT
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val audLocalePref = ListPreference(screen.context).apply {
            key = PREF_AUD
            title = "Preferred Audio Language"
            entries = locale.map { it.second }.toTypedArray()
            entryValues = locale.map { it.first }.toTypedArray()
            setDefaultValue("en-US")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subLocalePref = ListPreference(screen.context).apply {
            key = PREF_SUB
            title = "Preferred Sub Language"
            entries = locale.map { it.second }.toTypedArray()
            entryValues = locale.map { it.first }.toTypedArray()
            setDefaultValue("en-US")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subTypePref = ListPreference(screen.context).apply {
            key = PREF_SUB_TYPE
            title = "Preferred Sub Type"
            entries = arrayOf("Softsub", "Hardsub")
            entryValues = arrayOf("soft", "hard")
            setDefaultValue("soft")
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
        (
            object : LocalSubsPreference(screen.context) {
                override fun reload() {
                    this.apply {
                        key = PREF_FETCH_LOCAL_SUBS
                        title = "Fetch Local Subs (Don't Spam this please!)"
                        runBlocking {
                            withContext(Dispatchers.IO) { summary = getTokenDetail() }
                        }
                        setDefaultValue(false)
                        setOnPreferenceChangeListener { _, newValue ->
                            val new = newValue as Boolean
                            Thread {
                                runBlocking {
                                    if (new) {
                                        withContext(Dispatchers.IO) {
                                            summary = getTokenDetail(true)
                                        }
                                    } else {
                                        tokenInterceptor.removeLocalToken()
                                        summary = """Token location:
                                            |Expires:
                                        """.trimMargin()
                                    }
                                }
                            }.start()
                            preferences.edit().putBoolean(key, new).commit()
                        }
                    }
                }
            }
            ).apply { reload() }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun getTokenDetail(force: Boolean = false): String {
        return try {
            val storedToken = tokenInterceptor.getLocalToken(force)
            """Token location: ${
            storedToken?.bucket?.substringAfter("/")?.substringBefore("/") ?: ""
            }
            |Expires: ${storedToken?.policyExpire?.let { DateFormatter.format(it) } ?: ""}
            """.trimMargin()
        } catch (e: Exception) {
            ""
        }
    }
}
