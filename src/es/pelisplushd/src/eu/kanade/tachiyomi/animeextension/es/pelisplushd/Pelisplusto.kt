package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Pelisplusto(override val name: String, override val baseUrl: String) : Pelisplushd(name, baseUrl) {

    private val json: Json by injectLazy()

    override val supportsLatest = false

    override fun popularAnimeSelector(): String = "article.item"

    override fun popularAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h2").text()
        anime.thumbnail_url = element.select("a .item__image picture img").attr("data-src")
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(".home__slider_content div h1.slugh1")!!.text()
        anime.description = document.selectFirst(".home__slider_content .description")!!.text()
        anime.genre = document.select(".home__slider_content div:nth-child(5) > a").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        } else {
            var jsonscript = ""
            jsoup.select("script[type=text/javascript]").mapNotNull { script ->
                val ssRegex = Regex("(?i)seasons")
                val ss = if (script.data().contains(ssRegex)) script.data() else ""
                val swaa = ss.substringAfter("seasonsJson = ").substringBefore(";")
                jsonscript = swaa
            }
            val jsonParse = json.decodeFromString<JsonObject>(jsonscript)
            var index = 0
            jsonParse.entries.map {
                it.value.jsonArray.reversed().map { element ->
                    index += 1
                    val jsonElement = element!!.jsonObject
                    val season = jsonElement["season"]!!.jsonPrimitive!!.content
                    val title = jsonElement["title"]!!.jsonPrimitive!!.content
                    val ep = jsonElement["episode"]!!.jsonPrimitive!!.content
                    val episode = SEpisode.create()
                    episode.episode_number = index.toFloat()
                    episode.name = "T$season - E$ep - $title"
                    episode.setUrlWithoutDomain("${response.request.url}/season/$season/episode/$ep")
                    episodes.add(episode)
                }
            }
        }
        return episodes.reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/api/search?search=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(".bg-tabs li").map { it ->
            val link = it.attr("data-server")
                .replace("https://owodeuwu.xyz", "https://fembed.com")
                .replace("https://sblanh.com", "https://watchsb.com")
                .replace(Regex("([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https:\\/\\/ww3.pelisplus.to.*"), "")

            if (link.contains("https://dood.")) {
                try {
                    DoodExtractor(client).videoFromUrl(link, "DoodStream", false)!!.let { video ->
                        videoList.add(video)
                    }
                } catch (_: Exception) {}
            }
            if (link.contains("fembed")) {
                try {
                    FembedExtractor(client).videosFromUrl(link).map { video ->
                        videoList.add(video)
                    }
                } catch (_: Exception) {}
            }
            if (link.contains("watchsb")) {
                try {
                    val newHeaders = headers.newBuilder()
                        .set("referer", link)
                        .set("watchsb", "sbstream")
                        .set("authority", link.substringBefore("/e/").substringAfter("https://"))
                        .build()
                    StreamSBExtractor(client).videosFromDecryptedUrl(fixUrl(link), headers = newHeaders).map { video ->
                        videoList.add(video)
                    }
                } catch (_: Exception) {}
            }
        }
        return videoList
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por genero ignora los otros filtros"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "doramas"),
            Pair("Animes", "animes"),
            Pair("Acción", "genres/accion"),
            Pair("Action & Adventure", "genres/action-adventure"),
            Pair("Animación", "genres/animacion"),
            Pair("Aventura", "genres/aventura"),
            Pair("Bélica", "genres/belica"),
            Pair("Ciencia ficción", "genres/ciencia-ficcion"),
            Pair("Comedia", "genres/comedia"),
            Pair("Crimen", "genres/crimen"),
            Pair("Documental", "genres/documental"),
            Pair("Dorama", "genres/dorama"),
            Pair("Drama", "genres/drama"),
            Pair("Familia", "genres/familia"),
            Pair("Fantasía", "genres/fantasia"),
            Pair("Guerra", "genres/guerra"),
            Pair("Historia", "genres/historia"),
            Pair("Horror", "genres/horror"),
            Pair("Kids", "genres/kids"),
            Pair("Misterio", "genres/misterio"),
            Pair("Música", "genres/musica"),
            Pair("Musical", "genres/musical"),
            Pair("Película de TV", "genres/pelicula-de-tv"),
            Pair("Reality", "genres/reality"),
            Pair("Romance", "genres/romance"),
            Pair("Sci-Fi & Fantasy", "genres/sci-fi-fantasy"),
            Pair("Soap", "genres/soap"),
            Pair("Suspense", "genres/suspense"),
            Pair("Terror", "genres/terror"),
            Pair("War & Politics", "genres/war-politics"),
            Pair("Western", "genres/western"),
        ),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
            "DoodStream",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Voex")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
