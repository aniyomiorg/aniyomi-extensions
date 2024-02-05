package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.StreamHideExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Pelisplusto(override val name: String, override val baseUrl: String) : Pelisplushd(name, baseUrl) {

    private val json: Json by injectLazy()

    override val supportsLatest = false

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
        )
    }

    override fun popularAnimeSelector(): String = "article.item"

    override fun popularAnimeNextPageSelector(): String = "a[rel=\"next\"]"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("abs:href"))
        anime.title = element.select("a h2").text()
        anime.thumbnail_url = element.select("a .item__image picture img").attr("data-src")
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(".home__slider_content div h1.slugh1")!!.text()
        anime.description = document.selectFirst(".home__slider_content .description")!!.text()
        anime.genre = document.select(".home__slider_content div:nth-child(5) > a").joinToString { it.text() }
        anime.artist = document.selectFirst(".home__slider_content div:nth-child(7) > a")?.text()
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
                setUrlWithoutDomain(response.request.url.toString())
            }
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
                    val episode = SEpisode.create().apply {
                        episode_number = index.toFloat()
                        name = "T$season - E$ep - $title"
                        setUrlWithoutDomain("${response.request.url}/season/$season/episode/$ep")
                    }
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

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select(".bg-tabs ul li").map { it ->
            val url = String(Base64.decode(it.attr("data-server"), Base64.DEFAULT))
            if (url.contains("/player/")) {
                try {
                    val script = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(window.onload)")!!.data()
                    fetchUrls(script).map {
                        val link = it.replace("https://sblanh.com", "https://lvturbo.com")
                            .replace(Regex("([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https:\\/\\/ww3.pelisplus.to.*"), "")
                        serverVideoResolver(link).let { videos ->
                            videoList.addAll(videos)
                        }
                    }
                } catch (_: Exception) {}
            } else {
                val link = url.replace("https://sblanh.com", "https://lvturbo.com")
                    .replace(Regex("([a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)=https:\\/\\/ww3.pelisplus.to.*"), "")

                serverVideoResolver(link).let { videos ->
                    videoList.addAll(videos)
                }
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            if (embedUrl.contains("voe")) {
                VoeExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if ((embedUrl.contains("amazon") || embedUrl.contains("amz")) && !embedUrl.contains("disable")) {
                val body = client.newCall(GET(url)).execute().asJsoup()
                if (body.select("script:containsData(var shareId)").toString().isNotBlank()) {
                    val shareId = body.selectFirst("script:containsData(var shareId)")!!.data()
                        .substringAfter("shareId = \"").substringBefore("\"")
                    val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
                        .execute().asJsoup()
                    val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
                    val amazonApi =
                        client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
                            .execute().asJsoup()
                    val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
                    videoList.add(Video(videoUrl, "Amazon", videoUrl))
                }
            }
            if (embedUrl.contains("ok.ru") || embedUrl.contains("okru")) {
                OkruExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:", headers = vidHeaders).also(videoList::addAll)
            }
            if (embedUrl.contains("uqload")) {
                UqloadExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("mp4upload")) {
                Mp4uploadExtractor(client).videosFromUrl(url, headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish")) {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
                val url2 = url.replace("https://doodstream.com/e/", "https://dood.to/e/")
                DoodExtractor(client).videoFromUrl(url2, "DoodStream", false)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("streamlare")) {
                StreamlareExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("yourupload") || embedUrl.contains("upload")) {
                YourUploadExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("burstcloud") || embedUrl.contains("burst")) {
                BurstCloudExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("fastream")) {
                FastreamExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("upstream")) {
                UpstreamExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape")) {
                StreamTapeExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("ahvsh") || embedUrl.contains("streamhide")) {
                StreamHideExtractor(client).videosFromUrl(url, "StreamHide").let { videoList.addAll(it) }
            }
            if (embedUrl.contains("vudeo") || embedUrl.contains("vudea")) {
                VudeoExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("tomatomatela")) {
                runCatching {
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
                    if (status == "200") { videoList.add(Video(file, "Tomatomatela", file, headers = null)) }
                }
            }
        } catch (_: Exception) { }
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
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
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
