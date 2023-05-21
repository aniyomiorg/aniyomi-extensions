package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.VideoDto
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decodeHex
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.text.CharacterIterator
import java.text.StringCharacterIterator

class KickAssAnimeExtractor(
    private val client: OkHttpClient,
    private val json: Json,
    private val headers: Headers,
) {

    fun videosFromUrl(url: String): List<Video> {
        val query = url.substringAfterLast("?")
        val baseUrl = url.substringBeforeLast("/") // baseUrl + endpoint/player

        val html = client.newCall(GET(url, headers)).execute().body.string()

        val prefix = if ("pink" in url) "PinkBird" else "SapphireDuck"
        val key = AESKeyExtractor.KEY_MAP.get(prefix)
            ?: AESKeyExtractor(client).getKeyFromHtml(baseUrl, html, prefix)

        val request = sourcesRequest(baseUrl, url, html, query, key)

        val response = client.newCall(request).execute()
            .body.string()
            .ifEmpty { // Http 403 moment
                val newkey = AESKeyExtractor(client).getKeyFromUrl(url, prefix)
                sourcesRequest(baseUrl, url, html, query, newkey)
                    .let(client::newCall).execute()
                    .body.string()
            }

        val (encryptedData, ivhex) = response.substringAfter(":\"")
            .substringBefore('"')
            .replace("\\", "")
            .split(":")

        val iv = ivhex.decodeHex()

        val videoObject = try {
            val decrypted = CryptoAES.decrypt(encryptedData, key, iv)
                .ifEmpty { // Maybe the key did change.. AGAIN.
                    val newkey = AESKeyExtractor(client).getKeyFromUrl(url, prefix)
                    CryptoAES.decrypt(encryptedData, newkey, iv)
                }
            json.decodeFromString<VideoDto>(decrypted)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        val subtitles = videoObject.subtitles.map {
            val subUrl: String = it.src.let { src ->
                if (src.startsWith("/")) {
                    baseUrl.substringBeforeLast("/") + "/$src"
                } else {
                    src
                }
            }

            val language = "${it.name} (${it.language})"

            Track(subUrl, language)
        }

        val masterPlaylist = client.newCall(GET(videoObject.playlistUrl)).execute()
            .body.string()

        return when {
            videoObject.hls.isBlank() ->
                extractVideosFromDash(masterPlaylist, prefix, subtitles)
            else -> extractVideosFromHLS(masterPlaylist, prefix, subtitles)
        }
    }

    private fun sourcesRequest(baseUrl: String, url: String, html: String, query: String, key: ByteArray): Request {
        val timestamp = ((System.currentTimeMillis() / 1000) + 60).toString()
        val cid = html.substringAfter("cid: '").substringBefore("'").decodeHex()
        val ip = String(cid).substringBefore("|")
        val path = "/" + baseUrl.substringAfterLast("/") + "/source.php"
        val userAgent = headers.get("User-Agent") ?: ""
        val localHeaders = Headers.headersOf("User-Agent", userAgent, "referer", url)
        val idQuery = query.substringAfter("=")
        val items = listOf(timestamp, ip, userAgent, path.replace("player", "source"), idQuery, String(key))
        val signature = sha1sum(items.joinToString(""))

        return GET("$baseUrl/source.php?$query&e=$timestamp&s=$signature", localHeaders)
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

    private fun extractVideosFromHLS(playlist: String, prefix: String, subs: List<Track>): List<Video> {
        val separator = "#EXT-X-STREAM-INF"
        return playlist.substringAfter(separator).split(separator).map {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringBefore("\n")
                .substringAfter("x")
                .substringBefore(",") + "p"

            val videoUrl = it.substringAfter("\n").substringBefore("\n")

            Video(videoUrl, "$prefix - $resolution", videoUrl, subtitleTracks = subs)
        }
    }

    private fun extractVideosFromDash(playlist: String, prefix: String, subs: List<Track>): List<Video> {
        // Parsing dash with Jsoup :YEP:
        val document = Jsoup.parse(playlist)
        val audioList = document.select("Representation[mimetype~=audio]").map { audioSrc ->
            Track(audioSrc.text(), audioSrc.formatBits() ?: "audio")
        }
        return document.select("Representation[mimetype~=video]").map { videoSrc ->
            Video(
                videoSrc.text(),
                "$prefix - ${videoSrc.attr("height")}p - ${videoSrc.formatBits()}",
                videoSrc.text(),
                audioTracks = audioList,
                subtitleTracks = subs,
            )
        }
    }

    // ============================= Utilities ==============================

    @SuppressLint("DefaultLocale")
    private fun Element.formatBits(attribute: String = "bandwidth"): String? {
        var bits = attr(attribute).toLongOrNull() ?: 0L
        if (-1000 < bits && bits < 1000) {
            return "${bits}b"
        }
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bits <= -999950 || bits >= 999950) {
            bits /= 1000
            ci.next()
        }
        return java.lang.String.format("%.2f%cbs", bits / 1000.0, ci.current())
    }
}
