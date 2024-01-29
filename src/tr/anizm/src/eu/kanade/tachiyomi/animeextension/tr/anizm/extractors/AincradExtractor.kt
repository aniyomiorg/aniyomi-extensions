package eu.kanade.tachiyomi.animeextension.tr.anizm.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class AincradExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val json: Json,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String): List<Video> {
        val hash = url.substringAfterLast("video/").substringBefore("/")
        val body = FormBody.Builder()
            .add("hash", hash)
            .add("r", "https://anizm.net/")
            .build()

        val headers = headers.newBuilder()
            .set("Origin", DOMAIN)
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val req = POST("$DOMAIN/player/index.php?data=$hash&do=getVideo", headers, body)
        val res = client.newCall(req).execute().body.string()
        return runCatching {
            val data = json.decodeFromString<ResponseDto>(res)
            playlistUtils.extractFromHls(
                data.securedLink!!,
                referer = url,
                videoNameGen = { "Aincrad - $it" },
            )
        }.getOrElse { emptyList() }
    }

    @Serializable
    data class ResponseDto(val securedLink: String?)

    companion object {
        private const val DOMAIN = "https://anizmplayer.com"
    }
}
