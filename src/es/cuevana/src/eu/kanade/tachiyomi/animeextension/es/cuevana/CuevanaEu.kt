package eu.kanade.tachiyomi.animeextension.es.cuevana

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.cuevana.models.AnimeEpisodesList
import eu.kanade.tachiyomi.animeextension.es.cuevana.models.PopularAnimeList
import eu.kanade.tachiyomi.animeextension.es.cuevana.models.Videos
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat

class CuevanaEu(override val name: String, override val baseUrl: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "es"

    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[ENG]", "[CAST]", "[JAP]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "Tomatomatela", "YourUpload", "Doodstream", "Okru",
            "Voe", "StreamTape", "StreamWish", "Filemoon",
            "FileLions",
        )
    }

    override fun popularAnimeSelector(): String = ".MovieList .TPostMv .TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas/estrenos/page/$page")

    override fun popularAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.select("nav.navigation > div.nav-links > a.next.page-numbers").any()
        val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()

        val responseJson = json.decodeFromString<PopularAnimeList>(script)
        responseJson.props?.pageProps?.movies?.map { animeItem ->
            val anime = SAnime.create()
            val preSlug = animeItem.url?.slug ?: ""
            val type = if (preSlug.startsWith("series")) "ver-serie" else "ver-pelicula"

            anime.title = animeItem.titles?.name ?: ""
            anime.thumbnail_url = animeItem.images?.poster?.replace("/original/", "/w200/") ?: ""
            anime.description = animeItem.overview
            anime.setUrlWithoutDomain("/$type/${animeItem.slug?.name}")
            animeList.add(anime)
        }

        return AnimesPage(animeList, hasNextPage)
    }

    override fun popularAnimeNextPageSelector(): String = "uwu"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        if (response.request.url.toString().contains("/ver-serie/")) {
            val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
            val responseJson = json.decodeFromString<AnimeEpisodesList>(script)
            responseJson.props?.pageProps?.thisSerie?.seasons?.map {
                it.episodes.map { ep ->
                    val episode = SEpisode.create()
                    val epDate = try {
                        ep.releaseDate?.substringBefore("T")?.let { date -> SimpleDateFormat("yyyy-MM-dd").parse(date) }
                    } catch (e: Exception) {
                        null
                    }
                    if (epDate != null) episode.date_upload = epDate.time
                    episode.name = "T${ep.slug?.season} - Episodio ${ep.slug?.episode}"
                    episode.episode_number = ep.number?.toFloat()!!
                    episode.setUrlWithoutDomain("/episodio/${ep.slug?.name}-temporada-${ep.slug?.season}-episodio-${ep.slug?.episode}")
                    episodes.add(episode)
                }
            }
        } else {
            val episode = SEpisode.create().apply {
                episode_number = 1f
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
        val responseJson = json.decodeFromString<AnimeEpisodesList>(script)
        if (response.request.url.toString().contains("/episodio/")) {
            serverIterator(responseJson.props?.pageProps?.episode?.videos).let {
                videoList.addAll(it)
            }
        } else {
            serverIterator(responseJson.props?.pageProps?.thisMovie?.videos).let {
                videoList.addAll(it)
            }
        }
        return videoList
    }

    private fun serverIterator(videos: Videos?): MutableList<Video> {
        val videoList = mutableListOf<Video>()
        videos?.latino?.parallelMapBlocking {
            try {
                val body = client.newCall(GET(it.result!!)).execute().asJsoup()
                val url = body.selectFirst("script:containsData(var message)")?.data()?.substringAfter("var url = '")?.substringBefore("'") ?: ""
                loadExtractor(url, "[LAT]").let { videoList.addAll(it) }
            } catch (_: Exception) { }
        }
        videos?.spanish?.map {
            try {
                val body = client.newCall(GET(it.result!!)).execute().asJsoup()
                val url = body.selectFirst("script:containsData(var message)")?.data()?.substringAfter("var url = '")?.substringBefore("'") ?: ""
                loadExtractor(url, "[CAST]").let { videoList.addAll(it) }
            } catch (_: Exception) { }
        }
        videos?.english?.map {
            try {
                val body = client.newCall(GET(it.result!!)).execute().asJsoup()
                val url = body.selectFirst("script:containsData(var message)")?.data()?.substringAfter("var url = '")?.substringBefore("'") ?: ""
                loadExtractor(url, "[ENG]").let { videoList.addAll(it) }
            } catch (_: Exception) { }
        }
        videos?.japanese?.map {
            val body = client.newCall(GET(it.result!!)).execute().asJsoup()
            val url = body.selectFirst("script:containsData(var message)")?.data()?.substringAfter("var url = '")?.substringBefore("'") ?: ""
            loadExtractor(url, "[JAP]").let { videoList.addAll(it) }
        }
        return videoList
    }

    private fun loadExtractor(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        if (embedUrl.contains("tomatomatela")) {
            try {
                val mainUrl = url.substringBefore("/embed.html#").substringAfter("https://")
                val headers = headers.newBuilder()
                    .set("authority", mainUrl)
                    .set("accept", "application/json, text/javascript, */*; q=0.01")
                    .set("accept-language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                    .set("sec-ch-ua", "\"Chromium\";v=\"106\", \"Google Chrome\";v=\"106\", \"Not;A=Brand\";v=\"99\"")
                    .set("sec-ch-ua-mobile", "?0")
                    .set("sec-ch-ua-platform", "Windows")
                    .set("sec-fetch-dest", "empty")
                    .set("sec-fetch-mode", "cors")
                    .set("sec-fetch-site", "same-origin")
                    .set("x-requested-with", "XMLHttpRequest")
                    .build()
                val token = url.substringAfter("/embed.html#")
                val urlRequest = "https://$mainUrl/details.php?v=$token"
                val response = client.newCall(GET(urlRequest, headers = headers)).execute().asJsoup()
                val bodyText = response.select("body").text()
                val json = json.decodeFromString<JsonObject>(bodyText)
                val status = json["status"]!!.jsonPrimitive!!.content
                val file = json["file"]!!.jsonPrimitive!!.content
                if (status == "200") { videoList.add(Video(file, "$prefix Tomatomatela", file, headers = null)) }
            } catch (_: Exception) { }
        }
        if (embedUrl.contains("yourupload")) {
            val videos = YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            videoList.addAll(videos)
        }
        if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
            DoodExtractor(client).videoFromUrl(url, "$prefix DoodStream", false)
                ?.let { videoList.add(it) }
        }
        if (embedUrl.contains("okru") || embedUrl.contains("ok.ru")) {
            OkruExtractor(client).videosFromUrl(url, prefix, true).also(videoList::addAll)
        }
        if (embedUrl.contains("voe")) {
            VoeExtractor(client).videosFromUrl(url, prefix).also(videoList::addAll)
        }
        if (embedUrl.contains("streamtape")) {
            StreamTapeExtractor(client).videoFromUrl(url, "$prefix StreamTape")?.let { videoList.add(it) }
        }
        if (embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("wish")) {
            StreamWishExtractor(client, headers).videosFromUrl(url) { "$prefix StreamWish:$it" }
                .also(videoList::addAll)
        }
        if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
            FilemoonExtractor(client).videosFromUrl(url, "$prefix Filemoon:").also(videoList::addAll)
        }
        if (embedUrl.contains("filelions") || embedUrl.contains("lion")) {
            StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$prefix FileLions:$it" }).also(videoList::addAll)
        }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

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
            query.isNotBlank() -> GET("$baseUrl/search?q=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document) = throw UnsupportedOperationException()

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val newAnime = SAnime.create()
        val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
        val responseJson = json.decodeFromString<AnimeEpisodesList>(script)
        if (response.request.url.toString().contains("/ver-serie/")) {
            val data = responseJson.props?.pageProps?.thisSerie
            newAnime.status = SAnime.UNKNOWN
            newAnime.title = data?.titles?.name ?: ""
            newAnime.description = data?.overview ?: ""
            newAnime.thumbnail_url = data?.images?.poster?.replace("/original/", "/w500/")
            newAnime.genre = data?.genres?.joinToString { it.name ?: "" }
            newAnime.artist = data?.cast?.acting?.firstOrNull()?.name ?: ""
            newAnime.setUrlWithoutDomain(response.request.url.toString())
        } else {
            val data = responseJson.props?.pageProps?.thisMovie
            newAnime.status = SAnime.UNKNOWN
            newAnime.title = data?.titles?.name ?: ""
            newAnime.description = data?.overview ?: ""
            newAnime.thumbnail_url = data?.images?.poster?.replace("/original/", "/w500/")
            newAnime.genre = data?.genres?.joinToString { it.name ?: "" }
            newAnime.artist = data?.cast?.acting?.firstOrNull()?.name ?: ""
            newAnime.setUrlWithoutDomain(response.request.url.toString())
        }

        return newAnime
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Series", "series/estrenos"),
            Pair("Acción", "genero/accion"),
            Pair("Aventura", "genero/aventura"),
            Pair("Animación", "genero/animacion"),
            Pair("Ciencia Ficción", "genero/ciencia-ficcion"),
            Pair("Comedia", "genero/comedia"),
            Pair("Crimen", "genero/crimen"),
            Pair("Documentales", "genero/documental"),
            Pair("Drama", "genero/drama"),
            Pair("Familia", "genero/familia"),
            Pair("Fantasía", "genero/fantasia"),
            Pair("Misterio", "genero/misterio"),
            Pair("Romance", "genero/romance"),
            Pair("Suspenso", "genero/suspense"),
            Pair("Terror", "genero/terror"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
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
}
