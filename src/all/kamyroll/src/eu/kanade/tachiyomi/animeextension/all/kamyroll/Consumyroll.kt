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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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

    override val client: OkHttpClient = OkHttpClient().newBuilder()
        .addInterceptor(AccessTokenInterceptor(json, preferences)).build()

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val start = if (page != 1) "start${(page - 1) * 36}&" else ""
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
        val start = if (page != 1) "start${(page - 1) * 36}&" else ""
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
        val media = info.data.first()
        return Observable.just(
            anime.apply {
                author = media.content_provider
                description += "\n\nAudio: " + media.audio_locales!!.joinToString { it.getLocale() } +
                    "\nSubs: " + media.subtitle_locales!!.joinToString { it.getLocale() }
            }
        )
    }

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("not used")

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val mediaId = json.decodeFromString<LinkData>(anime.url)
        return GET("$baseUrl/info/${mediaId.id}?type=${mediaId.type}&fetchAllSeasons=true")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val medias = json.decodeFromString<JsonObject>(response.body!!.string())
        val rawEpisodes = mutableListOf<RawEpisode>()
        medias["episodes"]!!.jsonObject.entries.map { (key, value) ->
            val audLang = key.replace("[^A-Za-z ]".toRegex(), "")
                .replace("Dub", "", true)
                .replace("subbed", "Japanese", true)
            val episodes = value.jsonArray.map {
                json.decodeFromString<EpisodeDto>(it.jsonObject.toString())
            }
            rawEpisodes.addAll(
                episodes.map { ep ->
                    RawEpisode(
                        ep.id,
                        ep.title,
                        ep.season_number,
                        ep.episode_number,
                        ep.releaseDate,
                        audLang
                    )
                }
            )
        }
        return rawEpisodes.sortedBy { it.episode }.groupBy { "${it.season}_${it.episode}" }
            .mapNotNull { group ->
                val (season, episode) = group.key.split("_")
                val ep = episode.toFloatOrNull() ?: 0F
                SEpisode.create().apply {
                    url = EpisodeData(
                        group.value.map { rawEp ->
                            EpisodeData.Episode(rawEp.id, rawEp.audLang)
                        }
                    ).toJsonString()
                    name = if (ep > 0) "Season $season Ep ${df.format(ep)}: " +
                        group.value.first().title else group.value.first().title
                    episode_number = ep
                    date_upload = parseDate(group.value.first().releaseDate)
                }
            }.reversed()
    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val urlJson = json.decodeFromString<EpisodeData>(episode.url)
        val videoList = urlJson.ids.parallelMap { media ->
            runCatching {
                extractVideo(media.epId, media.audLang)
            }.getOrNull()
        }
            .filterNotNull()
            .flatten()
        return Observable.just(videoList.sort())
    }

    // ============================= Utilities ==============================

    private fun extractVideo(vidId: String, audLang: String): List<Video> {
        val response = client.newCall(GET("$baseUrl/episode/$vidId")).execute()
        val body = response.body!!.string()
        val streams = json.decodeFromString<VideoStreams>(body)

        val subsList = mutableListOf<Track>()
        val subLocale = preferences.getString("preferred_sub", "en-US")!!
        var subPreferred = 0
        try {
            streams.subtitles.forEach { sub ->
                if (sub.lang == subLocale) {
                    subsList.add(
                        subPreferred,
                        Track(sub.url, sub.lang.getLocale())
                    )
                    subPreferred++
                } else {
                    subsList.add(
                        Track(sub.url, sub.lang.getLocale())
                    )
                }
            }
        } catch (_: Error) {
        }

        return streams.sources.filter { it.quality.contains("auto") || it.quality.contains("hardsub") }
            .parallelMap { stream ->
                runCatching {
                    val playlist = client.newCall(GET(stream.url)).execute().body!!.string()
                    playlist.substringAfter("#EXT-X-STREAM-INF:")
                        .split("#EXT-X-STREAM-INF:").map {
                            val hardsub = stream.quality.replace("hardsub", "").replace("auto", "").trim()
                                .let { hs ->
                                    if (hs.isNotBlank()) " - HardSub: $hs" else ""
                                }
                            val quality = it.substringAfter("RESOLUTION=")
                                .split(",")[0].split("\n")[0].substringAfter("x") +
                                "p - Aud: $audLang$hardsub"

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

    private val locale = arrayOf(
        Pair("ar-ME", "Arabic"),
        Pair("ar-SA", "Arabic (Saudi Arabia)"),
        Pair("de-DE", "German"),
        Pair("en-US", "English"),
        Pair("es-419", "Spanish (Latin America)"),
        Pair("es-ES", "Spanish (Spain)"),
        Pair("es-LA", "Spanish"),
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
