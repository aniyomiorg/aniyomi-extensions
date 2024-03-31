package eu.kanade.tachiyomi.animeextension.fr.anisama.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.commonEmptyHeaders

class VidCdnExtractor(
    private val client: OkHttpClient,
    headers: Headers = commonEmptyHeaders,
) {

    private val headers = headers.newBuilder()
        .set("Referer", "https://msb.toonanime.xyz")
        .build()

    @Serializable
    data class CdnSourceDto(val file: String)

    @Serializable
    data class CdnResponseDto(val sources: List<CdnSourceDto>)

    fun videosFromUrl(
        url: String,
        videoNameGen: (String) -> String = { quality -> quality },
    ): List<Video> {
        val httpUrl = url.toHttpUrl()
        val source = when {
            url.contains("embeds.html") -> Pair("sib2", "Sibnet")
            // their sendvid server is currently borken lmao
            // url.contains("embedsen.html") -> Pair("azz", "Sendvid")
            else -> return emptyList()
        }
        val id = httpUrl.queryParameter("id")
        val epid = httpUrl.queryParameter("epid")
        val cdnUrl = "https://cdn2.vidcdn.xyz/${source.first}/$id?epid=$epid"
        val res = client.newCall(GET(cdnUrl, headers)).execute().parseAs<CdnResponseDto>()
        return res.sources.map {
            val file = if (it.file.startsWith("http")) it.file else "https:${it.file}"
            Video(
                file,
                videoNameGen(source.second),
                file,
            )
        }
    }
}
