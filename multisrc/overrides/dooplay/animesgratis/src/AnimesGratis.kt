package eu.kanade.tachiyomi.animeextension.pt.animesgratis

import eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors.AnimesGratisPlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors.BloggerExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesgratis.extractors.RuplayExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimesGratis : DooPlay(
    "pt-BR",
    "Animes Grátis",
    "https://animesgratis.org",
) {
    override val client by lazy {
        super.client.newBuilder().addInterceptor(VrfInterceptor()).build()
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.imdbRating > article > a"
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/")

    // =============================== Search ===============================
    override fun searchAnimeSelector() = latestUpdatesSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.parallelMap(::getPlayerVideos).flatten()
    }

    override val prefQualityValues = arrayOf("360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    private fun getPlayerVideos(player: Element): List<Video> {
        val name = player.selectFirst("span.title")!!.text().lowercase()
        val url = getPlayerUrl(player)
        return when {
            "ruplay" in name ->
                RuplayExtractor(client).videosFromUrl(url)
            "/player2/" in url ->
                AnimesGratisPlayerExtractor(client).videosFromUrl(url)
            "/player/" in url ->
                BloggerExtractor(client).videosFromUrl(url, headers)
            else -> emptyList<Video>()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/generos/")
    override fun genresListSelector() = "ul.generos li > a"

    // ============================= Utilities ==============================
    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
