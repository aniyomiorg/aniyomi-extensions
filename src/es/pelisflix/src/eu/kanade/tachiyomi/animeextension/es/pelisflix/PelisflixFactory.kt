package eu.kanade.tachiyomi.animeextension.es.pelisflix

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PelisflixFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(PelisflixClass(), SeriesflixClass())
}

class PelisflixClass : Pelisflix("Pelisflix", "https://pelisflix2.green")

class SeriesflixClass : Pelisflix("Seriesflix", "https://seriesflix.video") {
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ver-series-online/page/$page")

    override fun popularAnimeSelector() = "li[id*=post-] > article"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h2.Title").text()
        anime.thumbnail_url = element.selectFirst("a div.Image figure.Objf img")!!.attr("src")
        anime.description = element.select("div.TPMvCn div.Description p:nth-child(1)").text().removeSurrounding("\"")
        return anime
    }

    private fun loadVideoSources(urlResponse: String, lang: String): List<Video> {
        val videoList = mutableListOf<Video>()
        fetchUrls(urlResponse).map { serverUrl ->
            Log.i("bruh url", serverUrl)
            if (serverUrl.contains("doodstream")) {
                val video = DoodExtractor(client).videoFromUrl(serverUrl.replace("https://doodstream.com", "https://dood.wf"), lang + "DoodStream", false)
                if (video != null) videoList.add(video)
            }
            if (serverUrl.contains("streamtape")) {
                val video = StreamTapeExtractor(client).videoFromUrl(serverUrl, lang + "StreamTape")
                if (video != null) videoList.add(video)
            }
        }
        return videoList
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("ul.ListOptions li").forEach { serverList ->
            val movieID = serverList.attr("data-id")
            val serverID = serverList.attr("data-key")
            val type = if (response.request.url.toString().contains("movies")) 1 else 2
            val url = "$baseUrl/?trembed=$serverID&trid=$movieID&trtype=$type"
            val langTag = serverList.selectFirst("p.AAIco-language")!!.text().substring(3).uppercase()

            val lang = if (langTag.contains("LATINO")) "[LAT]" else if (langTag.contains("CASTELLANO")) "[CAST]" else "[SUB]"
            var request = client.newCall(GET(url)).execute()
            if (request.isSuccessful) {
                val serverLinks = request.asJsoup()
                serverLinks.select("div.Video iframe").map {
                    val iframe = it.attr("src")
                    if (iframe.contains("https://sc.seriesflix.video/index.php")) {
                        val postKey = iframe.replace("https://sc.seriesflix.video/index.php?h=", "")
                        val mediaType = "application/x-www-form-urlencoded".toMediaType()
                        val body: RequestBody = "h=$postKey".toRequestBody(mediaType)
                        val newClient = OkHttpClient().newBuilder().build()
                        val requestServer = Request.Builder()
                            .url("https://sc.seriesflix.video/r.php?h=$postKey")
                            .method("POST", body)
                            .addHeader("Host", "sc.seriesflix.video")
                            .addHeader(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                            )
                            .addHeader(
                                "Accept",
                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                            )
                            .addHeader("Accept-Language", "en-US,en;q=0.5")
                            .addHeader("Content-Type", "application/x-www-form-urlencoded")
                            .addHeader("Origin", "null")
                            .addHeader("DNT", "1")
                            .addHeader("Connection", "keep-alive")
                            .addHeader("Upgrade-Insecure-Requests", "1")
                            .addHeader("Sec-Fetch-Dest", "iframe")
                            .addHeader("Sec-Fetch-Mode", "no-cors")
                            .addHeader("sec-fetch-site", "same-origin")
                            .addHeader("Sec-Fetch-User", "?1")
                            .addHeader("Alt-Used", "sc.seriesflix.video")
                            .addHeader("Access-Control-Allow-Methods", "POST")
                            .build()
                        val document = newClient.newCall(requestServer).execute()
                        val urlResponse = document!!.networkResponse!!.toString()

                        loadVideoSources(urlResponse, lang)!!.forEach { source ->
                            videoList.add(source)
                        }
                    } else {
                        loadVideoSources(iframe, lang)
                    }
                }
            }
        }
        return videoList
    }

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
            Pair("Acción", "genero/accion"),
            Pair("Animación", "genero/animacion"),
            Pair("Anime", "genero/anime"),
            Pair("Antiguas", "genero/series-antiguas"),
            Pair("Aventura", "genero/aventura"),
            Pair("Ciencia ficción", "genero/ciencia-ficcion"),
            Pair("Comedia", "genero/comedia"),
            Pair("Crimen", "genero/crimen"),
            Pair("DC Comics", "genero/dc-comics"),
            Pair("Drama", "genero/drama"),
            Pair("Dorama", "genero/dorama"),
            Pair("Estrenos", "genero/estrenos"),
            Pair("Fantasía", "genero/fantasia"),
            Pair("Misterio", "genero/misterio"),
            Pair("Romance", "genero/romance"),
            Pair("Terror", "genero/terror"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) },
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "[LAT]DoodStream")
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "[LAT]DoodStream",
            "[CAST]DoodStream",
            "[SUB]DoodStream",
            "[LAT]StreamTape",
            "[CAST]StreamTape",
            "[SUB]StreamTape", // video servers without resolution
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("[LAT]DoodStream")
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
