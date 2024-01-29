package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class McpExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val regexEpId = Regex("ss,\"(\\d+)\"")
    private val regexVideoUrl = Regex("h\":\"(\\S+?)\"")
    private val apiUrl = "https://clp-new.animeshouse.net/ah-clp-new"

    fun getVideoList(js: String): List<Video> {
        val epId = regexEpId.find(js)!!.groupValues[1]
        val videoUrl = client.newCall(GET("$apiUrl/s_control.php?mid=$epId", headers))
            .execute()
            .let { req ->
                val reqBody = req.body.string()
                regexVideoUrl.find(reqBody)!!.groupValues
                    .get(1)
                    .replace("\\", "")
            }

        return listOf(Video(videoUrl, "default_mcp", videoUrl, headers))
    }
}
