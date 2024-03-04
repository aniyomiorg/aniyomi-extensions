package eu.kanade.tachiyomi.animeextension.en.multimovies.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decrypt
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class RabbitStreamExtractor(private val client: OkHttpClient) {

    // Prevent (automatic) caching the .JS file for different episodes, because it
    // changes everytime, and a cached old .js will have a invalid AES password,
    // invalidating the decryption algorithm.
    // We cache it manually when initializing the class.
    private val newClient = client.newBuilder()
        .cache(null)
        .build()

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, headers: Headers, prefix: String): List<Video> {
        val httpUrl = url.toHttpUrl()
        val host = httpUrl.host
        val id = httpUrl.pathSegments.last()
        val embed = httpUrl.pathSegments.first()

        val newHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", host)
            .build()

        val jsonBody = client.newCall(
            GET("https://rabbitstream.net/ajax/$embed/getSources?id=$id", headers = newHeaders),
        ).execute().body.string()
        val parsed = json.decodeFromString<Source>(jsonBody)

        val key = newClient.newCall(
            GET("https://raw.githubusercontent.com/enimax-anime/key/e4/key.txt"),
        ).execute().body.string()

        val decrypted = decrypt(parsed.sources, key).ifEmpty { return emptyList() }
        val subtitleList = parsed.tracks.map {
            Track(it.file, it.label)
        }

        val files = json.decodeFromString<List<File>>(decrypted)
        return files.flatMap { jsonFile ->
            val videoHeaders = Headers.headersOf(
                "Accept",
                "*/*",
                "Origin",
                "https://$host",
                "Referer",
                "https://$host/",
                "User-Agent",
                headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            )

            val masterPlaylist = client.newCall(
                GET(jsonFile.file, headers = videoHeaders),
            ).execute().body.string()

            val separator = "#EXT-X-STREAM-INF"
            masterPlaylist.substringAfter(separator).split(separator).map {
                val quality = prefix + it.substringAfter("RESOLUTION=")
                    .substringBefore("\n")
                    .substringAfter("x")
                    .substringBefore(",") + "p"
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, headers = videoHeaders, subtitleTracks = subtitleList)
            }
        }
    }

    @Serializable
    data class Source(
        val sources: String,
        val tracks: List<Sub>,
    ) {
        @Serializable
        data class Sub(
            val file: String,
            val label: String,
        )
    }

    @Serializable
    data class File(
        val file: String,
    )
}
