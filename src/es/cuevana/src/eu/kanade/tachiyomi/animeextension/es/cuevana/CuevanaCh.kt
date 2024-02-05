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
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

class CuevanaCh(override val name: String, override val baseUrl: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "es"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "Tomatomatela",
        )
    }

    override fun popularAnimeSelector(): String = ".MovieList .TPostMv .TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        anime.title = element.select("a .Title").text()
        anime.thumbnail_url = element.select("a .Image figure.Objf img").attr("abs:data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "nav.navigation > div.nav-links > a.next.page-numbers"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        if (response.request.url.toString().contains("/serie/")) {
            document.select("[id*=season-]").reversed().mapIndexed { idxSeason, season ->
                val noSeason = try {
                    season.attr("id").substringAfter("season-").toInt()
                } catch (e: Exception) {
                    idxSeason
                }
                season.select(".TPostMv article.TPost").reversed().mapIndexed { idxCap, cap ->
                    val epNum = try {
                        cap.select("a div.Image span.Year").text().substringAfter("x").toFloat()
                    } catch (e: Exception) {
                        idxCap.toFloat()
                    }
                    val episode = SEpisode.create()
                    val date = cap.select("a > p").text()
                    val epDate = try {
                        SimpleDateFormat("yyyy-MM-dd").parse(date)
                    } catch (e: Exception) {
                        null
                    }
                    episode.episode_number = epNum
                    episode.name = "T$noSeason - Episodio $epNum"
                    if (epDate != null) episode.date_upload = epDate.time
                    episode.setUrlWithoutDomain(cap.select("a").attr("href"))
                    episodes.add(episode)
                }
            }
        } else {
            val episode = SEpisode.create().apply {
                episode_number = 1f
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        }
        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("ul.anime_muti_link li").map {
            val langPrefix = try {
                val languageTag = it.selectFirst(".cdtr span")!!.text()
                if (languageTag.lowercase().contains("latino")) {
                    "[LAT]"
                } else if (languageTag.lowercase().contains("castellano")) {
                    "[CAST]"
                } else if (languageTag.lowercase().contains("subtitulado")) {
                    "[SUB]"
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }
            val url = it.attr("abs:data-video")
            try {
                serverVideoResolver(url, langPrefix).also(videoList::addAll)
            } catch (_: Exception) { }
        }

        return videoList
    }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            if (embedUrl.contains("voe")) {
                VoeExtractor(client).videosFromUrl(url, prefix).also(videoList::addAll)
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
            if (embedUrl.contains("filelions") || embedUrl.contains("lion")) {
                StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$prefix FileLions:$it" }).also(videoList::addAll)
            }
        } catch (_: Exception) { }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search.html?keyword=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/category/${genreFilter.toUriPart()}?page=$page")
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
        anime.title = document.selectFirst(".TPost header .Title")!!.text()
        anime.thumbnail_url = document.selectFirst(".backdrop article div.Image figure img")!!.attr("abs:data-src")
        anime.description = document.selectFirst(".backdrop article.TPost div.Description")!!.text().trim()
        anime.genre = document.select("ul.InfoList li:nth-child(1) > a").joinToString { it.text() }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

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
}
