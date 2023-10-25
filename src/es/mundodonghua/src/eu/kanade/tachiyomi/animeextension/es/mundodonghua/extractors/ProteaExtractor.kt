package eu.kanade.tachiyomi.animeextension.es.mundodonghua.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import org.jsoup.Connection
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class ProteaExtractor() {
    private val json: Json by injectLazy()
    fun videosFromUrl(url: String, qualityPrefix: String = "Protea", headers: Headers): List<Video> {
        val videoList = mutableListOf<Video>()
        runCatching {
            val document = Jsoup.connect(url).headers(headers.toMap()).ignoreContentType(true).method(Connection.Method.POST).execute()
            if (document!!.body()!!.isNotEmpty()) {
                val responseString = document.body().removePrefix("[").removeSuffix("]")
                val jObject = json.decodeFromString<JsonObject>(responseString)
                val sources = jObject["source"]!!.jsonArray
                sources!!.forEach { source ->
                    var item = source!!.jsonObject
                    var quality = "$qualityPrefix:${ item["label"]!!.jsonPrimitive.content }"
                    var urlVideo = item["file"]!!.jsonPrimitive!!.content.removePrefix("//")
                    var newHeaders = Headers.Builder()
                        .set("authority", "www.nemonicplayer.xyz")
                        .set("accept", "*/*")
                        .set("accept-language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                        .set("dnt", "1")
                        .set("referer", "https://www.mundodonghua.com/")
                        .set("sec-ch-ua", "\"Chromium\";v=\"104\", \" Not A;Brand\";v=\"99\", \"Google Chrome\";v=\"104\"")
                        .set("sec-ch-ua-mobile", "?0")
                        .set("sec-ch-ua-platform", "\"Windows\"")
                        .set("sec-fetch-mode", "no-cors")
                        .set("sec-fetch-dest", "video")
                        .set("sec-fetch-site", "cross-site")
                        .set("sec-gpc", "1")
                        .set("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.0.0 Safari/537.36")
                        .build()
                    videoList.add(Video("https://$urlVideo", quality, "https://$urlVideo", headers = newHeaders))
                }
            }
        }
        return videoList
    }
}
