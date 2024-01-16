package eu.kanade.tachiyomi.animeextension.es.pelisflix

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

open class Pelisflix(override val name: String, override val baseUrl: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "es"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas-online/page/$page")

    override fun popularAnimeSelector(): String = "#Tf-Wp div.TpRwCont ul.MovieList li.TPostMv article.TPost"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h2.Title").text()
        anime.thumbnail_url = externalOrInternalImg(element.selectFirst("a div.Image figure.Objf img.imglazy")!!.attr("data-src"))
        anime.description = element.select("div.TPMvCn div.Description p:nth-child(1)").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "nav.wp-pagenavi div.nav-links a ~ a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val movie = document.select("ul.optnslst li div[data-url]")
        val seasons = document.select("main .SeasonBx a")
        if (movie.any()) {
            val movieEp = SEpisode.create()
            movieEp.name = "Pelicula"
            movieEp.episode_number = 1f
            movieEp.setUrlWithoutDomain(response.request.url.toString())
            episodeList.add(movieEp)
        } else if (seasons.any()) {
            val seasonEp = extractEpisodesFromSeasons(seasons)
            if (seasonEp.isNotEmpty()) episodeList.addAll(seasonEp)
        }
        return episodeList.reversed()
    }

    private fun extractEpisodesFromSeasons(seasons: Elements): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        var noEp = 1f
        var noTemp = 1
        seasons!!.forEach {
            var request = client.newCall(GET(it!!.attr("href"))).execute()
            if (request.isSuccessful) {
                val document = request.asJsoup()
                document.select("div.TPTblCn table tbody tr.Viewed")!!.forEach { epContainer ->
                    val urlEp = epContainer.selectFirst("td.MvTbPly > a")!!.attr("href")
                    val ep = SEpisode.create()
                    ep.name = "T$noTemp - Episodio $noEp"
                    ep.episode_number = noEp
                    ep.setUrlWithoutDomain(urlEp)
                    episodeList.add(ep)
                    noEp++
                }
            }
            noTemp++
        }
        return episodeList
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("div.TPost.A.D div.Container div.optns-bx div.drpdn button.bstd").forEach { serverList ->
            serverList.select("ul.optnslst li div[data-url]").forEach {
                val langTag = it.selectFirst("span:nth-child(2)")!!
                    .text().substringBefore("HD")
                    .substringBefore("SD")
                    .trim()
                val langVideo = if (langTag.contains("LATINO")) "LAT" else if (langTag.contains("CASTELLANO")) "CAST" else "SUB"
                val encryptedUrl = it.attr("data-url")
                val url = String(Base64.decode(encryptedUrl, Base64.DEFAULT))
                val nuploadDomains = arrayOf("nuuuppp", "nupload")
                if (nuploadDomains.any { x -> url.contains(x) } && !url.contains("/iframe/")) {
                    nuploadExtractor(langVideo, url).map { video -> videoList.add(video) }
                }
            }
        }
        return videoList
    }

    private fun nuploadExtractor(prefix: String, url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val request = client.newCall(GET(url)).execute()
        if (request.isSuccessful) {
            val document = request.asJsoup()
            document.select("script").forEach { script ->
                if (script!!.data().contains("var sesz=\"")) {
                    val key = script.data().substringAfter("var sesz=\"").substringBefore("\",")
                    var preUrl = script.data().substringAfter("file:\"").substringBefore("\"+")
                    var headersNupload = headers.newBuilder()
                        .set("authority", preUrl.substringAfter("https://").substringBefore("/"))
                        .set("accept-language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                        .set("referer", "https://nupload.co/")
                        .set("dnt", "1")
                        .set("range", "bytes=0-")
                        .set("sec-ch-ua", "\"Chromium\";v=\"104\", \" Not A;Brand\";v=\"99\", \"Google Chrome\";v=\"104\"")
                        .set("sec-fetch-dest", "video")
                        .set("sec-fetch-mode", "no-cors")
                        .set("sec-fetch-site", "cross-site")
                        .set("sec-gpc", "1")
                        .set("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.0.0 Safari/537.36")
                        .build()
                    videoList.add(Video(preUrl, "$prefix Nupload", preUrl + key, headers = headersNupload))
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query&page=$page")
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Peliculas", "peliculas-online"),
            Pair("Series", "series-online"),
            Pair("Estrenos", "genero/estrenos"),
            Pair("Aventura", "genero/aventura"),
            Pair("Acción", "genero/accion"),
            Pair("Ciencia ficción", "genero/ciencia-ficcion"),
            Pair("Comedia", "genero/comedia"),
            Pair("Crimen", "genero/crimen"),
            Pair("Drama", "genero/drama"),
            Pair("Romance", "genero/romance"),
            Pair("Terror", "genero/terror"),
        ),
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

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        var description = try {
            document.selectFirst("article.TPost header.Container div.TPMvCn div.Description")!!
                .text().removeSurrounding("\"")
                .substringAfter("Online:")
                .substringBefore("Recuerda ")
                .substringBefore("Director:")
                .trim()
        } catch (e: Exception) {
            document.selectFirst("article.TPost header div.TPMvCn div.Description p:nth-child(1)")!!.text().removeSurrounding("\"").trim()
        }

        var title = try {
            document.selectFirst("article.TPost header.Container div.TPMvCn h1.Title")!!.text().removePrefix("Serie").trim()
        } catch (e: Exception) {
            document.selectFirst("article.TPost header.Container div.TPMvCn a h1.Title")!!.text().removePrefix("Serie").trim()
        }

        anime.title = title
        anime.description = description
        anime.genre = document.select("article.TPost header.Container div.TPMvCn div.Description p.Genre a").joinToString { it.text().replace(",", "") }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    private fun externalOrInternalImg(url: String): String {
        return if (url.contains("https")) url else if (url.startsWith("//")) "https:$url" else "$baseUrl/$url"
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/browse?order=added&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("LAT Nupload", "CAST Nupload", "SUB Nupload")
            entryValues = arrayOf("LAT Nupload", "CAST Nupload", "SUB Nupload")
            setDefaultValue("LAT Nupload")
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
