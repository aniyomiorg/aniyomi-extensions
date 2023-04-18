package eu.kanade.tachiyomi.animeextension.es.gnula

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class Gnula : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Gnula"

    override val baseUrl = "https://gnula.life"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val langValues = arrayOf("latino", "spanish", "english", "")

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/archives/movies/releases/page/$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.selectFirst("ul.pagination > li.page-item.active ~ li > a > span.visually-hidden")?.text()?.contains("Next") ?: false
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val results = pageProps["results"]!!.jsonObject
                val data = results["data"]!!.jsonArray
                data.forEach { item ->
                    val animeItem = item!!.jsonObject
                    val anime = SAnime.create()
                    val slug = animeItem["slug"]!!.jsonObject["name"]!!.jsonPrimitive!!.content
                    val type = animeItem["url"]?.jsonObject?.get("slug")?.jsonPrimitive?.content ?: response.request.url.toString()

                    anime.title = animeItem["titles"]!!.jsonObject["name"]!!.jsonPrimitive!!.content
                    anime.thumbnail_url = animeItem["images"]!!.jsonObject["poster"]!!.jsonPrimitive!!.content
                    anime.setUrlWithoutDomain(if (type.contains("movies") || type.contains("genres")) "$baseUrl/movies/$slug" else "$baseUrl/series/$slug")
                    animeList.add(anime)
                }
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    @SuppressLint("SimpleDateFormat")
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        if (response.request.url.toString().contains("/movies/")) {
            val episode = SEpisode.create()
            episode.episode_number = 1F
            episode.name = "Película"
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodeList.add(episode)
        } else {
            document.select("script").forEach { script ->
                if (script.data().contains("{\"props\":{\"pageProps\"")) {
                    val jObject = json.decodeFromString<JsonObject>(script.data())
                    val props = jObject["props"]!!.jsonObject
                    val pageProps = props["pageProps"]!!.jsonObject
                    val post = pageProps["post"]!!.jsonObject
                    val slug = post["slug"]!!.jsonObject["name"]!!.jsonPrimitive!!.content
                    val seasons = post["seasons"]!!.jsonArray
                    var realNoEpisode = 0F
                    seasons!!.forEach { it ->
                        val season = it!!.jsonObject
                        val seasonNumber = season["number"]!!.jsonPrimitive!!.content
                        season["episodes"]!!.jsonArray!!.map {
                            realNoEpisode += 1
                            val noEp = it.jsonObject["number"]!!.jsonPrimitive!!.content
                            val episode = SEpisode.create()
                            episode.name = "T$seasonNumber - E$noEp - Capítulo $noEp"
                            episode.episode_number = realNoEpisode
                            episode.setUrlWithoutDomain("$baseUrl/series/$slug/seasons/$seasonNumber/episodes/$noEp")
                            val date = it!!.jsonObject["releaseDate"]!!.jsonPrimitive!!.content!!.substringBefore("T")
                            val epDate = try { SimpleDateFormat("yyyy-MM-dd").parse(date) } catch (e: Exception) { null }
                            if (epDate != null) episode.date_upload = epDate.time
                            episodeList.add(episode)
                        }
                    }
                }
            }
        }
        return episodeList
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val langSelect = preferences.getString("preferred_lang", "latino").toString()

        if (response.request.url.toString().contains("/movies/")) {
            document.select("script").forEach { script ->
                if (script.data().contains("{\"props\":{\"pageProps\"")) {
                    val jObject = json.decodeFromString<JsonObject>(script.data())
                    val props = jObject["props"]!!.jsonObject
                    val pageProps = props["pageProps"]!!.jsonObject
                    val post = pageProps["post"]!!.jsonObject
                    var players = if (langSelect != "") {
                        post["players"]!!.jsonObject.entries.filter { x -> x.key == langSelect && x.value.jsonArray.any() }.toList()
                    } else {
                        post["players"]!!.jsonObject.entries.toList()
                    }

                    if (!players.any()) {
                        langValues.filter { x -> x != langSelect }.forEach { tmpLang ->
                            if (!players.any()) {
                                players = if (langSelect != "") {
                                    post["players"]!!.jsonObject.entries.filter { x -> x.key == tmpLang && x.value.jsonArray.any() }.toList()
                                } else {
                                    post["players"]!!.jsonObject.entries.toList()
                                }
                            }
                        }
                    }

                    players.map {
                        val lang = it.key.split(" ").joinToString(separator = " ", transform = String::capitalize)
                        it.value!!.jsonArray!!.map {
                            val server = it!!.jsonObject["result"]!!.jsonPrimitive!!.content
                            var url = ""
                            client.newCall(GET(server)).execute()!!.asJsoup()!!.select("script")!!.map { sc ->
                                if (sc.data().contains("var url = '")) {
                                    url = sc.data().substringAfter("var url = '").substringBefore("';")
                                }
                            }
                            loadExtractor(url, lang).let { videos -> videoList.addAll(videos) }
                        }
                    }
                }
            }
        } else {
            document.select("script").forEach { script ->
                if (script.data().contains("{\"props\":{\"pageProps\"")) {
                    val jObject = json.decodeFromString<JsonObject>(script.data())
                    val props = jObject["props"]!!.jsonObject
                    val pageProps = props["pageProps"]!!.jsonObject
                    val episode = pageProps["episode"]!!.jsonObject
                    val players = episode["players"]!!.jsonObject.entries.filter { x -> x.key == langSelect }
                        .ifEmpty { episode["players"]!!.jsonObject.entries }
                    players.map {
                        val lang = it.key.split(" ").joinToString(separator = " ", transform = String::capitalize)
                        it.value!!.jsonArray!!.map {
                            val server = it!!.jsonObject["result"]!!.jsonPrimitive!!.content
                            var url = ""
                            client.newCall(GET(server)).execute()!!.asJsoup()!!.select("script")!!.map { sc ->
                                if (sc.data().contains("var url = '")) {
                                    url = sc.data().substringAfter("var url = '").substringBefore("';")
                                }
                            }
                            loadExtractor(url, lang).let { videos -> videoList.addAll(videos) }
                        }
                    }
                }
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
            DoodExtractor(client).videoFromUrl(url, "$prefix DoodStream", false)?.let { videoList.add(it) }
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
            VoeExtractor(client).videoFromUrl(url, quality = "$prefix Voex")?.let { videoList.add(it) }
        }
        if (embedUrl.contains("streamtape")) {
            StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape")?.let { videoList.add(it) }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) },
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "StreamSB:1080p")
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
            query.isNotBlank() -> GET("$baseUrl/search?q=$query&p=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\"")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val post = pageProps["post"]!!.jsonObject
                val page = jObject["page"]?.jsonPrimitive?.content ?: ""
                anime.title = post["titles"]!!.jsonObject["name"]!!.jsonPrimitive!!.content
                anime.thumbnail_url = post["images"]!!.jsonObject["poster"]!!.jsonPrimitive!!.content
                anime.description = post["overview"]!!.jsonPrimitive!!.content
                anime.genre = try { post["genres"]!!.jsonArray!!.joinToString { it!!.jsonObject["name"]!!.jsonPrimitive!!.content } } catch (e: Exception) { "" }
                anime.status = if (page.contains("movie") || page.contains("movies")) SAnime.COMPLETED else SAnime.UNKNOWN
                anime.artist = try { post["cast"]!!.jsonObject["acting"]!!.jsonArray!!.first()!!.jsonObject["name"]!!.jsonPrimitive!!.content } catch (e: Exception) { null }
            }
        }
        return anime
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Películas", "archives/movies/releases"),
            Pair("Series", "archives/series/releases"),
            Pair("Acción", "genres/accion"),
            Pair("Animación", "genres/animacion"),
            Pair("Crimen", "genres/crimen"),
            Pair("Fámilia", "genres/familia"),
            Pair("Misterio", "genres/misterio"),
            Pair("Suspenso", "genres/suspenso"),
            Pair("Aventura", "genres/aventura"),
            Pair("Ciencia Ficción", "genres/ciencia-ficcion"),
            Pair("Drama", "genres/drama"),
            Pair("Fantasía", "genres/fantasia"),
            Pair("Romance", "genres/romance"),
            Pair("Terror", "genres/terror"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", // Fembed
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
            "Uqload", "Upload", "SolidFiles", "StreamTape", "DoodStream", "Voex", // video servers without resolution
            "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("StreamSB:1080p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val langPref = ListPreference(screen.context).apply {
            key = "preferred_lang"
            title = "Preferred language"
            entries = arrayOf("Latino", "Español", "Subtitulado", "Todos")
            entryValues = langValues
            setDefaultValue("latino")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(langPref)
    }

    // Not Used
    override fun popularAnimeSelector(): String = throw Exception("not used")
    override fun episodeListSelector() = throw Exception("not used")
    override fun episodeFromElement(element: Element) = throw Exception("not used")
    override fun popularAnimeFromElement(element: Element) = throw Exception("not used")
    override fun popularAnimeNextPageSelector() = throw Exception("not used")
    override fun videoListSelector() = throw Exception("not used")
    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun latestUpdatesNextPageSelector() = throw Exception("not used")
    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("not used")
    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")
    override fun latestUpdatesSelector() = throw Exception("not used")
    // Not Used
}
