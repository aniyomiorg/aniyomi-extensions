package eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class VidoExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val videoList = mutableListOf<Video>()

        val id = url.substringAfterLast("/").substringBefore(".html")
        val document = client.newCall(
            GET(url),
        ).execute().asJsoup()
        val postBodyValues = mutableListOf<String>()

        document.select("form > input").forEach {
            val name = it.attr("name")
            val value = if (name == "file_code") {
                id
            } else {
                it.attr("value")
            }
            postBodyValues.add("$name=$value")
        }

        val postBody = postBodyValues.joinToString("&").toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val postHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .add("Host", url.toHttpUrl().host)
            .add("Origin", "https://${url.toHttpUrl().host}")
            .add("Referer", url)
            .build()
        val postDocument = client.newCall(
            POST("https://${url.toHttpUrl().host}/dl", body = postBody, headers = postHeaders),
        ).execute().asJsoup()

        val sourcesScript = postDocument.selectFirst("script:containsData(sources)")?.data() ?: return emptyList()
        val sourcesString = Regex("""sources: ?(\[.*?\])""").find(sourcesScript)?.groupValues?.get(1) ?: return emptyList()

        val sources = Json.decodeFromString<List<String>>(sourcesString)
        sources.forEach { source ->
            val masterHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Host", source.toHttpUrl().host)
                .add("Origin", "https://${url.toHttpUrl().host}")
                .add("Referer", "https://${url.toHttpUrl().host}/")
                .build()

            val masterPlaylist = client.newCall(
                GET(source, headers = masterHeaders),
            ).execute().body.string()

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = "Vido - " + it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p "
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")

                    val videoHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Host", videoUrl.toHttpUrl().host)
                        .add("Origin", "https://${url.toHttpUrl().host}")
                        .add("Referer", "https://${url.toHttpUrl().host}/")
                        .build()

                    videoList.add(Video(videoUrl, quality, videoUrl, headers = videoHeaders))
                }
        }

        return videoList
    }
}
