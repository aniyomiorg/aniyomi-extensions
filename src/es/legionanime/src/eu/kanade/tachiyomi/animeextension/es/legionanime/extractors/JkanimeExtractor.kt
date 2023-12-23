package eu.kanade.tachiyomi.animeextension.es.legionanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class JkanimeExtractor(
    private val client: OkHttpClient,
) {

    fun getNozomiFromUrl(url: String, prefix: String = ""): Video? {
        val dataKeyHeaders = Headers.Builder().add("Referer", url).build()
        val doc = client.newCall(GET(url, dataKeyHeaders)).execute().asJsoup()
        val dataKey = doc.select("form input[value]").attr("value")

        val gsplayBody = "data=$dataKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

        val location = client.newCall(POST("https://jkanime.net/gsplay/redirect_post.php", dataKeyHeaders, gsplayBody)).execute().request.url.toString()
        val postKey = location.substringAfter("player.html#")

        val nozomiBody = "v=$postKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val nozomiResponse = client.newCall(POST("https://jkanime.net/gsplay/api.php", body = nozomiBody)).execute()
        val nozomiUrl = JSONObject(nozomiResponse.body.string()).getString("file")
        if (nozomiResponse.isSuccessful && nozomiUrl.isNotBlank()) {
            return Video(nozomiUrl, "${prefix}Nozomi", nozomiUrl)
        }
        return null
    }

    fun getDesuFromUrl(url: String, prefix: String = ""): Video? {
        val document = client.newCall(GET(url)).execute()
        val script = document.asJsoup().selectFirst("script:containsData(var parts = {)")!!.data()
        val streamUrl = script.substringAfter("url: '").substringBefore("'")
        if (document.isSuccessful && streamUrl.isNotBlank()) {
            return Video(streamUrl, "${prefix}Desu", streamUrl)
        }
        return null
    }

    fun amazonExtractor(url: String): String {
        val document = client.newCall(GET(url.replace(".com", ".tv"))).execute().asJsoup()
        val videoURl = document.selectFirst("script:containsData(sources: [)")!!.data()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")
        return try {
            if (client.newCall(GET(videoURl)).execute().code == 200) videoURl else ""
        } catch (_: Exception) {
            ""
        }
    }
}
