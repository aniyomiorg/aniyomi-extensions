package eu.kanade.tachiyomi.animeextension.pt.animestc.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.pt.animestc.dto.VideoDto.VideoLink
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class LinkBypasser(
    private val client: OkHttpClient,
    private val json: Json,
) {
    fun bypass(video: VideoLink, episodeId: Int): String? {
        val joined = "$episodeId/${video.quality}/${video.index}"
        val encoded = Base64.encodeToString(joined.toByteArray(), Base64.NO_WRAP)
        val url = "$PROTECTOR_URL/link/$encoded"
        val res = client.newCall(GET(url)).execute()
        if (res.code != 200) {
            return null
        }

        // Sadly we MUST wait 6s or we are going to get a HTTP 500
        Thread.sleep(6000L)
        val id = res.asJsoup().selectFirst("meta#link-id")!!.attr("value")
        val apiCall = client.newCall(GET("$PROTECTOR_URL/api/link/$id")).execute()
        if (apiCall.code != 200) {
            return null
        }

        val apiBody = apiCall.body.string()
        return json.decodeFromString<LinkDto>(apiBody).link
    }

    @Serializable
    data class LinkDto(val link: String)

    companion object {
        private const val PROTECTOR_URL = "https://protetor.animestc.xyz"
    }
}
