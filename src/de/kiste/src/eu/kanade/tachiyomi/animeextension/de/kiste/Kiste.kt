package eu.kanade.tachiyomi.animeextension.de.kiste

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class Kiste : ParsedAnimeHttpSource() {

    override val name = "Kiste"

    override val baseUrl = "https://kiste.to"

    override val lang = "de"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/search?sort=imdb:desc&page=$page")

    override fun popularAnimeSelector() = "div.filmlist > div.item > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector() = "li > a[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    override fun latestUpdatesSelector() = "div[data-name=alles] > div.filmlist > div.item > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun getFilterList() = KisteFilters.FILTER_LIST

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = KisteFilters.getSearchParameters(filters)
        val url = buildString {
            append("$baseUrl/search?page=$page")
            if (query.isNotBlank()) append("&keyword=$query")
            with(params) {
                listOf(genres, types, countries, years, qualities)
                    .filter(String::isNotBlank)
                    .forEach { append("&$it") }
            }
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val section = document.selectFirst("section.info")!!
        thumbnail_url = section.selectFirst("img")?.absUrl("src")
        title = section.selectFirst("h1.title")!!.text()
        genre = section.select("span:containsOwn(Genre:) + span > a")
            .eachText()
            .joinToString()
            .takeIf(String::isNotBlank)
        description = section.selectFirst("div.desc")?.text()
    }

    // ============================== Episodes ==============================
    @Serializable data class HtmlData(val html: String)

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val slug = anime.url.substringAfterLast("/")
        val vrf = encryptRC4(slug)
        val newDoc = client.newCall(GET("$baseUrl/ajax/film/servers.php?id=$slug&vrf=$vrf&episode=1-1&token="))
            .execute()
            .use { json.decodeFromString<HtmlData>(it.body.string()).html }
            .let(Jsoup::parse)

        val episodes = newDoc.select(episodeListSelector())
            .map(::episodeFromElement)
            .sortedByDescending { it.episode_number }

        return Observable.just(episodes)
    }

    override fun episodeListParse(response: Response) = throw Exception("not used")

    override fun episodeListSelector() = "div.episode > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val id = element.attr("data-ep").substringAfter("\":\"").substringBefore('"')
        setUrlWithoutDomain(element.attr("href") + "?id=$id")
        val kname = element.attr("data-kname")
        val (seasonNum, epNum) = kname.split("-", limit = 2)
        name = "Staffel $seasonNum - Episode $epNum"
        episode_number = "$seasonNum.${epNum.padStart(3, '0')}".toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val id = episode.url.substringAfter("?id=")
        val headers = headersBuilder()
            .add("Referer", episode.url)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val url = client.newCall(GET("$baseUrl/ajax/episode/info.php?id=$id", headers))
            .execute()
            .use { it.body.string().substringAfter(":\"").substringBefore('"') }
            .let(::decryptRC4)

        val playlistUrl = baseUrl + client.newCall(GET(url, headers)).execute()
            .use { it.body.string() }
            .substringAfter("file: \"", "")
            .substringBefore('"')

        return Observable.just(playlistUtils.extractFromHls(playlistUrl, baseUrl + episode.url))
    }

    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================= Utilities ==============================
    private fun decryptRC4(data: String): String {
        val b64decoded = Base64.decode(data, Base64.DEFAULT)
        val rc4Key = SecretKeySpec(KISTE_KEY, "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.getParameters())
        return cipher.doFinal(b64decoded).toString(Charsets.UTF_8)
    }

    private fun encryptRC4(data: String): String {
        val rc4Key = SecretKeySpec(KISTE_KEY, "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.ENCRYPT_MODE, rc4Key, cipher.getParameters())
        return Base64.encodeToString(cipher.doFinal(data.toByteArray()), Base64.DEFAULT)
    }

    companion object {
        const val PREFIX_SEARCH = "path:"

        private val KISTE_KEY = "DZmuZuXqa9O0z3b7".toByteArray()
    }
}
