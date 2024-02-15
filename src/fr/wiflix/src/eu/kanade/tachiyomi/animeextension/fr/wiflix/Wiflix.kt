package eu.kanade.tachiyomi.animeextension.fr.wiflix

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamdavextractor.StreamDavExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidoextractor.VidoExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.multisrc.datalifeengine.DataLifeEngine
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Wiflix : DataLifeEngine(
    "Wiflix",
    "https://wiflix.voto",
    "fr",
) {

    override val categories = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Séries", "/serie-en-streaming/"),
        Pair("Films", "/film-en-streaming/"),
    )

    override val genres = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Action", "/film-en-streaming/action/"),
        Pair("Animation", "/film-en-streaming/animation/"),
        Pair("Arts Martiaux", "/film-en-streaming/arts-martiaux/"),
        Pair("Aventure", "/film-en-streaming/aventure/"),
        Pair("Biopic", "/film-en-streaming/biopic/"),
        Pair("Comédie", "/film-en-streaming/comedie/"),
        Pair("Comédie Dramatique", "/film-en-streaming/comedie-dramatique/"),
        Pair("Épouvante Horreur", "/film-en-streaming/horreur/"),
        Pair("Drame", "/film-en-streaming/drame/"),
        Pair("Documentaire", "/film-en-streaming/documentaire/"),
        Pair("Espionnage", "/film-en-streaming/espionnage/"),
        Pair("Famille", "/film-en-streaming/famille/"),
        Pair("Fantastique", "/film-en-streaming/fantastique/"),
        Pair("Guerre", "/film-en-streaming/guerre/"),
        Pair("Historique", "/film-en-streaming/historique/"),
        Pair("Musical", "/film-en-streaming/musical/"),
        Pair("Policier", "/film-en-streaming/policier/"),
        Pair("Romance", "/film-en-streaming/romance/"),
        Pair("Science-Fiction", "/film-en-streaming/science-fiction/"),
        Pair("Spectacles", "/film-en-streaming/spectacles/"),
        Pair("Thriller", "/film-en-streaming/thriller/"),
        Pair("Western", "/film-en-streaming/western/"),
    )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/serie-en-streaming/page/$page/")

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = ".hostsblock div:has(a[href*=https])"

    override fun episodeListParse(response: Response): List<SEpisode> = super.episodeListParse(response).sort()

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        episode_number = element.className().filter { it.isDigit() }.toFloat()
        name = "Episode ${episode_number.toInt()}"
        scanlator = if (element.className().contains("vf")) "VF" else "VOSTFR"
        url = element.select("a").joinToString(",") { it.attr("href").removePrefix("/vd.php?u=") }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val list = episode.url.split(",").filter { it.isNotBlank() }.parallelCatchingFlatMap {
            with(it) {
                when {
                    contains("doods.pro") -> DoodExtractor(client).videosFromUrl(this)
                    contains("vido.lol") -> VidoExtractor(client).videosFromUrl(this)
                    contains("uqload.co") -> UqloadExtractor(client).videosFromUrl(this)
                    contains("waaw1.tv") -> emptyList()
                    contains("vudeo.co") -> VudeoExtractor(client).videosFromUrl(this)
                    contains("streamvid.net") -> StreamHideVidExtractor(client).videosFromUrl(this)
                    contains("upstream.to") -> UpstreamExtractor(client).videosFromUrl(this)
                    contains("streamdav.com") -> StreamDavExtractor(client).videosFromUrl(this)
                    contains("voe.sx") -> VoeExtractor(client).videosFromUrl(this)
                    else -> emptyList()
                }
            }
        }.sort()
        if (list.isEmpty()) throw Exception("no player found")
        return list
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
}
