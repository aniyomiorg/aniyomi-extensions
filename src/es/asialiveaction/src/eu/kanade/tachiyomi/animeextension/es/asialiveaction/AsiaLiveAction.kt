package eu.kanade.tachiyomi.animeextension.es.asialiveaction

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.asialiveaction.extractors.VidGuardExtractor
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
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Calendar

class AsiaLiveAction : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AsiaLiveAction"

    override val baseUrl = "https://asialiveaction.com"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "FileLions"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape",
            "Fastream", "Filemoon", "StreamWish", "VidGuard",
            "Amazon", "AmazonES", "Fireload", "FileLions",
            "vk.com",
        )
    }

    override fun popularAnimeSelector(): String = "div.TpRwCont main section ul.MovieList li.TPostMv article.TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/todos/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h3.Title").text()
        anime.thumbnail_url = element.select("a div.Image figure img").attr("src").trim().replace("//", "https://")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.TpRwCont main div a.next.page-numbers"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("header div.Image figure img")!!.attr("src").trim().replace("//", "https://")
        anime.title = document.selectFirst("header div.asia-post-header h1.Title")!!.text()
        anime.description = document.selectFirst("header div.asia-post-main div.Description p:nth-child(2), header div.asia-post-main div.Description p")!!.text().removeSurrounding("\"")
        anime.genre = document.select("div.asia-post-main p.Info span.tags a").joinToString { it.text() }
        val year = document.select("header div.asia-post-main p.Info span.Date a").text().toInt()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        anime.status = when {
            year < currentYear -> SAnime.COMPLETED
            year == currentYear -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "#ep-list div.TPTblCn span a, #ep-list div.TPTblCn .accordion"

    override fun episodeFromElement(element: Element): SEpisode {
        return if (element.attr("class").contains("accordion")) {
            val epNum = getNumberFromEpsString(element.select("label span").text())
            SEpisode.create().apply {
                name = element.select("label span").text().trim()
                episode_number = when {
                    epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
                    else -> 1F
                }
                setUrlWithoutDomain(element.selectFirst("ul li a")?.attr("abs:href")!!)
            }
        } else {
            val epNum = getNumberFromEpsString(element.select("div.flex-grow-1 p").text())
            SEpisode.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                episode_number = when {
                    epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
                    else -> 1F
                }
                name = element.select("div.flex-grow-1 p").text().trim()
            }
        }
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script:containsData(var videos)").forEach { script ->
            fetchUrls(script.data()).map { url ->
                try {
                    serverVideoResolver(url).also(videoList::addAll)
                } catch (_: Exception) {}
            }
        }
        return videoList
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
            if (embedUrl.contains("wishembed") ||
                embedUrl.contains("streamwish") ||
                embedUrl.contains("strwish") ||
                embedUrl.contains("wish") ||
                embedUrl.contains("sfastwish")
            ) {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
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
            if (embedUrl.contains("ahvsh") || embedUrl.contains("streamhide") || embedUrl.contains("hide")) {
                StreamHideVidExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("filelions") || embedUrl.contains("lion") || embedUrl.contains("fviplions")) {
                StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("vembed") || embedUrl.contains("guard")) {
                VidGuardExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("vk")) {
                VkExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
            }
        } catch (_: Exception) { }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query")
            genreFilter.state != 0 -> GET("$baseUrl/tag/${genreFilter.toUriPart()}/page/$page")
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
            Pair("<Selecionar>", "all"),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Drama", "drama"),
            Pair("Deporte", "deporte"),
            Pair("Erótico", "erotico"),
            Pair("Escolar", "escolar"),
            Pair("Extraterrestres", "extraterrestres"),
            Pair("Fantasía", "fantasia"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Lucha", "lucha"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Yaoi / BL", "yaoi-bl"),
            Pair("Yuri / GL", "yuri-gl"),
        ),
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

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
