package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class McpExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val REGEX_EP_ID = Regex("ss,\"(\\d+)\"")
    private val REGEX_VIDEO_URL = Regex("h\":\"(\\S+?)\"")
    private val API_URL = "https://clp-new.animeshouse.net/ah-clp-new"

    fun getVideoList(js: String): List<Video> {
        val epId = REGEX_EP_ID.find(js)!!.groupValues[1]
        val videoUrl = client.newCall(GET("$API_URL/s_control.php?mid=$epId", headers))
            .execute()
            .use { req ->
                val reqBody = req.body.string()
                REGEX_VIDEO_URL.find(reqBody)!!.groupValues
                    .get(1)
                    .replace("\\", "")
            }

        return listOf(Video(videoUrl, "default_mcp", videoUrl, headers))
    }
}
