package eu.kanade.tachiyomi.animeextension.es.cuevana

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.cuevana.extractors.YourUploadExtractor
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class Cuevana : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Cuevana"

    override val baseUrl = "https://n2.cuevana3.me"

    override val lang = "es"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "section li.xxx.TPostMv div.TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        anime.title = element.select("a .Title").text()
        anime.thumbnail_url = element.select("a .Image figure.Objf img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "nav.navigation > div.nav-links > a.next.page-numbers"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        if (response.request.url.toString().contains("/serie/")) {
            document.select("[id*=season-]").mapIndexed { idxSeason, season ->
                val noSeason = try {
                    season.attr("id").substringAfter("season-").toInt()
                } catch (e: Exception) {
                    idxSeason
                }
                season.select(".TPostMv article.TPost").mapIndexed { idxCap, cap ->
                    val epNum = try { cap.select("a div.Image span.Year").text().substringAfter("x").toFloat() } catch (e: Exception) { idxCap.toFloat() }
                    val episode = SEpisode.create()
                    val date = cap.select("a > p").text()
                    val epDate = try { SimpleDateFormat("yyyy-MM-dd").parse(date) } catch (e: Exception) { null }
                    episode.episode_number = epNum
                    episode.name = "T$noSeason - Episodio $epNum"
                    if (epDate != null) episode.date_upload = epDate.time
                    episode.setUrlWithoutDomain(cap.select("a").attr("href"))
                    episodes.add(episode)
                }
            }
        } else {
            val episode = SEpisode.create().apply {
                val epnum = 1
                episode_number = epnum.toFloat()
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("div.TPlayer.embed_div iframe").map {
            val langPrefix = try {
                val optLanguage = it.parent().attr("id")
                val languageTag = document.selectFirst("li[data-tplayernv=$optLanguage]").closest(".open_submenu").selectFirst("div:first-child").text()
                if (languageTag.lowercase().contains("latino"))
                    "[LAT]"
                else if (languageTag.lowercase().contains("españa"))
                    "[CAST]"
                else if (languageTag.lowercase().contains("subtitulado"))
                    "[SUB]"
                else
                    ""
            } catch (e: Exception) { "" }
            val iframe = urlServerSolver(it.attr("data-src"))
            if (iframe.contains("api.cuevana3.me/fembed/")) {
                val femRegex = Regex("(https.\\/\\/api\\.cuevana3\\.me\\/fembed\\/\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                femRegex.findAll(iframe).map { femreg -> femreg.value }.toList().map { fem ->
                    val key = fem.replace("https://api.cuevana3.me/fembed/?h=", "")
                    val headers = headers.newBuilder()
                        .set("Host", "api.cuevana3.me")
                        .set("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                        .set("Accept", "application/json, text/javascript, */*; q=0.01")
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .set("X-Requested-With", "XMLHttpRequest")
                        .set("Origin", "https://api.cuevana3.me")
                        .set("DNT", "1")
                        .set("Connection", "keep-alive")
                        .set("Sec-Fetch-Dest", "empty")
                        .set("Sec-Fetch-Mode", "cors")
                        .set("Sec-Fetch-Site", "same-origin")
                        .build()
                    val mediaType = "application/x-www-form-urlencoded".toMediaType()
                    val requestBody = "h=$key".toRequestBody(mediaType)
                    val jsonData = client.newCall(POST("https://api.cuevana3.me/fembed/api.php", headers = headers, requestBody)).execute()
                    if (jsonData.isSuccessful) {
                        val body = jsonData.asJsoup().body().toString()
                        val url = body.substringAfter("\"url\":\"").substringBefore("\",").replace("\\", "")
                        loadExtractor(url, langPrefix).map { video -> videoList.add(video) }
                    }
                }
            }
            if (iframe.contains("tomatomatela")) {
                try {
                    val tomatoRegex = Regex("(\\/\\/apialfa.tomatomatela.com\\/ir\\/player.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                    tomatoRegex.findAll(iframe).map { tomreg -> tomreg.value }.toList().map { tom ->
                        val tomkey = tom.replace("//apialfa.tomatomatela.com/ir/player.php?h=", "")
                        val clientGoTo = OkHttpClient().newBuilder().build()
                        val mediaType = "application/x-www-form-urlencoded".toMediaType()
                        val bodyGoTo = "url=$tomkey".toRequestBody(mediaType)
                        val requestGoTo = Request.Builder()
                            .url("https://apialfa.tomatomatela.com/ir/rd.php")
                            .method("POST", bodyGoTo)
                            .addHeader("Host", "apialfa.tomatomatela.com")
                            .addHeader("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                            .addHeader("Accept-Language", "en-US,en;q=0.5")
                            .addHeader("Content-Type", "application/x-www-form-urlencoded")
                            .addHeader("Origin", "null")
                            .addHeader("DNT", "1")
                            .addHeader("Connection", "keep-alive")
                            .addHeader("Upgrade-Insecure-Requests", "1")
                            .addHeader("Sec-Fetch-Dest", "iframe")
                            .addHeader("Sec-Fetch-Mode", "navigate")
                            .addHeader("Sec-Fetch-Site", "same-origin")
                            .build()
                        val responseGoto = clientGoTo.newCall(requestGoTo).execute()
                        val locations = responseGoto!!.networkResponse.toString()
                        fetchUrls(locations).map { loc ->
                            if (loc.contains("goto_ddh.php")) {
                                val goToRegex = Regex("(\\/\\/api.cuevana3.me\\/ir\\/goto_ddh.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                                goToRegex.findAll(loc).map { goreg ->
                                    goreg.value.replace("//api.cuevana3.me/ir/goto_ddh.php?h=", "")
                                }.toList().map { gotolink ->
                                    // https://github.com/Jacekun/CloudStream-3XXX/blob/javdev/app/src/main/java/com/lagradost/cloudstream3/movieproviders/CuevanaProvider.kt
                                    val clientDdh = OkHttpClient().newBuilder().build()
                                    val mediaType = "application/x-www-form-urlencoded".toMediaType()
                                    val bodyDdh = "url=$gotolink".toRequestBody(mediaType)
                                    val requestDdh = Request.Builder()
                                        .url("https://api.cuevana3.me/ir/redirect_ddh.php")
                                        .method("POST", bodyDdh)
                                        .addHeader("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                                        .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                                        .addHeader("Accept-Language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                                        .addHeader("cache-control", "max-age=0")
                                        .addHeader("Content-Type", "application/x-www-form-urlencoded")
                                        .addHeader("Origin", "null")
                                        .addHeader("DNT", "1")
                                        .addHeader("Connection", "keep-alive")
                                        .addHeader("Upgrade-Insecure-Requests", "1")
                                        .addHeader("Sec-Fetch-Dest", "document")
                                        .addHeader("Sec-Fetch-Mode", "navigate")
                                        .addHeader("Sec-Fetch-Site", "same-origin")
                                        .addHeader("sec-fetch-user", "?1")
                                        .addHeader("sec-ch-ua", "\"Chromium\";v=\"106\", \"Google Chrome\";v=\"106\", \"Not;A=Brand\";v=\"99\"")
                                        .addHeader("sec-ch-ua-mobile", "?0")
                                        .addHeader("sec-ch-ua-platform", "\"Windows\"")
                                        .addHeader("authority", "api.cuevana3.me")
                                        .build()
                                    val responseDdh = clientDdh.newCall(requestDdh).execute()
                                    val locationsDdh = responseDdh!!.networkResponse.toString()
                                    fetchUrls(locationsDdh).map { golink ->
                                        loadExtractor(golink, langPrefix).map { video -> videoList.add(video) }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        return videoList
    }

    private fun loadExtractor(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val normalizeUrl = url.lowercase()
        if (normalizeUrl.contains("fembed")) {
            val videos = FembedExtractor(client).videosFromUrl(url, prefix)
            videoList.addAll(videos)
        }
        if (normalizeUrl.contains("tomatomatela")) {
            try {
                var mainUrl = url.substringBefore("/embed.html#").substringAfter("https://")
                var headers = headers.newBuilder()
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
                var bodyText = response.select("body").text()
                var json = json.decodeFromString<JsonObject>(bodyText)
                var status = json["status"]!!.jsonPrimitive!!.content
                var file = json["file"]!!.jsonPrimitive!!.content
                if (status == "200") { videoList.add(Video(file, "$prefix Tomatomatela", file, headers = null)) }
            } catch (e: Exception) { }
        }
        if (normalizeUrl.contains("yourupload")) {
            val headers = headers.newBuilder().add("referer", "https://www.yourupload.com/").build()
            val videos = YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            videos.map { videoList.add(it) }
        }
        if (normalizeUrl.contains("doodstream") || normalizeUrl.contains("dood.")) {
            DoodExtractor(client).videoFromUrl(url, "$prefix DoodStream", false)
        }
        if (normalizeUrl.contains("sbstream")) {
            runCatching {
                StreamSBExtractor(client).videosFromUrl(url, headers, prefix = prefix)
            }.getOrNull()?.let { videoList.addAll(it) }
        }
        if (normalizeUrl.contains("okru")) {
            OkruExtractor(client).videosFromUrl(url, prefix, true)
        }
        return videoList
    }

    private fun urlServerSolver(url: String): String = if (url.startsWith("https")) url else if (url.startsWith("//")) "https:$url" else "$baseUrl/$url"

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = Regex("""(https?://(www\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))""")
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
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

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page?s=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/category/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("#top-single div.backdrop article.TPost header .Title").text()
        anime.thumbnail_url = document.selectFirst("#top-single div.backdrop article div.Image figure img").attr("data-src")
        anime.description = document.selectFirst("#top-single div.backdrop article.TPost div.Description").text().trim()
        anime.genre = document.select("#MvTb-Info ul.InfoList li:nth-child(2) > a").joinToString { it.text() }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Acción", "accion"),
            Pair("Animación", "animacion"),
            Pair("Aventura", "aventura"),
            Pair("Bélico Guerra", "belico-guerra"),
            Pair("Biográfia", "biografia"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Crimen", "crimen"),
            Pair("Documentales", "documentales"),
            Pair("Drama", "drama"),
            Pair("Familiar", "familiar"),
            Pair("Fantasía", "fantasia"),
            Pair("Misterio", "misterio"),
            Pair("Musical", "musical"),
            Pair("Romance", "romance"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller")
        )
    )

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
