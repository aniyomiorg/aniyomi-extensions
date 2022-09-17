package eu.kanade.tachiyomi.animeextension.es.animeyt.extractors

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class FastreamExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()
    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    fun videoFromUrl(url: String, server: String = "Fastream"): List<Video> {
        val videoList = mutableListOf<Video>()
        try {
            val document = client.newCall(GET(url)).execute()
            if (document.isSuccessful) {
                val content = document!!.asJsoup()
                content!!.select("script").forEach {
                    if (it!!.data()!!.contains("jwplayer(jwplayer(\"vplayer\").setup({")) {
                        val basicUrl = it!!.data().substringAfter("file: '").substringBefore("',")
                        videoList.add(Video(basicUrl, server, basicUrl, headers = null))
                    } else {
                        val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
                        val qualities = listOf(
                            Pair("Low", "360p"),
                            Pair("Normal", "480p"),
                            Pair("HD", "720p"),
                            Pair("Full", "1080p"),
                        )
                        packedRegex.findAll(it!!.data()).map { packed -> packed.value }.toList().map { eval ->
                            val fastreamRegex = "fastream.*?\\.m3u8([^&\">]?)".toRegex()
                            val unpack = JsUnpacker.unpack(eval)
                            fetchUrls(unpack!!.first()).map { url ->
                                if (fastreamRegex.containsMatchIn(url)) {
                                    val urlQualities = url.split(",").filter { p -> !p.contains("m3u8") }
                                    val baseUrl = urlQualities.first()
                                    val jsonQualities = "{ \"qualityLabels\": { ${unpack.first().substringAfter("\\'qualityLabels\\':{").substringBefore("},")} }}"
                                    val jObject = json.decodeFromString<JsonObject>(jsonQualities)
                                    val jQualities = jObject["qualityLabels"]!!.jsonObject.map { jsonElement ->
                                        val jQuality = jsonElement.value.toString().replace("\"", "")
                                        qualities.find { q -> q.first.contains(jQuality) }?.second
                                    }.toTypedArray()
                                    Log.i("bruh jQuality join", jQualities.joinToString())
                                    var qualityIdx = 0
                                    urlQualities.map { _url ->
                                        if (!_url.contains("http")) {
                                            Log.i("bruh _url", "$_url $qualityIdx")
                                            val quality = "$server:${jQualities[qualityIdx]}"
                                            Log.i("bruh quality", quality)
                                            val videoUrl = "$baseUrl$_url/master.m3u8"
                                            Log.i("bruh videoUrl", videoUrl)
                                            qualityIdx++
                                            videoList.add(Video(videoUrl, quality, videoUrl, headers = null))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return videoList
    }
}
