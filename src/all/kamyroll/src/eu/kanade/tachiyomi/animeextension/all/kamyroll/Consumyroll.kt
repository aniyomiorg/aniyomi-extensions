package eu.kanade.tachiyomi.animeextension.all.kamyroll

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
class Consumyroll : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Consumyroll"

    override val baseUrl = "https://cronchy.consumet.stream"

    private val crUrl = "https://beta-api.crunchyroll.com"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 7463514907068706782

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val TokenInterceptor = AccessTokenInterceptor(baseUrl, json, preferences)

    override val client: OkHttpClient = OkHttpClient().newBuilder()
        .addInterceptor(TokenInterceptor).build()

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        return GET("$crUrl/content/v2/discover/browse?${start}n=36&sort_by=popularity&locale=en-US")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<AnimeResult>(response.body!!.string())
        val animeList = parsed.data.filter { it.type == "series" }.map { ani ->
            ani.toSAnime()
        }
        return AnimesPage(animeList, true)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val start = if (page != 1) "start=${(page - 1) * 36}&" else ""
        return GET("$crUrl/content/v2/discover/browse?${start}n=36&sort_by=newly_added&locale=en-US")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()
        return GET("$crUrl/content/v2/discover/search?q=$cleanQuery&n=6&type=&locale=en-US")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<SearchAnimeResult>(response.body!!.string())
        val animeList = parsed.data.filter { it.type == "top_results" }.map { result ->
            result.items.filter { it.type == "series" }.map { ani ->
                ani.toSAnime()
            }
        }.flatten()
        return AnimesPage(animeList, false)
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        val resp = client.newCall(GET("$crUrl/content/v2/cms/series/${mediaId.id}?locale=en-US")).execute()
        val info = json.decodeFromString<AnimeResult>(resp.body!!.string())
        return Observable.just(
            anime.apply {
                author = info.data.first().content_provider
                status = SAnime.COMPLETED
                if (genre.isNullOrBlank()) {
                    genre = info.data.first().genres?.joinToString { gen -> gen.replaceFirstChar { it.uppercase() } }
                }
            }
        )
    }

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("not used")

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        return GET("$crUrl/content/v2/cms/series/${mediaId.id}/seasons")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seasons = json.decodeFromString<SeasonResult>(response.body!!.string())
        return seasons.data.parallelMap { seasonData ->
            runCatching {
                val episodeResp = client.newCall(GET("$crUrl/content/v2/cms/seasons/${seasonData.id}/episodes")).execute()
                val episodes = json.decodeFromString<EpisodeResult>(episodeResp.body!!.string())
                episodes.data.sortedBy { it.episode_number }.map { ep ->
                    SEpisode.create().apply {
                        url = EpisodeData(
                            ep.versions?.map { Pair(it.mediaId, it.audio_locale) } ?: listOf(
                                Pair(
                                    ep.streams_link.substringAfter("videos/").substringBefore("/streams"),
                                    ep.audio_locale
                                )
                            )
                        ).toJsonString()
                        name = if (ep.episode_number > 0 && ep.episode.isNumeric()) {
                            "Season ${seasonData.season_number} Ep ${df.format(ep.episode_number)}: " + ep.title
                        } else { ep.title }
                        episode_number = ep.episode_number
                        date_upload = ep.airDate?.let { parseDate(it) } ?: 0L
                        scanlator = ep.versions?.sortedBy { it.audio_locale }
                            ?.joinToString { it.audio_locale.substringBefore("-") } ?: ep.audio_locale.substringBefore("-")
                    }
                }
            }.getOrNull()
        }.filterNotNull().flatten().reversed()
    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val urlJson = json.decodeFromString<EpisodeData>(episode.url)
        val dubLocale = preferences.getString("preferred_audio", "en-US")!!
        val tokenJson = preferences.getString(AccessTokenInterceptor.TOKEN_PREF_KEY, null)
            ?: TokenInterceptor.refreshAccessToken()
        val policyJson = json.decodeFromString<AccessToken>(tokenJson)
        val videoList = urlJson.ids.filter {
            it.second == dubLocale || it.second == "ja-JP" || it.second == "en-US"
        }.parallelMap { media ->
            runCatching {
                extractVideo(media, policyJson)
            }.getOrNull()
        }.filterNotNull().flatten()

        return Observable.just(videoList.sort())
    }

    // ============================= Utilities ==============================

    private fun extractVideo(media: Pair<String, String>, policyJson: AccessToken): List<Video> {
        val (mediaId, audLang) = media
        val response = client.newCall(GET("$crUrl/cms/v2${policyJson.bucket}/videos/$mediaId/streams?Policy=${policyJson.policy}&Signature=${policyJson.signature}&Key-Pair-Id=${policyJson.key_pair_id}")).execute()
        val streams = json.decodeFromString<VideoStreams>(response.body!!.string())

        var subsList = emptyList<Track>()
        val subLocale = preferences.getString("preferred_sub", "en-US")!!.getLocale()
        try {
            subsList = streams.subtitles.entries.map { (_, value) ->
                val sub = json.decodeFromString<Subtitle>(value.jsonObject.toString())
                Track(sub.url, sub.locale.getLocale())
            }.sortedWith(
                compareBy(
                    { it.lang },
                    { it.lang.contains(subLocale) }
                )
            )
        } catch (_: Error) {}

        return streams.streams.adaptive_hls.entries.parallelMap { (_, value) ->
            val stream = json.decodeFromString<HlsLinks>(value.jsonObject.toString())
            runCatching {
                val playlist = client.newCall(GET(stream.url)).execute().body!!.string()
                playlist.substringAfter("#EXT-X-STREAM-INF:")
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
                                subtitleTracks = if (hardsub.isNotBlank()) emptyList() else subsList
                            )
                        } catch (_: Error) {
                            Video(videoUrl, quality, videoUrl)
                        }
                    }
            }.getOrNull()
        }
            .filterNotNull()
            .flatten()
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
        Pair("zh-HK", "Chinese (Hong Kong)")
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
            url = this@toSAnime.type!!.let { LinkData(this@toSAnime.id, it).toJsonString() }
            genre = this@toSAnime.series_metadata!!.genres?.joinToString() ?: "Anime"
            status = SAnime.COMPLETED
            var desc = this@toSAnime.description + "\n"
            desc += "\nLanguage: Sub" + (if (this@toSAnime.series_metadata.audio_locales.size > 1) " Dub" else "")
            desc += "\nMaturity Ratings: ${this@toSAnime.series_metadata.maturity_ratings.joinToString()}"
            desc += if (this@toSAnime.series_metadata.is_simulcast) "\nSimulcast" else ""
            desc += "\n\nAudio: " + this@toSAnime.series_metadata.audio_locales.sortedBy { it.getLocale() }.joinToString { it.getLocale() }
            desc += "\n\nSubs: " + this@toSAnime.series_metadata.subtitle_locales.sortedBy { it.getLocale() }.joinToString { it.getLocale() }
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
                { it.quality.contains(subLocale) }
            )
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
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
            key = "preferred_audio"
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
            key = "preferred_sub"
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
            key = "preferred_sub_type"
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
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
