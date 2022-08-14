package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers

class EmbedExtractor(private val headers: Headers) {

    private val REGEX_EMBED_PLAYER = Regex("""file: "(\S+)",\s+"label":"(\w+)"""")
    private val PLAYER_NAME = "EmbedPlayer"

    fun getVideoList(url: String, iframeBody: String): List<Video> {
        val hostUrl = url.substringBefore("/embed")
        return REGEX_EMBED_PLAYER.findAll(iframeBody).map {
            val newUrl = "$hostUrl/${it.groupValues[1]}"
            val quality = "$PLAYER_NAME: " + it.groupValues[2]
            Video(newUrl, quality, newUrl, headers)
        }.toList()
    }
}
