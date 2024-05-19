package eu.kanade.tachiyomi.animeextension.es.jkanime.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class JkanimeExtractor(
    private val client: OkHttpClient,
) {

    fun getNozomiFromUrl(url: String, prefix: String = ""): List<Video> {
        val dataKeyHeaders = Headers.Builder().add("Referer", url).build()
        val doc = client.newCall(GET(url, dataKeyHeaders)).execute().asJsoup()
        val dataKey = doc.select("form input[value]").attr("value")

        val gsplayBody = "data=$dataKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

        val location = client.newCall(POST("https://jkanime.net/gsplay/redirect_post.php", dataKeyHeaders, gsplayBody)).execute().request.url.toString()
        val postKey = location.substringAfter("player.html#")

        val nozomiBody = "v=$postKey".toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        val nozomiResponse = client.newCall(POST("https://jkanime.net/gsplay/api.php", body = nozomiBody)).execute()
        val nozomiUrl = nozomiResponse.body.string().parseAs<NozomiResponse>().file ?: return emptyList()

        return listOf(Video(nozomiUrl, "${prefix}Nozomi", nozomiUrl))
    }

    fun getDesuFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute()
        val streamUrl = document.asJsoup()
            .selectFirst("script:containsData(var parts = {)")
            ?.data()?.substringAfter("url: '")
            ?.substringBefore("'") ?: return emptyList()

        return listOf(Video(streamUrl, "${prefix}Desu", streamUrl))
    }

    fun getDesukaFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute()
        val contentType = document.header("Content-Type") ?: ""

        if (contentType.startsWith("video/")) {
            val realUrl = document.networkResponse.toString()
                .substringAfter("url=")
                .substringBefore("}")
            return listOf(Video(realUrl, "${prefix}Desuka", realUrl))
        }

        val streamUrl = document.asJsoup()
            .selectFirst("script:containsData(new DPlayer({)")
            ?.data()?.substringAfter("url: '")
            ?.substringBefore("'") ?: return emptyList()

        return listOf(Video(streamUrl, "${prefix}Desuka", streamUrl))
    }

    @Serializable
    data class NozomiResponse(val file: String? = null)
}
