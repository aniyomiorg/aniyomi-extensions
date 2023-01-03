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
class Kamyroll : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Kamyroll"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://api.kamyroll.tech")!! }

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val channelId = "crunchyroll"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = OkHttpClient().newBuilder()
        .addInterceptor(AccessTokenInterceptor(baseUrl, json, preferences)).build()

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/content/v1/updated?channel_id=$channelId&limit=20")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<Updated>(response.body!!.string())
        val animeList = parsed.items.map { ani ->
            SAnime.create().apply {
                title = ani.series_title
                thumbnail_url = ani.images.poster_tall!!.thirdLast().source
                url = LinkData(ani.series_id, "series").toJsonString()
                description = ani.description
            }
        }
        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()
        return GET("$baseUrl/content/v1/search?query=$cleanQuery&channel_id=$channelId")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<SearchResult>(response.body!!.string())
        val animeList = parsed.items.map { media ->
            media.items.map { ani ->
                SAnime.create().apply {
                    title = ani.title
                    thumbnail_url = ani.images.poster_tall!!.thirdLast().source
                    url = LinkData(ani.id, ani.media_type).toJsonString()
                    description = ani.description
                }
            }
        }.flatten()
        return AnimesPage(animeList, false)
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        val response = client.newCall(
            GET("$baseUrl/content/v1/media?id=${mediaId.id}&channel_id=$channelId")
        ).execute()
        return Observable.just(animeDetailsParse(response))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val media = json.decodeFromString<MediaResult>(response.body!!.string())
        val anime = SAnime.create()
        anime.title = media.title
        anime.author = media.content_provider
        anime.status = SAnime.COMPLETED

        var description = media.description + "\n"

        description += "\nLanguage: Sub" + (if (media.is_dubbed) " Dub" else "")

        description += "\nMaturity Ratings: ${media.maturity_ratings}"

        description += if (media.is_simulcast!!) "\nSimulcast" else ""

        anime.description = description

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        val path = if (mediaId.media_type == "series") "seasons" else "movies"
        return GET("$baseUrl/content/v1/$path?id=${mediaId.id}&channel_id=$channelId")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val medias = json.decodeFromString<EpisodeList>(response.body!!.string())

        if (medias.items.first().media_class == "movie") {
            return medias.items.map { media ->
                SEpisode.create().apply {
                    url = media.id
                    name = "Movie"
                    episode_number = 0F
                }
            }
        } else {
            val rawEpsiodes = medias.items.map { season ->
                season.episodes!!.map {
                    RawEpisode(
                        it.id,
                        it.title,
                        it.season_number,
                        it.sequence_number,
                        it.air_date
                    )
                }
            }.flatten()

            return rawEpsiodes.groupBy { "${it.season}_${it.episode}" }
                .mapNotNull { group ->
                    val (season, episode) = group.key.split("_")
                    val ep = episode.toFloatOrNull() ?: 0F
                    SEpisode.create().apply {
                        url = EpisodeData(group.value.map { it.id }).toJsonString()
                        name = if (ep > 0) "Season $season Ep ${df.format(ep)}: " + group.value.first().title else group.value.first().title
                        episode_number = ep
                        date_upload = parseDate(group.value.first().air_date)
                    }
                }.reversed()
        }
    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val urlJson = json.decodeFromString<EpisodeData>(episode.url)
        val videoList = urlJson.ids.parallelMap { vidId ->
            runCatching {
                extractVideo(vidId)
            }.getOrNull()
        }
            .filterNotNull()
            .flatten()
        return Observable.just(videoList.sort())
    }

    // ============================= Utilities ==============================

    private fun extractVideo(vidId: String): List<Video> {
        val url = "$baseUrl/videos/v1/streams?channel_id=$channelId&id=$vidId&type=adaptive_hls"
        val response = client.newCall(GET(url)).execute()
        val streams = json.decodeFromString<VideoStreams>(response.body!!.string())

        val subsList = mutableListOf<Track>()
        val subLocale = preferences.getString("preferred_sub", "en-US")!!
        var subPreferred = 0
        try {
            streams.subtitles.forEach { sub ->
                if (sub.locale == subLocale) {
                    subsList.add(
                        subPreferred,
                        Track(sub.url, sub.locale.getLocale())
                    )
                    subPreferred++
                } else {
                    subsList.add(
                        Track(sub.url, sub.locale.getLocale())
                    )
                }
            }
        } catch (_: Error) { }

        return streams.streams.parallelMap { stream ->
            runCatching {
                val playlist = client.newCall(GET(stream.url)).execute().body!!.string()
                playlist.substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").map {
                        val quality = it.substringAfter("RESOLUTION=").split(",")[0].split("\n")[0].substringAfter("x") + "p" +
                            (if (stream.audio.getLocale().isNotBlank()) " - Aud: ${stream.audio.getLocale()}" else "") +
                            (if (stream.hardsub.getLocale().isNotBlank()) " - HardSub: ${stream.hardsub}" else "")
                        val videoUrl = it.substringAfter("\n").substringBefore("\n")

                        try {
                            Video(videoUrl, quality, videoUrl, subtitleTracks = subsList)
                        } catch (e: Error) {
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

    private val locale = arrayOf(
        Pair("ar-ME", "Arabic"),
        Pair("ar-SA", "Arabic (Saudi Arabia)"),
        Pair("de-DE", "Dutch"),
        Pair("en-US", "English"),
        Pair("es-419", "Spanish"),
        Pair("es-ES", "Spanish (Spain)"),
        Pair("es-LA", "Spanish (Spanish)"),
        Pair("fr-FR", "French"),
        Pair("ja-JP", "Japanese"),
        Pair("it-IT", "Italian"),
        Pair("pt-BR", "Portuguese (Brazil)"),
        Pair("pl-PL", "Polish"),
        Pair("ru-RU", "Russian"),
        Pair("tr-TR", "Turkish"),
        Pair("uk-UK", "Ukrainian"),
        Pair("he-IL", "Hebrew"),
        Pair("ro-RO", "Romanian"),
        Pair("sv-SE", "Swedish")
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
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("kamyroll.tech")
            entryValues = arrayOf("https://api.kamyroll.tech")
            setDefaultValue("https://api.kamyroll.tech")
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

        screen.addPreference(domainPref)
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
