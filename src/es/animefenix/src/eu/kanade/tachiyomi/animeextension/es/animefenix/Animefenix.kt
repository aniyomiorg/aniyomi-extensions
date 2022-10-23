package eu.kanade.tachiyomi.animeextension.es.animefenix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animefenix.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class Animefenix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeFenix"

    override val baseUrl = "https://www.animefenix.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "article.serie-card"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?order=likes&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(
                element.select("figure.image a").attr("href")
            )
            title = element.select("div.title h3 a").text()
            thumbnail_url = element.select("figure.image a img").attr("src")
            description = element.select("div.serie-card__information p").text()
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination-list li a.pagination-link:contains(Siguiente)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        return document.select("ul.anime-page__episode-list.is-size-6 li").map { it ->
            val epNum = it.select("a span").text().replace("Episodio", "")
            SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                setUrlWithoutDomain(it.select("a").attr("href"))
            }
        }
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val servers = document.selectFirst("script:containsData(var tabsArray)").data()
            .split("tabsArray").map { it.substringAfter("src='").substringBefore("'").replace("amp;", "") }
            .filter { it.contains("https") }

        servers.forEach { server ->
            val decodedUrl = URLDecoder.decode(server, "UTF-8")
            val realUrl = try {
                client.newCall(GET(decodedUrl)).execute().asJsoup().selectFirst("script")
                    .data().substringAfter("src=\"").substringBefore("\"")
            } catch (e: Exception) { "" }
            /*
            in case this is too slow:
            Animefenix redirect links are associated with an id, ex: id:9=Amazon ; id:2=Fembed ; etc. ( $baseUrl/redirect.php?player=$id )
            can be obtained in an easy way by adding this line :
            Log.i("bruh", "${server.substringAfter("?player=").substringBefore("&")} = $realUrl}")
            and play any episode,
            the "code" part in the url represents represents what comes after the main domain like /embed/ or /v/ or /e/
            ex of full url: $baseUrl/redirect.php?player=2&amp;code=4mdmxtzmpe8768k&amp;
            in this case the playerId represent fembed and the full url is : https://www.fembed.com/v/4mdmxtzmpe8768k
            */

            when {
                realUrl.contains("ok.ru") -> {
                    val okruVideos = OkruExtractor(client).videosFromUrl(realUrl)
                    videoList.addAll(okruVideos)
                }
                realUrl.contains("fembed") -> {
                    val fbedVideos = FembedExtractor(client).videosFromUrl(realUrl)
                    videoList.addAll(fbedVideos)
                }
                realUrl.contains("/stream/amz.php?") -> {
                    val video = amazonExtractor(baseUrl + realUrl.substringAfter(".."))
                    if (video.isNotBlank()) {
                        if (realUrl.contains("&ext=es")) {
                            videoList.add(Video(video, "Amazon ES", video))
                        } else {
                            videoList.add(Video(video, "Amazon", video))
                        }
                    }
                }
                realUrl.contains("/stream/fl.php") -> {
                    val video = realUrl.substringAfter("/stream/fl.php?v=")
                    try {
                        if (client.newCall(GET(video)).execute().code == 200) {
                            videoList.add(Video(video, "FireLoad", video))
                        }
                    } catch (e: Exception) {}
                }
                realUrl.contains("streamtape") -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(realUrl, "StreamTape")
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                realUrl.contains("sbthe") -> {
                    videoList.addAll(
                        StreamSBExtractor(client).videosFromUrl(realUrl, headers)
                    )
                }
                realUrl.contains("mp4upload") -> {
                    val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                    val video = Mp4uploadExtractor().getVideoFromUrl(realUrl, headers)
                    videoList.add(video)
                }
            }
        }
        return videoList.filter { it.url.contains("https") || it.url.contains("http") }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) }
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "Amazon")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                videoSorted.drop(preferredIdx + 1)
                videoSorted[0] = videoSorted[preferredIdx]
            }
            videoSorted.toList()
        } catch (e: Exception) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val yearFilter = filters.find { it is YearFilter } as YearFilter
        val stateFilter = filters.find { it is StateFilter } as StateFilter
        val typeFilter = filters.find { it is TypeFilter } as TypeFilter

        val genreFilter = (filters.find { it is TagFilter } as TagFilter).state.filter { it.state }

        var filterUrl = "$baseUrl/animes?"
        if (query.isNotBlank()) {
            filterUrl += "&q=$query"
        } // search by name
        if (genreFilter.isNotEmpty()) {
            genreFilter.forEach {
                filterUrl += "&genero[]=${it.name}"
            }
        } // search by genre
        if (yearFilter.state.isNotBlank()) {
            filterUrl += "&year[]=${yearFilter.state}"
        } // search by year
        if (stateFilter.state != 0) {
            filterUrl += "&estado[]=${stateFilter.toUriPart()}"
        } // search by state
        if (typeFilter.state != 0) {
            filterUrl += "&type[]=${typeFilter.toUriPart()}"
        } // search by type
        filterUrl += "&page=$page" // add page

        return when {
            genreFilter.isEmpty() || yearFilter.state.isNotBlank() ||
                stateFilter.state != 0 || typeFilter.state != 0 || query.isNotBlank() -> GET(filterUrl, headers)
            else -> GET("$baseUrl/animes?order=likes&page=$page ")
        }
    }
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create().apply {
            title = document.select("h1.title.has-text-orange").text()
            genre = document.select("a.button.is-small.is-orange.is-outlined.is-roundedX").joinToString { it.text() }
            status = parseStatus(document.select("div.column.is-12-mobile.xis-3-tablet.xis-3-desktop.xhas-background-danger.is-narrow-tablet.is-narrow-desktop a").text())
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?order=added&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    private fun amazonExtractor(url: String): String {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoURl = document.selectFirst("script:containsData(sources: [)").data()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")

        return try {
            if (client.newCall(GET(videoURl)).execute().code == 200) videoURl else ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagFilter("Generos", checkboxesFrom(genreList)),
        StateFilter(),
        TypeFilter(),
        YearFilter(),
    )

    private val genreList = arrayOf(
        Pair("Acción", "acción"),
        Pair("Aventura", "aventura"),
        Pair("Angeles", "angeles"),
        Pair("Artes Marciales", "artes-marciales"),
        Pair("Ciencia Ficcion", "ciencia-ficcion"),
        Pair("Comedia", "comedia"),
        Pair("Cyberpunk", "cyberpunk"),
        Pair("Demonios", "demonios"),
        Pair("Deportes", "deportes"),
        Pair("Dragones", "dragones"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Escolares", "escolares"),
        Pair("Fantasía", "fantasía"),
        Pair("Gore", "gore"),
        Pair("Harem", "harem"),
        Pair("Historico", "historico"),
        Pair("Horror", "horror"),
        Pair("Infantil", "infantil"),
        Pair("Isekai", "isekai"),
        Pair("Josei", "josei"),
        Pair("Juegos", "juegos"),
        Pair("Magia", "magia"),
        Pair("Mecha", "mecha"),
        Pair("Militar", "militar"),
        Pair("Misterio", "misterio"),
        Pair("Música", "música"),
        Pair("Ninjas", "ninjas"),
        Pair("Parodias", "parodias"),
        Pair("Policia", "policia"),
        Pair("Psicológico", "psicológico"),
        Pair("Recuerdos de la vida", "recuerdos-de-la-vida"),
        Pair("Romance", "romance"),
        Pair("Samurai", "samurai"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shonen", "shonen"),
        Pair("Slice of life", "slice-of-life"),
        Pair("Sobrenatural", "sobrenatural"),
        Pair("Space", "space"),
        Pair("Spokon", "spokon"),
        Pair("SteamPunk", "steampunk"),
        Pair("SuperPoder", "superpoder"),
        Pair("Vampiros", "vampiros"),
        Pair("Yaoi", "yaoi"),
        Pair("Yuri", "yuri")
    )

    private fun checkboxesFrom(tagArray: Array<Pair<String, String>>): List<TagCheckBox> = tagArray.map { TagCheckBox(it.second) }

    class TagCheckBox(tag: String) : AnimeFilter.CheckBox(tag, false)
    class TagFilter(name: String, checkBoxes: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>(name, checkBoxes)

    private class YearFilter : AnimeFilter.Text("Año", "2022")
    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Emision", "1"),
            Pair("Finalizado", "2"),
            Pair("Proximamente", "3"),
            Pair("En Cuarentena", "4")
        )
    )
    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("TV", "tv"),
            Pair("Pelicula", "movie"),
            Pair("Especial", "special"),
            Pair("OVA", "ova")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf(
                "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
                "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
                "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
                "Amazon", "AmazonES", "StreamTape", "Fireload", "Mp4upload"
            )
            entryValues = arrayOf(
                "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
                "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
                "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
                "Amazon", "AmazonES", "StreamTape", "Fireload", "Mp4upload"
            )
            setDefaultValue("Amazon")
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
