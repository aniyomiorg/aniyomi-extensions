package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.VideoDto
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decodeHex
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest

class KickAssAnimeExtractor(
    private val client: OkHttpClient,
    private val json: Json,
    private val headers: Headers,
) {
    fun videosFromUrl(url: String, name: String): List<Video> {
        val host = url.toHttpUrl().host
        val mid = if (name == "DuckStream") "mid" else "id"
        val isBird = name == "BirdStream"

        val query = url.toHttpUrl().queryParameter(mid)!!

        val html = client.newCall(GET(url, headers)).execute().body.string()

        val key = when (name) {
            "VidStreaming" -> "e13d38099bf562e8b9851a652d2043d3"
            "DuckStream" -> "4504447b74641ad972980a6b8ffd7631"
            "BirdStream" -> "4b14d0ff625163e3c9c7a47926484bf2"
            else -> return emptyList()
        }.toByteArray()

        val (sig, timeStamp, route) = getSignature(html, name, query, key) ?: return emptyList()
        val sourceUrl = buildString {
            append("https://")
            append(host)
            append(route)
            append("?$mid=$query")
            if (!isBird) append("&e=$timeStamp")
            append("&s=$sig")
        }

        val request = GET(sourceUrl, headers.newBuilder().add("Referer", url).build())
        val response = client.newCall(request).execute()
            .body.string()

        val (encryptedData, ivhex) = response.substringAfter(":\"")
            .substringBefore('"')
            .replace("\\", "")
            .split(":")

        val iv = ivhex.decodeHex()

        val videoObject = try {
            val decrypted = CryptoAES.decrypt(encryptedData, key, iv)
            json.decodeFromString<VideoDto>(decrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        val subtitles = videoObject.subtitles.map {
            val subUrl: String = it.src.let { src ->
                if (src.startsWith("//")) {
                    "https:$src"
                } else if (src.startsWith("/")) {
                    "https://$host$src"
                } else {
                    src
                }
            }

            val language = "${it.name} (${it.language})"

            Track(subUrl, language)
        }

        fun getVideoHeaders(baseHeaders: Headers, referer: String, videoUrl: String): Headers {
            return baseHeaders.newBuilder().apply {
                add("Accept", "*/*")
                add("Accept-Language", "en-US,en;q=0.5")
                add("Origin", "https://$host")
                add("Sec-Fetch-Dest", "empty")
                add("Sec-Fetch-Mode", "cors")
                add("Sec-Fetch-Site", "cross-site")
            }.build()
        }

        return when {
            videoObject.hls.isBlank() ->
                PlaylistUtils(client, headers).extractFromDash(videoObject.playlistUrl, videoNameGen = { res -> "$name - $res" }, subtitleList = subtitles)
            else -> PlaylistUtils(client, headers).extractFromHls(
                videoObject.playlistUrl,
                videoNameGen = { "$name - $it" },
                videoHeadersGen = ::getVideoHeaders,
                subtitleList = subtitles,
            )
        }
    }

    private fun getSignature(html: String, server: String, query: String, key: ByteArray): Triple<String, String, String>? {
        val order = when (server) {
            "VidStreaming" -> listOf("IP", "USERAGENT", "ROUTE", "MID", "TIMESTAMP", "KEY")
            "DuckStream" -> listOf("IP", "USERAGENT", "ROUTE", "MID", "TIMESTAMP", "KEY")
            "BirdStream" -> listOf("IP", "USERAGENT", "ROUTE", "MID", "KEY")
            else -> return null
        }

        val cid = String(html.substringAfter("cid: '").substringBefore("'").decodeHex()).split("|")
        val timeStamp = (System.currentTimeMillis() / 1000 + 60).toString()
        val route = cid[1].replace("player.php", "source.php")

        val signature = buildString {
            order.forEach {
                when (it) {
                    "IP" -> append(cid[0])
                    "USERAGENT" -> append(headers["User-Agent"] ?: "")
                    "ROUTE" -> append(route)
                    "MID" -> append(query)
                    "TIMESTAMP" -> append(timeStamp)
                    "KEY" -> append(String(key))
                    "SIG" -> append(html.substringAfter("signature: '").substringBefore("'"))
                    else -> {}
                }
            }
        }

        return Triple(sha1sum(signature), timeStamp, route)
    }

    private fun sha1sum(value: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(value.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            throw Exception("Attempt to create the signature failed miserably.")
        }
    }

    // ============================= Utilities ==============================
}
