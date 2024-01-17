package eu.kanade.tachiyomi.animeextension.es.animeid

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date

class AnimeID : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeID"

    override val baseUrl = "https://www.animeid.tv/"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#result article.item"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series?sort=views&pag=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.select("a").attr("href"))
        anime.title = element.select("a header").text()
        anime.thumbnail_url = element.select("a figure img").attr("src")
        anime.description = element.select("p div").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "#paginas ul li:nth-last-child(2) a"

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val animeId = document.select("#ord").attr("data-id")
        return episodeJsonParse(response.request.url.toString(), animeId)
    }

    private fun episodeJsonParse(url: String, animeId: String): MutableList<SEpisode> {
        val capList = mutableListOf<SEpisode>()
        var nextPage = 1
        do {
            val headers = headers.newBuilder()
                .set("Referer", url)
                .set("sec-fetch-site", "same-origin")
                .set("x-requested-with", "XMLHttpRequest")
                .set("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                .set("Accept-Language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                .build()

            val responseString = client.newCall(GET("https://www.animeid.tv/ajax/caps?id=$animeId&ord=DESC&pag=$nextPage", headers))
                .execute().asJsoup().body()!!.toString().substringAfter("<body>").substringBefore("</body>")
            val jObject = json.decodeFromString<JsonObject>(responseString)
            val listCaps = jObject["list"]!!.jsonArray
            listCaps!!.forEach { cap ->
                val capParsed = cap.jsonObject
                val epNum = capParsed["numero"]!!.jsonPrimitive.content!!.toFloat()
                val episode = SEpisode.create()
                val dateUpload = manualDateParse(capParsed["date"]!!.jsonPrimitive.content!!.toString())
                episode.episode_number = epNum
                episode.name = "Episodio $epNum"
                dateUpload!!.also { episode.date_upload = it }
                episode.setUrlWithoutDomain(baseUrl + capParsed["href"]!!.jsonPrimitive.content!!.toString())
                capList.add(episode)
            }
            if (listCaps!!.any()) nextPage += 1 else nextPage = -1
        } while (nextPage != -1)
        return capList
    }

    private fun manualDateParse(stringDate: String): Long? {
        return try {
            val format = SimpleDateFormat("dd MMM yyyy")
            format.parse(stringDate!!.toString()).time
        } catch (e: Exception) {
            var dateParsed = stringDate.split(" ")
            val arrMonths = arrayOf("Jun", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            val day = dateParsed[0]!!.trim().toInt()
            val month = arrMonths.indexOf(dateParsed[1].trim()) + 1
            val year = dateParsed[2]!!.trim().toInt()
            Date(year, month, day).time
        }
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("#partes div.container li.subtab div.parte").forEach { script ->
            val jsonString = script.attr("data")
            val jsonUnescape = unescapeJava(jsonString)!!.replace("\\", "")
            val url = jsonUnescape.substringAfter("src=\"").substringBefore("\"").replace("\\\\", "\\")
            if (url.contains("streamtape")) {
                StreamTapeExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
            }
        }
        return videoList
    }

    private fun unescapeJava(escaped: String): String {
        var escaped = escaped
        if (escaped.indexOf("\\u") == -1) return escaped
        var processed = ""
        var position = escaped.indexOf("\\u")
        while (position != -1) {
            if (position != 0) processed += escaped.substring(0, position)
            val token = escaped.substring(position + 2, position + 6)
            escaped = escaped.substring(position + 6)
            processed += token.toInt(16).toChar()
            position = escaped.indexOf("\\u")
        }
        processed += escaped
        return processed
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("#anime figure img.cover")!!.attr("abs:src")
        anime.title = document.selectFirst("#anime section hgroup h1")!!.text()
        anime.description = document.selectFirst("#anime section p.sinopsis")!!.text().removeSurrounding("\"")
        anime.genre = document.select("#anime section ul.tags li a").joinToString { it.text() }
        anime.status = parseStatus(document.select("div.main div section div.status-left div.cuerpo div:nth-child(2) span").text().trim())
        return anime
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val orderByFilter = filters.find { it is OrderByFilter } as OrderByFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&pag=$page&sort=${orderByFilter.toUriPart()}")
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}?pag=$page&sort=${orderByFilter.toUriPart()}")
            orderByFilter.state != 0 -> GET("$baseUrl/series?sort=${orderByFilter.toUriPart()}&pag=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
        OrderByFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("+18", "18"),
            Pair("Acción", "accion"),
            Pair("Animación", "animacion"),
            Pair("Arte", "arte"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventura", "aventura"),
            Pair("Bizarro", "bizarro"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Colegialas", "colegialas"),
            Pair("Comedia", "comedia"),
            Pair("Concert", "concert"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Fantasía", "fantasia"),
            Pair("Fútbol", "futbol"),
            Pair("Game", "game"),
            Pair("Gore", "gore"),
            Pair("Guerra", "guerra"),
            Pair("Harem", "harem"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Idol", "idol"),
            Pair("Infantil", "infantil"),
            Pair("invier", "invier"),
            Pair("Invierno 2013", "invierno-2013"),
            Pair("Invierno 2014", "invierno-2014"),
            Pair("Invierno 2015", "invierno-2015"),
            Pair("Invierno 2016", "invierno-2016"),
            Pair("Invierno 2017", "invierno-2017"),
            Pair("Invierno 2019", "invierno-2019"),
            Pair("Invierno 2020", "invierno-2020"),
            Pair("Invierno 2021", "invierno-2021"),
            Pair("Invierno 2022", "invierno-2022"),
            Pair("Invierno-2018", "invierno-2018"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Juegos De Mesa", "juegos-de-mesa"),
            Pair("Kids", "kids"),
            Pair("Loli", "loli"),
            Pair("Lucha", "lucha"),
            Pair("Mafia", "mafia"),
            Pair("Magia", "magia"),
            Pair("Mahou Shōjo", "mahou-shojo"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Otoño 2012", "otono-2012"),
            Pair("Otoño 2013", "otono-2013"),
            Pair("Otoño 2014", "otono-2014"),
            Pair("Otoño 2015", "otono-2015"),
            Pair("Otoño 2016", "otono-2016"),
            Pair("Otoño 2018", "otono-2018"),
            Pair("Otoño 2019", "otono-2019"),
            Pair("Otoño 2020", "otono-2020"),
            Pair("Otoño 2021", "otono-2021"),
            Pair("otono-2017", "otono-2017"),
            Pair("Pantsu", "pantsu"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Post Apocalitico", "post-apocalitico"),
            Pair("prima", "prima"),
            Pair("Primavera 2013", "primavera-2013"),
            Pair("Primavera 2014", "primavera-2014"),
            Pair("Primavera 2015", "primavera-2015"),
            Pair("Primavera 2016", "primavera-2016"),
            Pair("Primavera 2017", "primavera-2017"),
            Pair("primavera 2018", "primavera-2018"),
            Pair("Primavera 2019", "primavera-2019"),
            Pair("Primavera 2020", "primavera-2020"),
            Pair("Primavera 2021", "primavera-2021"),
            Pair("Primavera 2022", "primavera-2022"),
            Pair("Primvera 2018", "primvera-2018"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos De La Vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shōjo", "shojo"),
            Pair("Shōjo-ai", "shojo-ai"),
            Pair("Shōnen", "shonen"),
            Pair("Shōnen-ai", "shonen-ai"),
            Pair("Shooter", "shooter"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("shounen ai", "shounen-ai"),
            Pair("Slice Of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Super poder", "super-poder"),
            Pair("Supernatural", "supernatural"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Torneo", "torneo"),
            Pair("Tragedia", "tragedia"),
            Pair("Vampiros", "vampiros"),
            Pair("ver", "ver"),
            Pair("vera", "vera"),
            Pair("Verano 2013", "verano-2013"),
            Pair("Verano 2014", "verano-2014"),
            Pair("Verano 2015", "verano-2015"),
            Pair("Verano 2016", "verano-2016"),
            Pair("Verano 2017", "verano-2017"),
            Pair("Verano 2018", "verano-2018"),
            Pair("Verano 2019", "verano-2019"),
            Pair("Verano 2020", "verano-2020"),
            Pair("Verano 2021", "verano-2021"),
            Pair("Verano 2022", "verano-2022"),
            Pair("Violencia", "violencia"),
            Pair("Vocaloid", "vocaloid"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class OrderByFilter : UriPartFilter(
        "Ordenar Por",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Recientes", "newest"),
            Pair("A-Z", "asc"),
            Pair("Más vistos", "views"),
        ),
    )

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emisión") -> SAnime.ONGOING
            statusString.contains("Finalizada") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/series?sort=newest&pag=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred server"
            entries = arrayOf("StreamTape")
            entryValues = arrayOf("StreamTape")
            setDefaultValue("StreamTape")
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
