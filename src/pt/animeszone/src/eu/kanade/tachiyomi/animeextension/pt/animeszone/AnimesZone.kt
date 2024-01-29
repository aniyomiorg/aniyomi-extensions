package eu.kanade.tachiyomi.animeextension.pt.animeszone

import android.app.Application
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimesZone : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesZone"

    override val baseUrl = "https://animeszone.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tendencia/")

    override fun popularAnimeSelector(): String = "div.items > div.seriesList"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("abs:href"))
        thumbnail_url = element.selectFirst("div.cover-image")?.run {
            attr("style").substringAfter("url('").substringBefore("'")
        }
        title = element.selectFirst("span.series-title")!!.text()
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

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("div.aniItem > a[href]")!!.attr("abs:href"))
        thumbnail_url = element.selectFirst("div.aniItemImg img[src]")?.attr("abs:src")
        title = element.selectFirst("h2.aniTitulo")!!.text()
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimesZoneFilters.getSearchParameters(filters)

        val cleanQuery = query.replace(" ", "%20")
        val queryQuery = if (query.isBlank()) {
            ""
        } else {
            "&_pesquisa=$cleanQuery"
        }
        val url = "$baseUrl/?s=${params.genre}${params.year}${params.version}${params.studio}${params.type}${params.adult}$queryQuery"

        val httpParams = url.substringAfter("$baseUrl/?").split("&").joinToString(",") {
            val (key, value) = it.split("=", limit = 2)
            "\"$key\":\"$value\""
        }
        val softRefresh = if (page == 1) 0 else 1
        val jsonBody = """
            {
               "action":"facetwp_refresh",
               "data":{
                  "facets":{
                     "generos":[
                        ${queryToJsonList(params.genre)}
                     ],
                     "versao":[
                        ${queryToJsonList(params.year)}
                     ],
                     "tipo":[
                        ${queryToJsonList(params.version)}
                     ],
                     "estudio":[
                        ${queryToJsonList(params.studio)}
                     ],
                     "tipototal":[
                        ${queryToJsonList(params.type)}
                     ],
                     "adulto":[
                        ${queryToJsonList(params.adult)}
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

        return POST(url, headers, jsonBody)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val parsed = response.parseAs<PostResponse>()

        val document = Jsoup.parse(parsed.template)

        val animes = document.select(searchAnimeSelector()).map(::searchAnimeFromElement)

        val hasNextPage = parsed.settings.pager.run { page < total_pages }

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeSelector(): String = "div.aniItem"

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href"))
        thumbnail_url = element.selectFirst("img[src]")?.attr("abs:src")
        title = element.selectFirst("div.aniInfos")?.text() ?: "Anime"
    }

    // ============================== FILTERS ===============================
    override fun getFilterList(): AnimeFilterList = AnimesZoneFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("div.container > div div.bottom-div > h4")?.ownText()
            ?: document.selectFirst("div#info > h1")?.text()
            ?: "Anime"
        thumbnail_url = document.selectFirst("div.container > div > img[src]")?.attr("abs:src")
        description = document.selectFirst("section#sinopse p:matches(.)")?.text()
            ?: document.selectFirst("div.content.right > dialog > p:matches(.)")?.text()
        genre = document.select("div.card-body table > tbody > tr:has(>td:contains(Genres)) td > a").joinToString { it.text() }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val singleVideo = document.selectFirst("div.anime__video__player")

        // First check if single episode
        return if (singleVideo != null) {
            SEpisode.create().apply {
                name = document.selectFirst("div#info h1")?.text() ?: "Episódio"
                episode_number = 1F
                setUrlWithoutDomain(document.location())
            }.let(::listOf)
        } else {
            buildList {
                document.select(episodeListSelector()).forEach { ep ->
                    val name = ep.selectFirst("h2.aniTitulo")?.text()?.trim()
                    // Check if it's multi-season
                    var nextPageUrl = when {
                        name != null && name.startsWith("temporada ", true) -> ep.selectFirst("a[href]")!!.attr("href")
                        else -> {
                            add(episodeFromElement(ep, size + 1))
                            document.nextPageUrl()
                        }
                    }

                    while (nextPageUrl != null) {
                        val seasonDocument = client.newCall(GET(nextPageUrl)).execute()
                            .asJsoup()

                        seasonDocument.select(episodeListSelector()).forEach { seasonEp ->
                            add(episodeFromElement(seasonEp, size + 1, name))
                        }

                        nextPageUrl = seasonDocument.nextPageUrl()
                    }
                }

                reverse()
            }
        }
    }

    private fun Document.nextPageUrl() = selectFirst("div.paginadorplay > a:contains(Próxima Pagina)")?.absUrl("href")

    override fun episodeListSelector(): String = "main.site-main ul.post-lst > li"

    private fun episodeFromElement(element: Element, counter: Int, info: String? = null): SEpisode {
        val epTitle = element.selectFirst("div.title")?.text() ?: element.text()
        val epNumber = element.selectFirst("span.epiTipo")

        return SEpisode.create().apply {
            name = "Ep. ${epNumber?.text()?.trim() ?: counter} - ${epTitle.replace(EPISODE_REGEX, "")}"
                .replace(" - - ", " - ")
            episode_number = epNumber?.run {
                text().trim().toFloatOrNull()
            } ?: counter.toFloat()
            scanlator = info
            setUrlWithoutDomain(element.selectFirst("article > a")!!.attr("href"))
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val videoList = document.select("div#playeroptions li[data-post]").flatMap { vid ->
            val jsonHeaders = headersBuilder()
                .add("Accept", "application/json, text/javascript, */*; q=0.01")
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val post = vid.attr("data-post")
            val type = vid.attr("data-type")
            val nume = vid.attr("data-nume")

            val apires = client.newCall(
                GET("$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume", jsonHeaders),
            ).execute()

            val url = apires.parseAs<VideoResponse>().embed_url

            when {
                url.startsWith("https://dood") -> DoodExtractor(client).videosFromUrl(url, vid.text().trim())
                "https://gojopoolt" in url -> {
                    client.newCall(GET(url, headers)).execute()
                        .asJsoup()
                        .selectFirst("script:containsData(sources:)")
                        ?.data()
                        ?.let(BloggerJWPlayerExtractor::videosFromScript)
                        .orEmpty()
                }
                url.startsWith(baseUrl) -> videosFromInternalUrl(url)
                "blogger.com" in url -> extractBloggerVideos(url, vid.text().trim())
                else -> emptyList()
            }
        }

        return videoList
    }

    private fun videosFromInternalUrl(url: String): List<Video> {
        val videoDocument = client.newCall(GET(url, headers)).execute()
            .asJsoup()

        val script = videoDocument.selectFirst("script:containsData(decodeURIComponent)")?.data()
            ?.let(::getDecrypted)
            ?: videoDocument.selectFirst("script:containsData(sources:)")?.data()
            ?: return emptyList()

        return when {
            "/bloggerjwplayer" in url || "jwplayer-2" in url || "/ctaplayer" in url -> {
                BloggerJWPlayerExtractor.videosFromScript(script)
            }
            "/m3u8" in url -> PlaylistExtractor.videosFromScript(script)
            else -> emptyList()
        }
    }

    private fun extractBloggerVideos(url: String, name: String): List<Video> {
        return client.newCall(GET(url, headers)).execute()
            .body.string()
            .takeIf { !it.contains("errorContainer") }
            .let { it ?: return emptyList() }
            .substringAfter("\"streams\":[")
            .substringBefore("]")
            .split("},")
            .map {
                val videoUrl = it.substringAfter("{\"play_url\":\"").substringBefore('"')
                val format = it.substringAfter("\"format_id\":").substringBefore("}")
                val quality = when (format) {
                    "18" -> "360p"
                    "22" -> "720p"
                    else -> "Unknown"
                }
                Video(videoUrl, "$quality - $name", videoUrl, headers = headers)
            }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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

    // ============================= Utilities ==============================
    private fun getDecrypted(script: String): String? {
        val patchedScript = script.trim().split("\n").first()
            .replace("eval(function", "function a")
            .replace("decodeURIComponent(escape(r))}(", "r};a(")
            .substringBeforeLast(")")

        return QuickJs.create().use {
            it.evaluate(patchedScript)?.toString()
        }
    }

    private fun queryToJsonList(input: String): String {
        if (input.isEmpty()) return ""
        return input.substringAfter("=").split("%2C").joinToString(",") {
            "\"$it\""
        }
    }

    @Serializable
    data class VideoResponse(val embed_url: String)

    @Serializable
    data class PostResponse(
        val template: String,
        val settings: Settings,
    ) {
        @Serializable
        data class Settings(val pager: Pager) {
            @Serializable
            data class Pager(
                val page: Int,
                val total_pages: Int,
            )
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private val EPISODE_REGEX by lazy { Regex("""Episódio ?\d+\.?\d* ?""") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
    }
}
