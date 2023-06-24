package eu.kanade.tachiyomi.animeextension.id.minioppai

import eu.kanade.tachiyomi.animeextension.id.minioppai.extractors.MiniOppaiExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MiniOppai : AnimeStream(
    "id",
    "MiniOppai",
    "https://minioppai.org",
) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    override val animeListUrl = "$baseUrl/advanced-search"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale(lang))
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.epsdlist > ul > li > a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            element.selectFirst(".epl-num")!!.text().let {
                val num = it.substringAfterLast(" ")
                episode_number = num.toFloatOrNull() ?: 0F
                name = when {
                    it.contains("OVA", true) -> "OVA $num"
                    else -> "Episode $num"
                }
            }
            element.selectFirst(".epl-sub")?.text()?.let { scanlator = it }
            date_upload = element.selectFirst(".epl-date")?.text().toDate()
        }
    }

    // ============================ Video Links =============================
    override fun getVideoList(url: String, name: String): List<Video> {
        return when {
            "gdriveplayer" in url -> {
                val playerUrl = buildString {
                    val data = url.toHttpUrl().queryParameter("data")
                        ?: return emptyList()
                    if (data.startsWith("//")) append("https:")
                    append(data)
                }
                GdrivePlayerExtractor(client).videosFromUrl(playerUrl, name, headers)
            }
            "paistream.my.id" in url ->
                MiniOppaiExtractor(client).videosFromUrl(url, headers)
            else -> emptyList()
        }
    }

    // =========================== Anime Details ============================
    override fun getAnimeDescription(document: Document) =
        document.select("div.entry-content > p").eachText().joinToString("\n")

    // =============================== Search ===============================
    override fun searchAnimeSelector() = "div.latest article a.tip"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.selectFirst("h2.entry-title")!!.ownText()
            thumbnail_url = element.selectFirst("img")!!.getImageUrl()
        }
    }

    // ============================== Filters ===============================
    override val fetchFilters = false

    // ============================= Utilities ==============================
    override fun Element.getInfo(text: String): String? {
        return selectFirst("li:has(b:contains($text))")
            ?.selectFirst("span.colspan")
            ?.text()
    }
}
