package eu.kanade.tachiyomi.animeextension.es.gnula

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class Gnula : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Gnula"

    override val baseUrl = "https://gnula.life"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "StreamHideVid",
        )

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[CAST]", "[SUB]")

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/archives/movies/page/$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data()
            ?: return AnimesPage(emptyList(), false)
        val jsonData = jsonString.parseTo<PopularModel>().props.pageProps
        val hasNextPage = document.selectFirst("ul.pagination > li.page-item.active ~ li > a > span.visually-hidden")?.text()?.contains("Next") ?: false
        var type = jsonData.results.typename ?: ""

        val animeList = jsonData.results.data.map {
            if (!it.url.slug.isNullOrEmpty()) {
                type = when {
                    "series" in it.url.slug -> "PaginatedSerie"
                    "movies" in it.url.slug -> "PaginatedMovie"
                    else -> ""
                }
            }

            SAnime.create().apply {
                title = it.titles.name ?: ""
                thumbnail_url = it.images.poster?.replace("/original/", "/w200/")
                setUrlWithoutDomain(urlSolverByType(type, it.slug.name ?: ""))
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/archives/movies/releases/page/$page")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (response.request.url.toString().contains("/movies/")) {
            listOf(
                SEpisode.create().apply {
                    name = "Película"
                    episode_number = 1F
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } else {
            val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return emptyList()
            val jsonData = jsonString.parseTo<SeasonModel>().props.pageProps
            var episodeCounter = 1F
            jsonData.post.seasons
                .flatMap { season ->
                    season.episodes.map { ep ->
                        val episode = SEpisode.create().apply {
                            episode_number = episodeCounter++
                            name = "T${season.number} - E${ep.number} - ${ep.title}"
                            date_upload = ep.releaseDate?.toDate() ?: 0L
                            setUrlWithoutDomain("$baseUrl/series/${ep.slug.name}/seasons/${ep.slug.season}/episodes/${ep.slug.episode}")
                        }
                        episode
                    }
                }
        }.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return emptyList()

        if (response.request.url.toString().contains("/movies/")) {
            val pageProps = jsonString.parseTo<SeasonModel>().props.pageProps
            pageProps.post.players.latino.toVideoList("[LAT]").also(videoList::addAll)
            pageProps.post.players.spanish.toVideoList("[CAST]").also(videoList::addAll)
            pageProps.post.players.english.toVideoList("[SUB]").also(videoList::addAll)
        } else {
            val pageProps = jsonString.parseTo<EpisodeModel>().props.pageProps
            pageProps.episode.players.latino.toVideoList("[LAT]").also(videoList::addAll)
            pageProps.episode.players.spanish.toVideoList("[CAST]").also(videoList::addAll)
            pageProps.episode.players.english.toVideoList("[SUB]").also(videoList::addAll)
        }
        return videoList
    }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("voe") -> VoeExtractor(client).videosFromUrl(url, prefix)
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url, prefix)
            embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") -> {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "$prefix Filemoon:", headers = vidHeaders)
            }
            embedUrl.contains("uqload") -> UqloadExtractor(client).videosFromUrl(url, prefix = prefix)
            embedUrl.contains("mp4upload") -> Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix = prefix)
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
            }
            embedUrl.contains("doodstream") || embedUrl.contains("dood.") || embedUrl.contains("ds2play") || embedUrl.contains("doods.") -> {
                val url2 = url.replace("https://doodstream.com/e/", "https://dood.to/e/")
                listOf(DoodExtractor(client).videoFromUrl(url2, "$prefix DoodStream", false)!!)
            }
            embedUrl.contains("streamlare") -> StreamlareExtractor(client).videosFromUrl(url, prefix = prefix)
            embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)
            embedUrl.contains("burstcloud") || embedUrl.contains("burst") -> BurstCloudExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)
            embedUrl.contains("fastream") -> FastreamExtractor(client, headers).videosFromUrl(url, prefix = "$prefix Fastream:")
            embedUrl.contains("upstream") -> UpstreamExtractor(client).videosFromUrl(url, prefix = prefix)
            embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> listOf(StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape")!!)
            embedUrl.contains("ahvsh") || embedUrl.contains("streamhide") || embedUrl.contains("guccihide") || embedUrl.contains("streamvid") -> StreamHideVidExtractor(client).videosFromUrl(url, "$prefix StreamHide")
            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query&p=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return SAnime.create()
        val json = jsonString.parseTo<SeasonModel>()
        val post = json.props.pageProps.post
        return SAnime.create().apply {
            title = post.titles.name ?: ""
            thumbnail_url = post.images.poster
            description = post.overview
            genre = post.genres.joinToString { it.name ?: "" }
            artist = post.cast.acting.firstOrNull()?.name ?: ""
            status = if (json.page.contains("movie", true)) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    private fun List<Region>.toVideoList(lang: String): List<Video> {
        return this.parallelCatchingFlatMapBlocking {
            var url = ""
            client.newCall(GET(it.result)).execute().asJsoup().select("script").map { sc ->
                if (sc.data().contains("var url = '")) {
                    url = sc.data().substringAfter("var url = '").substringBefore("';")
                }
            }
            serverVideoResolver(url, lang)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Películas", "archives/movies/releases"),
            Pair("Series", "archives/series/releases"),
            Pair("Acción", "genres/accion"),
            Pair("Animación", "genres/animacion"),
            Pair("Crimen", "genres/crimen"),
            Pair("Fámilia", "genres/familia"),
            Pair("Misterio", "genres/misterio"),
            Pair("Suspenso", "genres/suspenso"),
            Pair("Aventura", "genres/aventura"),
            Pair("Ciencia Ficción", "genres/ciencia-ficcion"),
            Pair("Drama", "genres/drama"),
            Pair("Fantasía", "genres/fantasia"),
            Pair("Romance", "genres/romance"),
            Pair("Terror", "genres/terror"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> String.parseTo(): T {
        return json.decodeFromString<T>(this)
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
    }

    private fun urlSolverByType(type: String, slug: String): String {
        return when (type) {
            "PaginatedMovie", "PaginatedGenre" -> "$baseUrl/movies/$slug"
            "PaginatedSerie" -> "$baseUrl/series/$slug"
            else -> ""
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
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
            entries = SERVER_LIST
            entryValues = SERVER_LIST
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

    // Not Used
    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()
    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector() = throw UnsupportedOperationException()
    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    // Not Used
}
