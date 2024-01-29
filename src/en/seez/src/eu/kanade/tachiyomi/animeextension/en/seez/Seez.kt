package eu.kanade.tachiyomi.animeextension.en.seez

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class Seez : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Seez"

    override val baseUrl = "https://seez.su"

    private val embedUrl = "https://vidsrc.to"

    override val lang = "en"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiKey by lazy {
        val jsUrl = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
            .select("script[defer][src]")[1].attr("abs:src")

        val jsBody = client.newCall(GET(jsUrl, headers)).execute().body.string()
        Regex("""f="(\w{20,})"""").find(jsBody)!!.groupValues[1]
    }

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/javascript, */*; q=0.01")
        add("Host", "api.themoviedb.org")
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = TMDB_URL.newBuilder().apply {
            addPathSegment("movie")
            addPathSegment("popular")
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.buildAPIUrl()

        return GET(url, headers = apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<TmdbResponse>()

        val animeList = data.results.map { ani ->
            val name = ani.title ?: ani.name ?: "Title N/A"

            SAnime.create().apply {
                title = name
                url = LinkData(ani.id, "movie").toJsonString()
                thumbnail_url = ani.posterPath?.let { IMG_URL + it } ?: FALLBACK_IMG
            }
        }

        return AnimesPage(animeList, data.page < data.totalPages)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val collectionFilter = filterList.find { it is CollectionFilter } as CollectionFilter
        val orderFilter = filterList.find { it is OrderFilter } as OrderFilter

        val url = if (query.isNotBlank()) {
            TMDB_URL.newBuilder().apply {
                addPathSegment("search")
                addPathSegment("multi")
                addQueryParameter("query", query)
                addQueryParameter("page", page.toString())
            }.buildAPIUrl()
        } else {
            TMDB_URL.newBuilder().apply {
                addPathSegment(typeFilter.toUriPart())
                addPathSegment(orderFilter.toUriPart())
                if (collectionFilter.state != 0) {
                    addQueryParameter("with_networks", collectionFilter.toUriPart())
                }
                addQueryParameter("language", "en-US")
                addQueryParameter("page", page.toString())
            }.buildAPIUrl()
        }

        return GET(url, headers = apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<TmdbResponse>()

        val animeList = data.results.map { ani ->
            val name = ani.title ?: ani.name ?: "Title N/A"

            SAnime.create().apply {
                title = name
                url = LinkData(ani.id, ani.mediaType).toJsonString()
                thumbnail_url = ani.posterPath?.let { IMG_URL + it } ?: FALLBACK_IMG
                status = if (ani.mediaType == "movie") SAnime.COMPLETED else SAnime.UNKNOWN
            }
        }

        return AnimesPage(animeList, data.page < data.totalPages)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filters are going to be ignored if using search text"),
        TypeFilter(),
        CollectionFilter(),
        OrderFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("Movies", "movie"),
            Pair("TV-shows", "tv"),
        ),
    )

    private class CollectionFilter : UriPartFilter(
        "Collection",
        arrayOf(
            Pair("<select>", ""),
            Pair("Netflix", "213"),
            Pair("HBO Max", "49"),
            Pair("Paramount+", "4330"),
            Pair("Disney+", "2739"),
            Pair("Apple TV+", "2552"),
            Pair("Prime Video", "1024"),
        ),
    )

    private class OrderFilter : UriPartFilter(
        "Order by",
        arrayOf(
            Pair("Popular", "popular"),
            Pair("Top", "top_rated"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val data = json.decodeFromString<LinkData>(anime.url)

        val url = TMDB_URL.newBuilder().apply {
            addPathSegment(data.mediaType)
            addPathSegment(data.id.toString())
        }.buildAPIUrl()

        return GET(url, headers = apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<TmdbDetailsResponse>()

        return SAnime.create().apply {
            author = data.productions?.joinToString { it.name }
            genre = data.genres?.joinToString { it.name }
            status = when (data.status) {
                "Ended", "Released" -> SAnime.COMPLETED
                "In Production" -> SAnime.LICENSED
                "Canceled" -> SAnime.CANCELLED
                "Returning Series" -> {
                    data.nextEpisode?.let { SAnime.ONGOING } ?: SAnime.ON_HIATUS
                }
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                data.overview?.let {
                    appendLine(it)
                    appendLine()
                }
                data.nextEpisode?.let {
                    appendLine("Next: Ep ${it.epNumber} - ${it.name}")
                    appendLine("Air Date: ${it.airDate}")
                    appendLine()
                }
                data.releaseDate?.let { appendLine("Release date: $it") }
                data.firstAirDate?.let { appendLine("First air date: $it") }
                data.lastAirDate?.let { appendLine("Last air date: $it") }
                data.languages?.let { langs ->
                    append("Languages: ")
                    appendLine(langs.joinToString { "${it.engName} (${it.name})" })
                }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<TmdbDetailsResponse>()
        val episodeList = mutableListOf<SEpisode>()

        if (data.title != null) { // movie
            episodeList.add(
                SEpisode.create().apply {
                    name = "Movie"
                    date_upload = parseDate(data.releaseDate!!)
                    episode_number = 1F
                    url = "/movie/${data.id}"
                },
            )
        } else {
            data.seasons.filter { t -> t.seasonNumber != 0 }.forEach { season ->
                val seasonUrl = TMDB_URL.newBuilder().apply {
                    addPathSegment("tv")
                    addPathSegment(data.id.toString())
                    addPathSegment("season")
                    addPathSegment(season.seasonNumber.toString())
                }.buildAPIUrl()

                val seasonData = client.newCall(
                    GET(seasonUrl, headers = apiHeaders),
                ).execute().parseAs<TmdbSeasonResponse>()

                seasonData.episodes.filter { ep ->
                    ep.airDate?.let {
                        DATE_FORMATTER.parse(it)!! <= DATE_FORMATTER.parse(DATE_FORMATTER.format(Date()))
                    } ?: false
                }.forEach { ep ->
                    episodeList.add(
                        SEpisode.create().apply {
                            name = "Season ${season.seasonNumber} Ep. ${ep.epNumber} - ${ep.name}"
                            date_upload = ep.airDate?.let(::parseDate) ?: 0L
                            episode_number = ep.epNumber.toFloat()
                            url = "/tv/${data.id}/${season.seasonNumber}/${ep.epNumber}"
                        },
                    )
                }
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    override fun videoListRequest(episode: SEpisode): Request {
        val docHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Host", embedUrl.toHttpUrl().host)
            add("Referer", "$baseUrl/")
        }.build()

        return GET("$embedUrl/embed${episode.url}", headers = docHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val sourcesHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", embedUrl.toHttpUrl().host)
            add("Referer", response.request.url.toString())
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val dataId = document.selectFirst("ul.episodes li a[data-id]")!!.attr("data-id")
        val sources = client.newCall(
            GET("$embedUrl/ajax/embed/episode/$dataId/sources", headers = sourcesHeaders),
        ).execute().parseAs<EmbedSourceList>().result

        val urlList = sources.map {
            val encrypted = client.newCall(
                GET("$embedUrl/ajax/embed/source/${it.id}", headers = sourcesHeaders),
            ).execute().parseAs<EmbedUrlResponse>().result.url

            Pair(decrypt(encrypted), it.title)
        }

        return urlList.parallelCatchingFlatMapBlocking {
            val url = it.first

            when (val name = it.second) {
                "Vidplay" -> vidsrcExtractor.videosFromUrl(url, name)
                "Filemoon" -> filemoonExtractor.videosFromUrl(url)
                else -> emptyList()
            }
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun HttpUrl.Builder.buildAPIUrl(): String = this.apply {
        addQueryParameter("api_key", apiKey)
    }.build().toString()

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun decrypt(encrypted: String): String {
        var vrf = encrypted.toByteArray()
        vrf = Base64.decode(vrf, Base64.URL_SAFE)

        val rc4Key = SecretKeySpec("8z5Ag5wgagfsOuhz".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        vrf = cipher.doFinal(vrf)

        return URLDecoder.decode(vrf.toString(Charsets.UTF_8), "utf-8")
    }

    companion object {
        private val TMDB_URL = "https://api.themoviedb.org/3".toHttpUrl()
        private const val IMG_URL = "https://image.tmdb.org/t/p/w300/"
        private const val FALLBACK_IMG = "https://seez.su/fallback.png"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidplay"
    }
    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = arrayOf("Vidplay", "Filemoon")
            entryValues = arrayOf("Vidplay", "Filemoon")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
