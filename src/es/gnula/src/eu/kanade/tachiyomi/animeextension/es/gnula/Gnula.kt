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
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class Gnula : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Gnula"

    override val baseUrl = "https://gnula.life"

    override val lang = "es"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "StreamHideVid",
        )

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[CAST]", "[SUB]")
        private val LANGUAGE_LIST_VALUES = arrayOf("latino", "spanish", "english")
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

        if (response.request.url.toString().contains("/movies/")) {
            document.select("script").forEach { script ->
                if (script.data().contains("{\"props\":{\"pageProps\"")) {
                    val jObject = json.decodeFromString<JsonObject>(script.data())
                    val props = jObject["props"]!!.jsonObject
                    val pageProps = props["pageProps"]!!.jsonObject
                    val post = pageProps["post"]!!.jsonObject
                    post["players"]!!.jsonObject.entries.map {
                        val key = it.key
                        val langVal = try {
                            LANGUAGE_LIST[LANGUAGE_LIST.indexOf(LANGUAGE_LIST_VALUES.firstOrNull { it == key })]
                        } catch (_: Exception) { "" }
                        it.value!!.jsonArray!!.map {
                            val server = it!!.jsonObject["result"]!!.jsonPrimitive!!.content
                            var url = ""
                            client.newCall(GET(server)).execute()!!.asJsoup()!!.select("script")!!.map { sc ->
                                if (sc.data().contains("var url = '")) {
                                    url = sc.data().substringAfter("var url = '").substringBefore("';")
                                }
                            }
                            serverVideoResolver(url, langVal).let { videos -> videoList.addAll(videos) }
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
                    val players = episode["players"]!!.jsonObject.entries
                    players.map {
                        val key = it.key
                        val langVal = try {
                            LANGUAGE_LIST[LANGUAGE_LIST.indexOf(LANGUAGE_LIST_VALUES.firstOrNull { it == key })]
                        } catch (_: Exception) { "" }

                        it.value!!.jsonArray!!.map {
                            val server = it!!.jsonObject["result"]!!.jsonPrimitive!!.content
                            var url = ""
                            client.newCall(GET(server)).execute()!!.asJsoup()!!.select("script")!!.map { sc ->
                                if (sc.data().contains("var url = '")) {
                                    url = sc.data().substringAfter("var url = '").substringBefore("';")
                                }
                            }
                            serverVideoResolver(url, langVal).let { videos -> videoList.addAll(videos) }
                        }
                    }
                }
            }
        }
        return videoList
    }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            if (embedUrl.contains("voe")) {
                VoeExtractor(client).videoFromUrl(url, prefix = "$prefix Voe:")?.let { videoList.add(it) }
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
                    videoList.add(Video(videoUrl, "$prefix Amazon", videoUrl))
                }
            }
            if (embedUrl.contains("ok.ru") || embedUrl.contains("okru")) {
                OkruExtractor(client).videosFromUrl(url, prefix).also(videoList::addAll)
            }
            if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "$prefix Filemoon:", headers = vidHeaders).also(videoList::addAll)
            }
            if (embedUrl.contains("uqload")) {
                UqloadExtractor(client).videosFromUrl(url, prefix = prefix).also(videoList::addAll)
            }
            if (embedUrl.contains("mp4upload")) {
                Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix = prefix).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish")) {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
                val url2 = url.replace("https://doodstream.com/e/", "https://dood.to/e/")
                DoodExtractor(client).videoFromUrl(url2, "$prefix DoodStream", false)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("streamlare")) {
                StreamlareExtractor(client).videosFromUrl(url, prefix = prefix).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("yourupload") || embedUrl.contains("upload")) {
                YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("burstcloud") || embedUrl.contains("burst")) {
                BurstCloudExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("fastream")) {
                FastreamExtractor(client, headers).videosFromUrl(url, prefix = "$prefix Fastream:").also(videoList::addAll)
            }
            if (embedUrl.contains("upstream")) {
                UpstreamExtractor(client).videosFromUrl(url, prefix = prefix).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape")) {
                StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape")?.let { videoList.add(it) }
            }
            if (embedUrl.contains("ahvsh") || embedUrl.contains("streamhide")) {
                StreamHideVidExtractor(client).videosFromUrl(url, "$prefix ").let { videoList.addAll(it) }
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
                    if (status == "200") { videoList.add(Video(file, "$prefix Tomatomatela", file, headers = null)) }
                }
            }
        } catch (_: Exception) { }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
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
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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
