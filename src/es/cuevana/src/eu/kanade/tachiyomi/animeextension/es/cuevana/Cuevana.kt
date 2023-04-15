package eu.kanade.tachiyomi.animeextension.es.cuevana

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
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
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
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
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
                val optLanguage = it.parent()!!.attr("id")
                val languageTag = document.selectFirst("li[data-tplayernv=$optLanguage]")!!.closest(".open_submenu")!!.selectFirst("div:first-child")!!.text()
                if (languageTag.lowercase().contains("latino")) {
                    "[LAT]"
                } else if (languageTag.lowercase().contains("españa")) {
                    "[CAST]"
                } else if (languageTag.lowercase().contains("subtitulado")) {
                    "[SUB]"
                } else {
                    ""
                }
            } catch (e: Exception) { "" }
            val regIsUrl = "https?:\\/\\/(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&//=]*)".toRegex()
            val iframe = urlServerSolver(it.attr("data-src"))
            if (iframe.contains("api.cuevana3.me/fembed/")) {
                val key = iframe.substringAfter("?h=")
                val headers = headers.newBuilder()
                    .add("authority", "api.cuevana3.me")
                    .add("accept", "application/json, text/javascript, */*; q=0.01")
                    .add("accept-language", "es-MX,es;q=0.9,en;q=0.8")
                    .add("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .add("origin", "https://api.cuevana3.me")
                    .add("sec-ch-ua", "\"Chromium\";v=\"112\", \"Google Chrome\";v=\"112\", \"Not:A-Brand\";v=\"99\"")
                    .add("sec-ch-ua-mobile", "?0")
                    .add("sec-ch-ua-platform", "\"Windows\"")
                    .add("sec-fetch-dest", "empty")
                    .add("sec-fetch-mode", "cors")
                    .add("sec-fetch-site", "same-origin")
                    .add("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36")
                    .add("x-requested-with", "XMLHttpRequest")
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
            if (iframe.contains("apialfa.tomatomatela.club")) {
                try {
                    val tomkey = iframe.substringAfter("?h=")
                    val clientGoTo = OkHttpClient().newBuilder().build()
                    val mediaType = "application/x-www-form-urlencoded".toMediaType()
                    val bodyGoTo = "url=$tomkey".toRequestBody(mediaType)
                    val requestGoTo = Request.Builder()
                        .url("https://apialfa.tomatomatela.club/ir/rd.php")
                        .method("POST", bodyGoTo)
                        .addHeader("Host", "apialfa.tomatomatela.club")
                        .addHeader(
                            "User-Agent",
                            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0",
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
                        .addHeader("Sec-Fetch-Mode", "navigate")
                        .addHeader("Sec-Fetch-Site", "same-origin")
                        .build()
                    val responseGoto = clientGoTo.newCall(requestGoTo).execute()
                    val locations = responseGoto!!.networkResponse.toString()
                    fetchUrls(locations).map {
                        if (!it.contains("ir/rd.php")) {
                            loadExtractor(it, langPrefix).map { video -> videoList.add(video) }
                        }
                    }
                } catch (e: Exception) { }
            }
            if (regIsUrl.containsMatchIn(iframe) &&
                !iframe.contains("api.cuevana3.me/fembed/") &&
                !iframe.contains("apialfa.tomatomatela.club")
            ) {
                try {
                    loadExtractor(iframe, langPrefix).map { video -> videoList.add(video) }
                } catch (e: Exception) { }
            }
        }
        return videoList
    }

    private fun loadExtractor(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
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
            val videos = FembedExtractor(client).videosFromUrl(url, prefix, redirect = true)
            videoList.addAll(videos)
        }
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
            } catch (e: Exception) { }
        }
        if (embedUrl.contains("yourupload")) {
            val videos = YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            videoList.addAll(videos)
        }
        if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
            DoodExtractor(client).videoFromUrl(url, "$prefix DoodStream", false)
                ?.let { videoList.add(it) }
        }
        if (embedUrl.contains("sbembed.com") || embedUrl.contains("sbembed1.com") || embedUrl.contains("sbplay.org") ||
            embedUrl.contains("sbvideo.net") || embedUrl.contains("streamsb.net") || embedUrl.contains("sbplay.one") ||
            embedUrl.contains("cloudemb.com") || embedUrl.contains("playersb.com") || embedUrl.contains("tubesb.com") ||
            embedUrl.contains("sbplay1.com") || embedUrl.contains("embedsb.com") || embedUrl.contains("watchsb.com") ||
            embedUrl.contains("sbplay2.com") || embedUrl.contains("japopav.tv") || embedUrl.contains("viewsb.com") ||
            embedUrl.contains("sbfast") || embedUrl.contains("sbfull.com") || embedUrl.contains("javplaya.com") ||
            embedUrl.contains("ssbstream.net") || embedUrl.contains("p1ayerjavseen.com") || embedUrl.contains("sbthe.com") ||
            embedUrl.contains("vidmovie.xyz") || embedUrl.contains("sbspeed.com") || embedUrl.contains("streamsss.net") ||
            embedUrl.contains("sblanh.com") || embedUrl.contains("sbbrisk.com")
        ) {
            runCatching {
                StreamSBExtractor(client).videosFromUrl(url, headers, prefix = prefix)
            }.getOrNull()?.let { videoList.addAll(it) }
        }
        if (embedUrl.contains("okru")) {
            videoList.addAll(
                OkruExtractor(client).videosFromUrl(url, prefix, true),
            )
        }
        if (embedUrl.contains("voe")) {
            VoeExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
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
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) },
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
        anime.title = document.selectFirst("#top-single div.backdrop article.TPost header .Title")!!.text()
        anime.thumbnail_url = document.selectFirst("#top-single div.backdrop article div.Image figure img")!!.attr("data-src")
        anime.description = document.selectFirst("#top-single div.backdrop article.TPost div.Description")!!.text().trim()
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
        GenreFilter(),
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
            Pair("Thriller", "thriller"),
        ),
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
            "StreamTape", "Amazon", "Voex", "DoodStream", "YourUpload",
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
