package eu.kanade.tachiyomi.animeextension.es.mundodonghua

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.mundodonghua.extractors.JsUnpacker
import eu.kanade.tachiyomi.animeextension.es.mundodonghua.extractors.ProteaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class MundoDonghua : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MundoDonghua"

    override val baseUrl = "https://www.mundodonghua.com"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.container.new-donghua-grid.sm-row div.col-md-9 div.sm-row.bg-white.pt-20.pr-20.pb-15.pl-20.br-8.of-a div.row div.item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/lista-donghuas/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(getExternalOrInternalUrl(element.select("a.angled-img").attr("href")))
        anime.title = element.select("a.angled-img div.bottom-info.white h5").text().removeSurrounding("\"")
        anime.thumbnail_url = baseUrl + element.select("a.angled-img div.img img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:last-child a"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = getExternalOrInternalUrl(
            document.selectFirst("div.col-md-4.col-xs-12.mb-10 div.row.sm-row > div.side-banner > div.banner-side-serie")
                .attr("style").substringAfter("background-image: url(").substringBefore(")")
        )
        anime.title = document.selectFirst("div.col-md-4.col-xs-12.mb-10 div.row.sm-row div div.sf.fc-dark.ls-title-serie").html()
        anime.description = document.selectFirst("section div.row div.col-md-8 div.sm-row p.text-justify").text().removeSurrounding("\"")
        anime.genre = document.select("div.col-md-8.col-xs-12 div.sm-row a.generos span.label").joinToString { it.text() }
        anime.status = parseStatus(document.select("div.col-md-4.col-xs-12.mb-10 div.row.sm-row div:nth-child(2) div:nth-child(2) p span.badge").text())
        return anime
    }

    override fun episodeListSelector() = "div.sm-row.mt-10 div.donghua-list-scroll ul.donghua-list a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.attr("href").split("/").last().toFloat()
        episode.setUrlWithoutDomain(getExternalOrInternalUrl(element.attr("href")))
        episode.episode_number = epNum
        episode.name = "Episodio $epNum"
        return episode
    }

    private fun getAndUnpack(string: String): Sequence<String> {
        return JsUnpacker.unpack(string)
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = Regex("""(https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))""")
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.isEmpty()) return ""
        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) return "https:$url"
        else {
            if (url.startsWith('/')) return baseUrl + url
            return "$baseUrl/$url"
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e")) {
                val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
                packedRegex.findAll(script.data()).map {
                    it.value
                }.toList().map {
                    val unpack = getAndUnpack(it)
                    fetchUrls(unpack!!.first()).map { url ->
                        if (url.contains("diasfem")) {
                            var serverUrl = url.replace("diasfem", "embedsito")
                            videoList.addAll(
                                FembedExtractor(client).videosFromUrl(serverUrl)
                            )
                        }
                    }
                    if (unpack!!.first()!!.contains("protea_tab")) {
                        val protearegex = Regex("(protea_tab.*slug.*,type)")
                        val slug = protearegex.findAll(unpack!!.first()).map {
                            it.value.replace(Regex("(protea_tab.*slug\":\")"), "").replace("\"},type", "")
                        }.first()
                        val requestlink = "$baseUrl/api_donghua.php?slug=$slug"
                        val headers = headers.newBuilder()
                            .set("authority", "www.mundodonghua.com")
                            .set("accept", "*/*")
                            .set("accept-language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                            .set("dnt", "1")
                            .set("Connection", "keep-alive")
                            .set("Sec-Fetch-Dest", "empty")
                            .set("Sec-Fetch-Mode", "no-cors")
                            .set("Sec-Fetch-Site", "same-origin")
                            .set("TE", "trailers")
                            .set("Pragma", "no-cache")
                            .set("Cache-Control", "no-cache")
                            .set("referer", response!!.request!!.url!!.toString())
                            .set("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.0.0 Safari/537.36")
                            .build()
                        ProteaExtractor().videosFromUrl(requestlink, "Protea", headers = headers).map { vid -> videoList.add(vid) }
                    }
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) }
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "Fembed:720p")
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
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/busquedas/$query")
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Acción", "Acción"),
            Pair("Artes Marciales", "Artes Marciales"),
            Pair("Aventura", "Aventura"),
            Pair("Ciencia Ficción", "Ciencia Ficción"),
            Pair("Comedia", "Comedia"),
            Pair("Comida", "Comida"),
            Pair("Cultivación", "Cultivación"),
            Pair("Demonios", "Demonios"),
            Pair("Deportes", "Deportes"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Escolar", "Escolar"),
            Pair("Fantasía", "Fantasía"),
            Pair("Harem", "Harem"),
            Pair("Harem Inverso", "Harem Inverso"),
            Pair("Historico", "Historico"),
            Pair("Idols", "Idols"),
            Pair("Juegos", "Juegos"),
            Pair("Lucha", "Lucha"),
            Pair("Magia", "Magia"),
            Pair("Mechas", "Mechas"),
            Pair("Militar", "Militar"),
            Pair("Misterio", "Misterio"),
            Pair("Música", "Música"),
            Pair("Por Definir", "Por Definir"),
            Pair("Psicológico", "Psicológico"),
            Pair("Reencarnación", "Reencarnación"),
            Pair("Romance", "Romance"),
            Pair("Seinen", "Seinen"),
            Pair("Shojo", "Shojo"),
            Pair("Shonen", "Shonen"),
            Pair("Sobrenatural", "Sobrenatural"),
            Pair("Sucesos de la Vida", "Sucesos de la Vida"),
            Pair("Superpoderes", "Superpoderes"),
            Pair("Suspenso", "Suspenso"),
            Pair("Terror", "Terror"),
            Pair("Vampiros", "Vampiros"),
            Pair("Viaje a Otro Mundo", "Viaje a Otro Mundo"),
            Pair("Videojuegos", "Videojuegos"),
            Pair("Zombis", "Zombis")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    private fun getExternalOrInternalUrl(url: String): String {
        return if (url.contains("https")) url else "$baseUrl/$url"
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizada") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Protea:1080p", "Protea:720p", "Protea:480p", "Protea:380p", "Protea:360p",
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:380p", "Fembed:360p"
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Fembed:720p")
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
