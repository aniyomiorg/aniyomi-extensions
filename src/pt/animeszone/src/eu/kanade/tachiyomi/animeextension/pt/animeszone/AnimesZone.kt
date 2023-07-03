package eu.kanade.tachiyomi.animeextension.pt.animeszone

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animeextension.pt.animeszone.extractors.BloggerJWPlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeszone.extractors.PlaylistExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimesZone : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesZone"

    override val baseUrl = "https://animeszone.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val EPISODE_REGEX = Regex("""Episódio ?\d+\.?\d* ?""")
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tendencia/")

    override fun popularAnimeSelector(): String = "div.items > div.seriesList"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(
                element.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath,
            )
            thumbnail_url = element.selectFirst("div.cover-image")?.let {
                it.attr("style").substringAfter("url('").substringBefore("'")
            } ?: ""
            title = element.selectFirst("span.series-title")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/animes-legendados/")
        } else {
            GET("$baseUrl/animes-legendados/page/$page/")
        }
    }

    override fun latestUpdatesSelector(): String = "main#list-animes ul.post-lst > li"

    override fun latestUpdatesNextPageSelector(): String = "div.paginadorplay > a.active + a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(
                element.selectFirst("div.aniItem > a[href]")!!.attr("href").toHttpUrl().encodedPath,
            )
            thumbnail_url = element.selectFirst("div.aniItemImg img[src]")?.attr("src") ?: ""
            title = element.selectFirst("h2.aniTitulo")!!.text()
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AnimesZoneFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: AnimesZoneFilters.FilterSearchParams): Request {
        val cleanQuery = query.replace(" ", "%20")
        val queryQuery = if (query.isBlank()) {
            ""
        } else {
            "&_pesquisa=$cleanQuery"
        }
        val url = "$baseUrl/?s=${filters.genre}${filters.year}${filters.version}${filters.studio}${filters.type}${filters.adult}$queryQuery"

        val httpParams = url.substringAfter("$baseUrl/?").split("&").joinToString(",") {
            val data = it.split("=")
            "\"${data[0]}\":\"${data[1]}\""
        }
        val softRefresh = if (page == 1) 0 else 1
        val jsonBody = """
            {
               "action":"facetwp_refresh",
               "data":{
                  "facets":{
                     "generos":[
                        ${queryToJsonList(filters.genre)}
                     ],
                     "versao":[
                        ${queryToJsonList(filters.year)}
                     ],
                     "tipo":[
                        ${queryToJsonList(filters.version)}
                     ],
                     "estudio":[
                        ${queryToJsonList(filters.studio)}
                     ],
                     "tipototal":[
                        ${queryToJsonList(filters.type)}
                     ],
                     "adulto":[
                        ${queryToJsonList(filters.adult)}
                     ],
                     "pesquisa":"$query",
                     "paginar":[

                     ]
                  },
                  "frozen_facets":{

                  },
                  "http_params":{
                     "get":{
                        $httpParams
                     },
                     "uri":"",
                     "url_vars":[

                     ]
                  },
                  "template":"wp",
                  "extras":{
                     "sort":"default"
                  },
                  "soft_refresh":$softRefresh,
                  "is_bfcache":1,
                  "first_load":0,
                  "paged":$page
               }
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val postHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/json")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Origin", baseUrl)
            .add("Referer", url)
            .build()

        return POST(url, body = jsonBody, headers = postHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<PostResponse>(response.body.string())

        val document = Jsoup.parse(parsed.template)

        val animes = document.select(searchAnimeSelector()).map { element ->
            searchAnimeFromElement(element)
        }

        return AnimesPage(animes, parsed.settings.pager.page < parsed.settings.pager.total_pages)
    }

    override fun searchAnimeSelector(): String = "div.aniItem"

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(
                element.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath,
            )
            thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
            title = element.selectFirst("div.aniInfos")?.text() ?: "Anime"
        }
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimesZoneFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("div.container > div div.bottom-div > h4")?.ownText() ?: "Anime"
            thumbnail_url = document.selectFirst("div.container > div > img[src]")?.attr("src")
            description = document.selectFirst("section#sinopse p:matches(.)")?.text()
            genre = document.select("div.card-body table > tbody > tr:has(>td:contains(Genres)) td > a").joinToString(", ") { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val document = response.asJsoup()

        // Used only for fallback
        var counter = 1
        val singleVideo = document.selectFirst("div.anime__video__player")

        // First check if single episode
        if (singleVideo != null) {
            val episode = SEpisode.create()
            episode.name = document.selectFirst("div#info h1")?.text() ?: "Episódio"
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(response.request.url.encodedPath)
            episodeList.add(episode)
        } else {
            document.select(episodeListSelector()).forEach { ep ->
                val name = ep.selectFirst("h2.aniTitulo")?.text()?.trim()
                // Check if it's multi-season
                if (name != null && name.startsWith("temporada ", true)) {
                    var nextPageUrl: String? = ep.selectFirst("a[href]")!!.attr("href")
                    while (nextPageUrl != null) {
                        val seasonDocument = client.newCall(GET(nextPageUrl)).execute().asJsoup()

                        seasonDocument.select(episodeListSelector()).forEach { seasonEp ->
                            episodeList.add(episodeFromElement(seasonEp, counter, name))
                            counter++
                        }

                        nextPageUrl = seasonDocument.selectFirst("div.paginadorplay > a:contains(Próxima Pagina)")?.absUrl("href")
                    }
                } else {
                    episodeList.add(episodeFromElement(ep, counter))
                    counter++

                    var nextPageUrl: String? = document.selectFirst("div.paginadorplay > a:contains(Próxima Pagina)")?.absUrl("href")
                    while (nextPageUrl != null) {
                        val document = client.newCall(GET(nextPageUrl)).execute().asJsoup()
                        document.select(episodeListSelector()).forEach { ep ->
                            episodeList.add(episodeFromElement(ep, counter))
                            counter++
                        }

                        nextPageUrl = document.selectFirst("div.paginadorplay > a:contains(Próxima Pagina)")?.absUrl("href")
                    }
                }
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = "main.site-main ul.post-lst > li"

    private fun episodeFromElement(element: Element, counter: Int, info: String? = null): SEpisode {
        val epTitle = element.selectFirst("div.title")?.text() ?: element.text()
        val epNumber = element.selectFirst("span.epiTipo")

        return SEpisode.create().apply {
            name = "Ep. ${epNumber?.text()?.trim() ?: counter} - ${epTitle.replace(EPISODE_REGEX, "")}"
                .replace(" - - ", " - ")
            episode_number = epNumber?.let {
                it.text().trim().toFloatOrNull()
            } ?: counter.toFloat()
            scanlator = info
            setUrlWithoutDomain(
                element.selectFirst("article > a")!!.attr("href").toHttpUrl().encodedPath,
            )
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("div#playeroptions li[data-post]").forEach { vid ->
            val jsonHeaders = headers.newBuilder()
                .add("Accept", "application/json, text/javascript, */*; q=0.01")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Referer", response.request.url.toString())
                .add("X-Requested-With", "XMLHttpRequest")
                .build()
            val response = client.newCall(
                GET("$baseUrl/wp-json/dooplayer/v2/${vid.attr("data-post")}/${vid.attr("data-type")}/${vid.attr("data-nume")}", headers = jsonHeaders),
            ).execute()
            val url = json.decodeFromString<VideoResponse>(response.body.string()).embed_url

            when {
                url.contains("sbembed.com") || url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                    url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                    url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                    url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                    url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                    url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                    url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com") ||
                    url.contains("lvturbo") || url.contains("sbface.com") || url.contains("sblongvu.com") -> {
                    videoList.addAll(
                        StreamSBExtractor(client).videosFromUrl(url, headers),
                    )
                }

                url.startsWith("https://dood") -> {
                    videoList.addAll(DoodExtractor(client).videosFromUrl(url, vid.text().trim()))
                }

                url.toHttpUrl().host == baseUrl.toHttpUrl().host -> {
                    val videoDocument = client.newCall(
                        GET(url),
                    ).execute().asJsoup()

                    val decrypted = getDecrypted(videoDocument) ?: return@forEach

                    when {
                        "/bloggerjwplayer" in url ->
                            videoList.addAll(BloggerJWPlayerExtractor.videosFromScript(decrypted))
                        "jwplayer-2" in url ->
                            videoList.addAll(BloggerJWPlayerExtractor.videosFromScript(decrypted))
                        "/m3u8" in url ->
                            videoList.addAll(PlaylistExtractor.videosFromScript(decrypted))
                    }
                }
//                url.toHttpUrl().host == "gojoanimes.biz" -> {
//                    val videoDocument = client.newCall(
//                        GET(url),
//                    ).execute().asJsoup()
//
//                    val decrypted = getDecrypted(videoDocument) ?: return@forEach
//
//                    Regex("""file: ?["']([^"']+?)["']""").findAll(decrypted).forEach { videoFile ->
//                        val videoHeaders = headers.newBuilder()
//                            .add("Accept", "*/*")
//                            .add("Origin", "https://gojoanimes.biz")
//                            .add("Referer", "https://gojoanimes.biz/")
//                            .build()
//                        videoList.add(
//                            Video(videoFile.groupValues[1], vid.text().trim(), videoFile.groupValues[1], headers = videoHeaders),
//                        )
//                    }
//                }
                url.toHttpUrl().host == "www.blogger.com" -> {
                    videoList.addAll(extractBloggerVideos(url, vid.text().trim()))
                }
            }
        }

        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    private fun extractBloggerVideos(url: String, name: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()

        val jsElement = document.selectFirst("script:containsData(VIDEO_CONFIG)") ?: return emptyList()
        val js = jsElement.data()
        val json = json.decodeFromString<VideoConfig>(js.substringAfter("var VIDEO_CONFIG = "))

        return json.streams.map {
            Video(it.play_url, "${it.format_id} - $name", it.play_url)
        }
    }

    @Serializable
    data class VideoConfig(
        val streams: List<Stream>,
    ) {
        @Serializable
        data class Stream(
            val play_url: String,
            val format_id: Int,
        )
    }

    private fun getDecrypted(document: Document): String? {
        val script = document.selectFirst("script:containsData(decodeURIComponent)") ?: return null

        val quickJs = QuickJs.create()
        val decrypted = quickJs.evaluate(
            script.data().trim().split("\n")[0].replace("eval(function", "function a").replace("decodeURIComponent(escape(r))}(", "r};a(").substringBeforeLast(")"),
        ).toString()
        quickJs.close()
        return decrypted
    }

    private fun queryToJsonList(input: String): String {
        if (input.isEmpty()) return ""
        return input.substringAfter("=").split("%2C").joinToString(",") {
            "\"$it\""
        }
    }

    @Serializable
    data class VideoResponse(
        val embed_url: String,
    )

    @Serializable
    data class PostResponse(
        val template: String,
        val settings: Settings,
    ) {
        @Serializable
        data class Settings(
            val pager: Pager,
        ) {
            @Serializable
            data class Pager(
                val page: Int,
                val total_pages: Int,
            )
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
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
