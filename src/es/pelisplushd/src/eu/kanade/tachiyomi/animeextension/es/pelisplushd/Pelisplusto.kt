package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Pelisplusto(override val name: String, override val baseUrl: String) : Pelisplushd(name, baseUrl) {

    private val json: Json by injectLazy()

    override val supportsLatest = false

    override fun popularAnimeSelector(): String = "article.item"

    override fun popularAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h2").text()
        anime.thumbnail_url = element.select("a .item__image picture img").attr("data-src")
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(".home__slider_content div h1.slugh1")!!.text()
        anime.description = document.selectFirst(".home__slider_content .description")!!.text()
        anime.genre = document.select(".home__slider_content div:nth-child(5) > a").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        } else {
            var jsonscript = ""
            jsoup.select("script[type=text/javascript]").mapNotNull { script ->
                val ssRegex = Regex("(?i)seasons")
                val ss = if (script.data().contains(ssRegex)) script.data() else ""
                val swaa = ss.substringAfter("seasonsJson = ").substringBefore(";")
                jsonscript = swaa
            }
            val jsonParse = json.decodeFromString<JsonObject>(jsonscript)
            var index = 0
            jsonParse.entries.map {
                it.value.jsonArray.reversed().map { element ->
                    index += 1
                    val jsonElement = element!!.jsonObject
                    val season = jsonElement["season"]!!.jsonPrimitive!!.content
                    val title = jsonElement["title"]!!.jsonPrimitive!!.content
                    val ep = jsonElement["episode"]!!.jsonPrimitive!!.content
                    val episode = SEpisode.create()
                    episode.episode_number = index.toFloat()
                    episode.name = "T$season - E$ep - $title"
                    episode.setUrlWithoutDomain("${response.request.url}/season/$season/episode/$ep")
                    episodes.add(episode)
                }
            }
        }
        return episodes.reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/api/search?search=$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(".bg-tabs li").map { it ->
            val link = it.attr("data-server")
                .replace("https://sblanh.com", "https://watchsb.com")
                .replace(Regex("([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https:\\/\\/ww3.pelisplus.to.*"), "")

            loadExtractor(link).let { videos ->
                videoList.addAll(videos)
            }
        }
        return videoList
    }

    private fun loadExtractor(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
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
            embedUrl.contains("sblanh.com") || embedUrl.contains("sbbrisk.com") || embedUrl.contains("lvturbo.com")
        ) {
            runCatching {
                StreamSBExtractor(client).videosFromUrl(url, headers, prefix = prefix)
            }.getOrNull()?.let { videoList.addAll(it) }
        }
        if (embedUrl.contains("okru") || embedUrl.contains("ok.ru")) {
            videoList.addAll(
                OkruExtractor(client).videosFromUrl(url, prefix, true),
            )
        }
        if (embedUrl.contains("voe")) {
            VoeExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
        }
        if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
            FilemoonExtractor(client).videosFromUrl(url, prefix)
                .also(videoList::addAll)
        }
        if (embedUrl.contains("streamlare")) {
            videoList.addAll(StreamlareExtractor(client).videosFromUrl(url))
        }
        return videoList
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por genero ignora los otros filtros"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "doramas"),
            Pair("Animes", "animes"),
            Pair("Acción", "genres/accion"),
            Pair("Action & Adventure", "genres/action-adventure"),
            Pair("Animación", "genres/animacion"),
            Pair("Aventura", "genres/aventura"),
            Pair("Bélica", "genres/belica"),
            Pair("Ciencia ficción", "genres/ciencia-ficcion"),
            Pair("Comedia", "genres/comedia"),
            Pair("Crimen", "genres/crimen"),
            Pair("Documental", "genres/documental"),
            Pair("Dorama", "genres/dorama"),
            Pair("Drama", "genres/drama"),
            Pair("Familia", "genres/familia"),
            Pair("Fantasía", "genres/fantasia"),
            Pair("Guerra", "genres/guerra"),
            Pair("Historia", "genres/historia"),
            Pair("Horror", "genres/horror"),
            Pair("Kids", "genres/kids"),
            Pair("Misterio", "genres/misterio"),
            Pair("Música", "genres/musica"),
            Pair("Musical", "genres/musical"),
            Pair("Película de TV", "genres/pelicula-de-tv"),
            Pair("Reality", "genres/reality"),
            Pair("Romance", "genres/romance"),
            Pair("Sci-Fi & Fantasy", "genres/sci-fi-fantasy"),
            Pair("Soap", "genres/soap"),
            Pair("Suspense", "genres/suspense"),
            Pair("Terror", "genres/terror"),
            Pair("War & Politics", "genres/war-politics"),
            Pair("Western", "genres/western"),
        ),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "StreamSB:1080p",
            "StreamSB:720p",
            "StreamSB:480p",
            "StreamSB:360p",
            "StreamSB:240p",
            "StreamSB:144p", // StreamSB
            "DoodStream",
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
