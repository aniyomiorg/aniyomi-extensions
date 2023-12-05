package eu.kanade.tachiyomi.animeextension.pt.pobreflix.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class SuperFlixExtractor(
    private val client: OkHttpClient,
    private val defaultHeaders: Headers,
    private val genericExtractor: (String, String) -> List<Video>,
) {
    private val json: Json by injectLazy()

    fun videosFromUrl(url: String): List<Video> {
        val links = linksFromUrl(url)

        val fixedLinks = links.flatMap {
            val (language, linkUrl) = it
            when {
                linkUrl.contains("?vid=") -> linksFromPlayer(linkUrl, language)
                else -> listOf(it)
            }
        }

        return fixedLinks.flatMap { genericExtractor(it.second, it.first) }
    }

    private fun linksFromPlayer(url: String, language: String): List<Pair<String, String>> {
        val httpUrl = url.toHttpUrl()
        val id = httpUrl.queryParameter("vid")!!
        val headers = defaultHeaders.newBuilder()
            .set("referer", "$API_DOMAIN/")
            .set("origin", API_DOMAIN)
            .build()

        val doc = client.newCall(GET(url, headers)).execute().use { it.asJsoup() }

        val baseUrl = "https://" + httpUrl.host
        val apiUrl = "$baseUrl/ajax_sources.php"

        val apiHeaders = headers.newBuilder()
            .set("referer", url)
            .set("origin", baseUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return doc.select("ul > li[data-order-value]").mapNotNull {
            val name = it.attr("data-dropdown-value")
            val order = it.attr("data-order-value")

            val formBody = FormBody.Builder()
                .add("vid", id)
                .add("alternative", name)
                .add("ord", order)
                .build()

            val req = client.newCall(POST(apiUrl, apiHeaders, formBody)).execute()
                .use { it.body.string() }

            runCatching {
                val iframeUrl = json.decodeFromString<PlayerLinkDto>(req).iframe!!
                val iframeServer = iframeUrl.toHttpUrl().queryParameter("sv")!!
                language to when (name) {
                    "1xbet" -> "https://watch.brplayer.site/watch?v=${iframeServer.trim('/')}"
                    else -> iframeServer
                }
            }.getOrNull()
        }
    }

    @Serializable
    data class PlayerLinkDto(val iframe: String? = null)

    private fun linksFromUrl(url: String): List<Pair<String, String>> {
        val doc = client.newCall(GET(url, defaultHeaders)).execute().use { it.asJsoup() }

        val items = doc.select("div.select_language").mapNotNull {
            val target = it.attr("data-target")
            val id = doc.selectFirst("div.players_select div[data-target=$target] div[data-id]")
                ?.attr("data-id")
                ?: return@mapNotNull null

            it.text() to id // (Language, videoId)
        }

        val headers = defaultHeaders.newBuilder()
            .set("Origin", API_DOMAIN)
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return items.mapNotNull {
            runCatching {
                it.first to getLink(it.second, headers)!!
            }.getOrNull()
        }
    }

    private fun getLink(id: String, headers: Headers): String? {
        val body = FormBody.Builder()
            .add("action", "getPlayer")
            .add("video_id", id)
            .build()

        val res = client.newCall(POST("$API_DOMAIN/api", headers, body)).execute()
            .use { it.body.string() }

        return json.decodeFromString<ApiResponseDto>(res).data?.video_url
    }

    @Serializable
    data class ApiResponseDto(val data: DataDto? = null)

    @Serializable
    data class DataDto(val video_url: String? = null)
}

private const val API_DOMAIN = "https://superflixapi.top"
