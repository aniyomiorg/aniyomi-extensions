package eu.kanade.tachiyomi.animeextension.sr.animebalkan

import eu.kanade.tachiyomi.animeextension.sr.animebalkan.extractors.MailRuExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeBalkan : AnimeStream(
    "sr",
    "AnimeBalkan",
    "https://animebalkan.org",
) {
    override val animeListUrl = "$baseUrl/animesaprevodom"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale("bs")) // YES, Bosnian
    }

    // ============================ Video Links =============================
    override fun getHosterUrl(element: Element): String {
        if (element.text().contains("Server AB")) {
            return element.attr("value")
        }

        return super.getHosterUrl(element)
    }

    private val gdriveExtractor by lazy { GoogleDriveExtractor(client, headers) }
    private val mailruExtractor by lazy { MailRuExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when {
            "Server OK" in name || "ok.ru" in url -> okruExtractor.videosFromUrl(url)
            "Server Ru" in name || "mail.ru" in url -> mailruExtractor.videosFromUrl(url)
            "Server GD" in name || "google.com" in url -> {
                // We need to do that bc the googledrive extractor is garbage.
                val newUrl = when {
                    url.contains("uc?id=") -> url
                    else -> {
                        val id = url.substringAfter("/d/").substringBefore("/")
                        "https://drive.google.com/uc?id=$id"
                    }
                }
                gdriveExtractor.videosFromUrl(newUrl)
            }
            "Server AB" in name && baseUrl in url -> {
                val doc = client.newCall(GET(url)).execute().asJsoup()
                val videoUrl = doc.selectFirst("source")?.attr("src")
                    ?: return emptyList()
                listOf(Video(videoUrl, "Server AB - Default", videoUrl))
            }
            else -> emptyList()
        }
    }

    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    override val prefQualityEntries = prefQualityValues
}
