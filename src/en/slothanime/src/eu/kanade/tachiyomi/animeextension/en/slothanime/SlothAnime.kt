package eu.kanade.tachiyomi.animeextension.en.slothanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

class SlothAnime : ParsedAnimeHttpSource() {

    override val name = "SlothAnime"

    override val baseUrl = "https://slothanime.com"

    override val lang = "en"

    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl/list/viewed?page=$page"
        } else {
            "$baseUrl/list/viewed"
        }

        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = ".row > div > .anime-card-md"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")!!.imgAttr()
        with(element.selectFirst("a[href~=/anime]")!!) {
            title = text()
            setUrlWithoutDomain(attr("abs:href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = ".pagination > .active ~ li:has(a)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) {
            "$baseUrl/list/latest?page=$page"
        } else {
            "$baseUrl/list/latest"
        }

        return GET(url, headers)
    }
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().first()
        val typeFilter = filters.filterIsInstance<TypeFilter>().first()
        val statusFilter = filters.filterIsInstance<StatusFilter>().first()
        val sortFilter = filters.filterIsInstance<SortFilter>().first()

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addQueryParameter("q", query)
            genreFilter.getIncluded().forEachIndexed { idx, value ->
                addQueryParameter("genre[$idx]", value)
            }
            typeFilter.getValues().forEachIndexed { idx, value ->
                addQueryParameter("type[$idx]", value)
            }
            addQueryParameter("status", statusFilter.getValue())
            addQueryParameter("sort", sortFilter.getValue())
            genreFilter.getExcluded().forEachIndexed { idx, value ->
                addQueryParameter("ignore_genre[$idx]", value)
            }

            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst(".single-title > *:not(.single-altername)")!!.text()
        thumbnail_url = document.selectFirst(".single-cover > img")!!.imgAttr()
        description = document.selectFirst(".single-detail:has(span:contains(Description)) .more-content")?.text()
        genre = document.select(".single-tag > a.tag").joinToString { it.text() }
        author = document.select(".single-detail:has(span:contains(Studios)) .value a").joinToString { it.text() }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = ".list-episodes-container > a[class~=episode]"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        name = element.text()
            .replace(Regex("""^EP """), "Episode ")
            .replace(Regex("""^\d+""")) { m -> "Episode ${m.value}" }
    }

    // ============================ Video Links =============================

    fun encryptAES(input: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivParameterSpec = IvParameterSpec(iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivParameterSpec)
        val paddedInput = zeroPad(input)
        val encryptedBytes = cipher.doFinal(paddedInput.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    fun zeroPad(input: String): String {
        val blockSize = 16
        val padLength = blockSize - input.length % blockSize
        return input.padEnd(input.length + padLength, '\u0000')
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val key = String(Base64.decode(KEY, Base64.DEFAULT)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val iv = String(Base64.decode(IV, Base64.DEFAULT)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val time = floor(System.currentTimeMillis() / 1000.0)
        val vrf = encryptAES(time.toString(), key, iv)
        val id = episode.url.substringAfterLast("/")

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("player-url")
            addPathSegment(id)
            addQueryParameter("vrf", vrf)
        }.build().toString()

        val videoHeaders = headersBuilder().apply {
            add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            add("Referer", baseUrl + episode.url)
        }.build()

        return listOf(
            Video(url, "Video", url, videoHeaders),
        )
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    companion object {
        private const val KEY = "YWI0OWZkYjllYzE5M2I0YWQzYWFkMGVmMTU4N2Q2OGE0YmYxY2Y5YjJkMjA4YjRjYzIzMDYwZTkwNThiMjA0NA=="
        private const val IV = "NDI4MzEzNjcxMThiMzFmYjVhNTI1MTMzNTc0ZmJmNGI="
    }
}
