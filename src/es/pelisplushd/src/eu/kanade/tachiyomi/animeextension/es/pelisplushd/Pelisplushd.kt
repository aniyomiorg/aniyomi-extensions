package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Pelisplushd : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Pelisplushd"

    override val baseUrl = "https://ww1.pelisplushd.nu"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.Posters a.Posters-link"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("a").attr("href")
        )
        anime.title = element.select("a div.listing-content p").text()
        anime.thumbnail_url = element.select("a img").attr("src").replace("/w154/", "/w200/")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                val epnum = 1
                episode_number = epnum.toFloat()
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        } else {
            jsoup.select("div.tab-content div a").forEachIndexed { index, element ->
                val epNum = index + 1
                val episode = SEpisode.create()
                episode.episode_number = epNum.toFloat()
                episode.name = element.text()
                episode.setUrlWithoutDomain(element.attr("href"))
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val data = document.selectFirst("script:containsData(video[1] = )").data()
        val apiUrl = data.substringAfter("video[1] = '", "").substringBefore("';", "")
        val alternativeServers = document.select("ul.TbVideoNv.nav.nav-tabs li:not(:first-child)")
        if (apiUrl.isNotEmpty()) {
            val apiResponse = client.newCall(GET(apiUrl)).execute().asJsoup()
            var encryptedList = apiResponse!!.select("#PlayerDisplay div[class*=\"OptionsLangDisp\"] div[class*=\"ODDIV\"] div[class*=\"OD\"] li[data-r]")
            var decryptedList = apiResponse!!.select("#PlayerDisplay div[class*=\"OptionsLangDisp\"] div[class*=\"ODDIV\"] div[class*=\"OD\"] li:not([data-r])")
            encryptedList.forEach {
                val url = String(Base64.decode(it.attr("data-r"), Base64.DEFAULT))
                val server = it.select("span").text()
                serverVideoResolver(url, server.toString())?.forEach { video -> videoList.add(video) }
            }
            decryptedList.forEach {
                val url = it.attr("onclick")
                    .substringAfter("go_to_player('")
                    .substringBefore("?cover_url=")
                    .substringBefore("')")
                    .substringBefore("?poster")
                    .substringBefore("#poster=")
                val server = it.select("span").text()
                serverVideoResolver(url, server.toString())?.forEach { video -> videoList.add(video) }
            }
        }

        // verifier for old series
        if (!apiUrl.contains("/video/") || alternativeServers.any()) {
            document.select("ul.TbVideoNv.nav.nav-tabs li").forEach { id ->
                val serverName = id.select("a").text()
                val serverId = id.attr("data-id")
                var serverUrl = data.substringAfter("video[$serverId] = '", "").substringBefore("';", "")
                if (serverUrl.contains("api.mycdn.moe")) {
                    val urlId = serverUrl.substringAfter("id=")
                    when (serverName.lowercase()) {
                        "sbfast" -> { serverUrl = "https://sbfull.com/e/$urlId" }
                        "plusto" -> { serverUrl = "https://owodeuwu.xyz/v/$urlId" }
                        "doodstream" -> { serverUrl = "https://dood.to/e/$urlId" }
                        "upload" -> { serverUrl = "https://uqload.com/embed-$urlId.html" }
                    }
                }
                serverVideoResolver(serverUrl, serverName.toString())?.forEach { video -> videoList.add(video) }
            }
        }
        return videoList
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexArray = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun fixUrl(url: String): String {
        val sbUrl = url.substringBefore("/e/")
        val id = url.substringAfter("/e/").substringBefore("?").substringBefore(".html")
        val hexBytes = bytesToHex(id.toByteArray())
        return "$sbUrl/sources49/316751675854774a53316b777c7c${hexBytes}7c7c744548764f5a666a315547577c7c73747265616d7362"
    }

    private fun serverVideoResolver(url: String, server: String): List<Video>? {
        val videoList = mutableListOf<Video>()
        try {
            if (server.lowercase() == "sbfast") {
                val newHeaders = headers.newBuilder().set("referer", url).set("watchsb", "sbstream").set("authority", url.substringBefore("/e/")).build()
                return StreamSBExtractor(client).videosFromDecryptedUrl(fixUrl(url), headers = newHeaders)
            } else if (server.lowercase() == "plusto") {
                return FembedExtractor(client).videosFromUrl(url)
            } else if (server.lowercase() == "stp") {
                StreamTapeExtractor(client).videoFromUrl(url, "StreamTape")?.let { videoList.add(it) }
            } else if (server.lowercase() == "uwu") {
                if (!url.contains("disable")) {
                    val body = client.newCall(GET(url)).execute().asJsoup()
                    if (body.select("script:containsData(var shareId)").toString()
                        .isNotBlank()
                    ) {
                        val shareId =
                            body.selectFirst("script:containsData(var shareId)").data()
                                .substringAfter("shareId = \"").substringBefore("\"")
                        val amazonApiJson =
                            client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
                                .execute().asJsoup()
                        val epId = amazonApiJson.toString().substringAfter("\"id\":\"")
                            .substringBefore("\"")
                        val amazonApi =
                            client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
                                .execute().asJsoup()
                        val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":")
                            .substringAfter("tempLink\":\"").substringBefore("\"")
                        videoList.add(Video(videoUrl, "Amazon", videoUrl))
                    }
                }
            } else if (server.lowercase() == "voex") {
                VoeExtractor(client).videoFromUrl(url, "Voex")?.let { videoList.add(it) }
            } else if (server.lowercase() == "streamlare") {
                StreamlareExtractor(client).videosFromUrl(url)?.let { videoList.add(it) }
            } else if (server.lowercase() == "doodstream") {
                val url2 = url.replace("https://doodstream.com/e/", "https://dood.to/e/")
                DoodExtractor(client).videoFromUrl(url2, "DoodStream", false)?.let { videoList.add(it) }
            } else if (server.lowercase() == "upload") {
                val headers = headers.newBuilder().add("referer", "https://www.yourupload.com/").build()
                return YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            }
        } catch (_: Exception) {}
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
            val userPreferredQuality = preferences.getString("preferred_quality", "Voex")
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
        val tagFilter = filters.find { it is Tags } as Tags

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            tagFilter.state.isNotBlank() -> GET("$baseUrl/year/${tagFilter.state}?page=$page")
            else -> GET("$baseUrl/peliculas?page=$page")
        }
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.m-b-5").text()
        anime.thumbnail_url = document.selectFirst("div.card-body div.row div.col-sm-3 img.img-fluid")
            .attr("src").replace("/w154/", "/w500/")
        anime.description = document.selectFirst("div.col-sm-4 div.text-large").ownText()
        anime.genre = document.select("div.p-v-20.p-h-15.text-center a span").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro de año"),
        GenreFilter(),
        AnimeFilter.Header("Busqueda por año"),
        Tags("Año")
    )

    private class GenreFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "generos/dorama"),
            Pair("Animes", "animes"),
            Pair("Acción", "generos/accion"),
            Pair("Animación", "generos/animacion"),
            Pair("Aventura", "generos/aventura"),
            Pair("Ciencia Ficción", "generos/ciencia-ficcion"),
            Pair("Comedia", "generos/comedia"),
            Pair("Crimen", "generos/crimen"),
            Pair("Documental", "generos/documental"),
            Pair("Drama", "generos/drama"),
            Pair("Fantasía", "generos/fantasia"),
            Pair("Foreign", "generos/foreign"),
            Pair("Guerra", "generos/guerra"),
            Pair("Historia", "generos/historia"),
            Pair("Misterio", "generos/misterio"),
            Pair("Pelicula de Televisión", "generos/pelicula-de-la-television"),
            Pair("Romance", "generos/romance"),
            Pair("Suspense", "generos/suspense"),
            Pair("Terror", "generos/terror"),
            Pair("Western", "generos/western")
        )
    )

    private class Tags(name: String) : AnimeFilter.Text(name)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
            "Streamlare:1080p", "Streamlare:720p", "Streamlare:480p", "Streamlare:360p", "Streamlare:240p", // Streamlare
            "StreamTape", "Amazon", "Voex", "DoodStream", "YourUpload"
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
