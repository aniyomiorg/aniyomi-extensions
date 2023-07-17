package eu.kanade.tachiyomi.animeextension.en.kickassanime.extractors

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.animeextension.en.kickassanime.dto.VideoDto
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES.decodeHex
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
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
    // Stolen from AniWatch
    // Prevent (automatic) caching the .JS file for different episodes, because it
    // changes everytime, and a cached old .js will have a invalid AES password,
    // invalidating the decryption algorithm.
    // We cache it manually when initializing the class.
    private val cacheControl = CacheControl.Builder().noStore().build()
    private val newClient = client.newBuilder()
        .cache(null)
        .build()

    private val keyMaps by lazy {
        buildMap {
            put("bird", newClient.newCall(GET("https://raw.githubusercontent.com/enimax-anime/kaas/bird/key.txt", cache = cacheControl)).execute().body.string().toByteArray())
            put("duck", newClient.newCall(GET("https://raw.githubusercontent.com/enimax-anime/kaas/duck/key.txt", cache = cacheControl)).execute().body.string().toByteArray())
        }
    }

    private val signaturesMap by lazy {
        newClient.newCall(GET("https://raw.githubusercontent.com/enimax-anime/gogo/main/KAA.json", cache = cacheControl)).execute().parseAs<Map<String, List<String>>>()
    }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val host = url.toHttpUrl().host
        val mid = if (name == "DuckStream") "mid" else "id"
        val isBird = (name == "BirdStream")

        val query = url.toHttpUrl().queryParameter(mid)!!

        val html = client.newCall(GET(url, headers)).execute().body.string()

        val key = when (name) {
            "VidStreaming" -> keyMaps["duck"]!!
            "DuckStream" -> keyMaps["duck"]!!
            "BirdStream" -> keyMaps["bird"]!!
            else -> return emptyList()
        }

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

        val masterPlaylist = client.newCall(GET(videoObject.playlistUrl)).execute()
            .body.string()

        return when {
            videoObject.hls.isBlank() ->
                extractVideosFromDash(masterPlaylist, name, subtitles)
            else -> extractVideosFromHLS(masterPlaylist, name, subtitles, videoObject.playlistUrl)
        }
    }

    private fun getSignature(html: String, server: String, query: String, key: ByteArray): Triple<String, String, String>? {
        val order = when (server) {
            "VidStreaming" -> signaturesMap["vid"]!!
            "DuckStream" -> signaturesMap["duck"]!!
            "BirdStream" -> signaturesMap["bird"]!!
            else -> return null
        }

        val cid = String(html.substringAfter("cid: '").substringBefore("'").decodeHex()).split("|")
        val timeStamp = ((System.currentTimeMillis() / 1000) + 60).toString()
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

    private fun extractVideosFromHLS(playlist: String, prefix: String, subs: List<Track>, playlistUrl: String): List<Video> {
        val separator = "#EXT-X-STREAM-INF"

        val masterBase = "https://${playlistUrl.toHttpUrl().host}${playlistUrl.toHttpUrl().encodedPath}"
            .substringBeforeLast("/") + "/"

        // Get audio tracks
        val audioTracks = AUDIO_REGEX.findAll(playlist).mapNotNull {
            Track(
                getAbsoluteUrl(it.groupValues[2], playlistUrl, masterBase) ?: return@mapNotNull null,
                it.groupValues[1],
            )
        }.toList()

        return playlist.substringAfter(separator).split(separator).mapNotNull {
            val resolution = it.substringAfter("RESOLUTION=")
                .substringBefore("\n")
                .substringAfter("x")
                .substringBefore(",") + "p"

            val videoUrl = it.substringAfter("\n").substringBefore("\n").let { url ->
                getAbsoluteUrl(url, playlistUrl, masterBase)
            } ?: return@mapNotNull null

            Video(videoUrl, "$prefix - $resolution", videoUrl, audioTracks = audioTracks, subtitleTracks = subs)
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

    companion object {
        private val AUDIO_REGEX by lazy { Regex("""#EXT-X-MEDIA:TYPE=AUDIO.*?NAME="(.*?)".*?URI="(.*?)"""") }
    }

    private fun getAbsoluteUrl(url: String, playlistUrl: String, masterBase: String): String? {
        return when {
            url.isEmpty() -> null
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://" + playlistUrl.toHttpUrl().host + url
            else -> masterBase + url
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        return body.string().let(json::decodeFromString)
    }

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
