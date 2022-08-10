package eu.kanade.tachiyomi.animeextension.es.animeyt.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import java.io.IOException

class FastreamExtractor(private val client: OkHttpClient) {
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
                        packedRegex.findAll(it!!.data()).map { packed -> packed.value }.toList().map { eval ->
                            val unpack = JsUnpacker.unpack(eval)
                            fetchUrls(unpack!!.first()).map { url ->
                                val fastreamRegex = "fastream.*?\\.m3u8([^&\">]?)".toRegex()
                                if (fastreamRegex.containsMatchIn(url)) {
                                    videoList.add(Video(url, server, url, headers = null))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
        }
        return videoList
    }
}
