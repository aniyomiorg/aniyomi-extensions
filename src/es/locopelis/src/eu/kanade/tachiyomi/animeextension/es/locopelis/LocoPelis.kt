package eu.kanade.tachiyomi.animeextension.es.locopelis

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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import kotlin.Exception

class LocoPelis : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "LocoPelis"

    override val baseUrl = "https://www.locopelis.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "ul.peliculas li.peli_bx"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/pelicula/peliculas-mas-vistas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.peli_img div.peli_img_img a").attr("href"))
        anime.title = element.select("h2.titpeli").text()
        anime.thumbnail_url = element.select("div.peli_img div.peli_img_img a img").attr("src")
        anime.description = element.select("div.peli_img div.peli_txt p").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "#cn div ul.nav li ~ li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val existVideos = document.select(".tab_container .tab_content iframe").any()
        val parser = SimpleDateFormat("yyyy-MM-dd")
        if (existVideos) {
            val ep = SEpisode.create()
            ep.setUrlWithoutDomain(response.request.url.toString())
            ep.name = "PELÍCULA"
            ep.episode_number = 1f
            document.select("div.content div.details ul.dtalist li").map {
                if (it.text().contains("Publicado:")) {
                    try { ep.date_upload = parser.parse(it.text().replace("Publicado:", "").trim()).time } catch (_: Exception) { }
                }
            }
            episodeList.add(ep)
        }
        return episodeList
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(".tab_container .tab_content iframe").forEach { iframe ->
            val url = iframe.attr("src")
            val embedUrl = url.lowercase()
            if (embedUrl.contains("sbembed.com") || embedUrl.contains("sbembed1.com") || embedUrl.contains("sbplay.org") ||
                embedUrl.contains("sbvideo.net") || embedUrl.contains("streamsb.net") || embedUrl.contains("sbplay.one") ||
                embedUrl.contains("cloudemb.com") || embedUrl.contains("playersb.com") || embedUrl.contains("tubesb.com") ||
                embedUrl.contains("sbplay1.com") || embedUrl.contains("embedsb.com") || embedUrl.contains("watchsb.com") ||
                embedUrl.contains("sbplay2.com") || embedUrl.contains("japopav.tv") || embedUrl.contains("viewsb.com") ||
                embedUrl.contains("sbfast") || embedUrl.contains("sbfull.com") || embedUrl.contains("javplaya.com") ||
                embedUrl.contains("ssbstream.net") || embedUrl.contains("p1ayerjavseen.com") || embedUrl.contains("sbthe.com") ||
                embedUrl.contains("vidmovie.xyz") || embedUrl.contains("sbspeed.com") || embedUrl.contains("streamsss.net") ||
                embedUrl.contains("sblanh.com")
            ) {
                val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
                videoList.addAll(videos)
            }
            if (embedUrl.contains("fembed") || embedUrl.contains("anime789.com") || embedUrl.contains("24hd.club") ||
                embedUrl.contains("fembad.org") || embedUrl.contains("vcdn.io") || embedUrl.contains("sharinglink.club") ||
                embedUrl.contains("moviemaniac.org") || embedUrl.contains("votrefiles.club") || embedUrl.contains("femoload.xyz") ||
                embedUrl.contains("albavido.xyz") || embedUrl.contains("feurl.com") || embedUrl.contains("dailyplanet.pw") ||
                embedUrl.contains("ncdnstm.com") || embedUrl.contains("jplayer.net") || embedUrl.contains("xstreamcdn.com") ||
                embedUrl.contains("fembed-hd.com") || embedUrl.contains("gcloud.live") || embedUrl.contains("vcdnplay.com") ||
                embedUrl.contains("superplayxyz.club") || embedUrl.contains("vidohd.com") || embedUrl.contains("vidsource.me") ||
                embedUrl.contains("cinegrabber.com") || embedUrl.contains("votrefile.xyz") || embedUrl.contains("zidiplay.com") ||
                embedUrl.contains("ndrama.xyz") || embedUrl.contains("fcdn.stream") || embedUrl.contains("mediashore.org") ||
                embedUrl.contains("suzihaza.com") || embedUrl.contains("there.to") || embedUrl.contains("femax20.com") ||
                embedUrl.contains("javstream.top") || embedUrl.contains("viplayer.cc") || embedUrl.contains("sexhd.co") ||
                embedUrl.contains("fembed.net") || embedUrl.contains("mrdhan.com") || embedUrl.contains("votrefilms.xyz") ||
                embedUrl.contains("embedsito.com") || embedUrl.contains("dutrag.com") || embedUrl.contains("youvideos.ru") ||
                embedUrl.contains("streamm4u.club") || embedUrl.contains("moviepl.xyz") || embedUrl.contains("asianclub.tv") ||
                embedUrl.contains("vidcloud.fun") || embedUrl.contains("fplayer.info") || embedUrl.contains("diasfem.com") ||
                embedUrl.contains("javpoll.com") || embedUrl.contains("reeoov.tube") || embedUrl.contains("suzihaza.com") ||
                embedUrl.contains("ezsubz.com") || embedUrl.contains("vidsrc.xyz") || embedUrl.contains("diampokusy.com") ||
                embedUrl.contains("diampokusy.com") || embedUrl.contains("i18n.pw") || embedUrl.contains("vanfem.com") ||
                embedUrl.contains("fembed9hd.com") || embedUrl.contains("votrefilms.xyz") || embedUrl.contains("watchjavnow.xyz")
            ) {
                val videos = FembedExtractor(client).videosFromUrl(url, redirect = true)
                videoList.addAll(videos)
            }
            if (url.lowercase().contains("streamtape")) {
                val video = StreamTapeExtractor(client).videoFromUrl(url, "Streamtape")
                if (video != null) {
                    videoList.add(video)
                }
            }
            if (url.lowercase().contains("doodstream") || url.lowercase().contains("dood")) {
                val video = try {
                    DoodExtractor(client).videoFromUrl(url, "DoodStream", true)
                } catch (e: Exception) {
                    null
                }
                if (video != null) {
                    videoList.add(video)
                }
            }
            if (url.lowercase().contains("okru")) {
                val videos = OkruExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
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
            val userPreferredQuality = preferences.getString("preferred_quality", "DoodStream")
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
            query.isNotBlank() -> GET("$baseUrl/buscar/?q=$query&page=$page")
            genreFilter.state != 0 && !genreFilter.toUriPart().contains("pelicula/") -> GET("$baseUrl/categoria/${genreFilter.toUriPart()}/?page=$page")
            genreFilter.state != 0 && genreFilter.toUriPart().contains("pelicula/") -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
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
            Pair("Últ. agregadas", "pelicula/ultimas-peliculas"),
            Pair("Últ. descargas", "pelicula/ultimas-descargas"),
            Pair("Más votadas", "pelicula/peliculas-mas-votadas"),
            Pair("Más visitadas", "pelicula/peliculas-mas-vistas"),
            Pair("Accion", "accion"),
            Pair("Adolescente", "adolescente"),
            Pair("Animacion e Infantil", "animacion-e-infantil"),
            Pair("Anime", "anime"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Asiaticas", "asiaticas"),
            Pair("Aventura", "aventura"),
            Pair("Bélico (Guerra)", "belico"),
            Pair("Ciencia Ficcion", "ciencia-ficcion"),
            Pair("Cine negro", "cine-negro"),
            Pair("Comedia", "comedia"),
            Pair("Deporte", "deporte"),
            Pair("Documentales", "documentales"),
            Pair("Drama", "drama"),
            Pair("Eroticas +18", "eroticas"),
            Pair("Fantasia", "fantasia"),
            Pair("Hindu", "hindu"),
            Pair("Intriga", "intriga"),
            Pair("Musical", "musical"),
            Pair("Religiosas", "religiosas"),
            Pair("Romance", "romance"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Western", "western"),
            Pair("Zombies", "zombies")
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

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = externalOrInternalImg(document.selectFirst("div.intsd div.peli_img_int img").attr("src"))
        anime.description = document.selectFirst("div.content span div.sinoptxt strong").text().removeSurrounding("\"")
        document.select("div.content div.details ul.dtalist li").map {
            val textContent = it.text()
            val tempContent = textContent.lowercase()
            if (tempContent.contains("titulo latino")) anime.title = textContent.replace("Titulo Latino:", "").trim()
            if (tempContent.contains("genero")) anime.genre = textContent.replace("Genero:", "").trim()
        }
        anime.status = parseStatus("Finalizado")
        return anime
    }

    private fun externalOrInternalImg(url: String): String {
        return if (url.contains("https")) url else "$baseUrl/$url"
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

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/pelicula/ultimas-peliculas?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf(
                "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
                "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
                "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
                "YourUpload", "DoodStream", "StreamTape"
            ) // video servers without resolution
            entryValues = arrayOf(
                "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
                "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
                "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
                "DoodStream", "StreamTape"
            ) // video servers without resolution
            setDefaultValue("DoodStream")
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
