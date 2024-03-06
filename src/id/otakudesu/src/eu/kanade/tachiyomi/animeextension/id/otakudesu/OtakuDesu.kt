package eu.kanade.tachiyomi.animeextension.id.otakudesu

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapNotNullBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class OtakuDesu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "OtakuDesu"

    override val baseUrl = "https://otakudesu.cloud"

    override val lang = "id"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val info = document.selectFirst("div.infozingle")!!
            title = info.getInfo("Judul") ?: ""
            genre = info.getInfo("Genre")
            status = parseStatus(info.getInfo("Status"))
            artist = info.getInfo("Studio")
            author = info.getInfo("Produser")

            description = buildString {
                info.getInfo("Japanese", false)?.also { append("$it\n") }
                info.getInfo("Skor", false)?.also { append("$it\n") }
                info.getInfo("Total Episode", false)?.also { append("$it\n") }
                append("\n\nSynopsis:\n")
                document.select("div.sinopc > p").eachText().forEach { append("$it\n\n") }
            }
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    private val nameRegex by lazy { ".+?(?=Episode)|\\sSubtitle.+".toRegex() }
    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val link = element.selectFirst("span > a")!!
            val text = link.text()
            episode_number = text.substringAfter("Episode ")
                .substringBefore(" ")
                .toFloatOrNull() ?: 1F
            setUrlWithoutDomain(link.attr("href"))
            name = text.replace(nameRegex, "")
            date_upload = element.selectFirst("span.zeebr")?.text().toDate()
        }
    }

    override fun episodeListSelector() = "#venkonten > div.venser > div:nth-child(8) > ul > li"

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            title = element.selectFirst("h2")!!.text()
        }
    }

    override fun latestUpdatesNextPageSelector() = "a.next.page-numbers"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ongoing-anime/page/$page")

    override fun latestUpdatesSelector() = "div.detpost div.thumb > a"

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/complete-anime/page/$page")
    override fun popularAnimeSelector() = latestUpdatesSelector()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    private fun searchAnimeFromElement(element: Element, ui: String): SAnime {
        return SAnime.create().apply {
            when (ui) {
                "search" -> {
                    val link = element.selectFirst("h2 > a")!!
                    setUrlWithoutDomain(link.attr("href"))
                    title = link.text().replace(" Subtitle Indonesia", "")
                    thumbnail_url = element.selectFirst("img")!!.attr("src")
                }
                else -> {
                    val link = element.selectFirst(".col-anime-title > a")!!
                    setUrlWithoutDomain(link.attr("href"))
                    title = link.text()
                    thumbnail_url = element.selectFirst(".col-anime-cover > img")!!.attr("src")
                }
            }
        }
    }

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query&post_type=anime")
            genreFilter.state != 0 -> GET("$baseUrl/genres/${genreFilter.toUriPart()}/page/$page")
            else -> GET("$baseUrl/complete-anime/page/$page")
        }
    }

    override fun searchAnimeSelector() = "#venkonten > div > div.venser > div > div > ul > li"
    private val genreSelector = ".col-anime"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val ui = when {
            document.selectFirst(genreSelector) == null -> "search"
            document.selectFirst(searchAnimeSelector()) == null -> "genres"
            else -> "unknown"
        }

        val animes = when (ui) {
            "genres" -> document.select(genreSelector).map { searchAnimeFromElement(it, ui) }
            "search" -> document.select(searchAnimeSelector()).map { searchAnimeFromElement(it, ui) }
            else -> document.select(latestUpdatesSelector()).map(::latestUpdatesFromElement)
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null

        return AnimesPage(animes, hasNextPage)
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "div.mirrorstream ul li > a"

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val script = doc.selectFirst("script:containsData({action:)")!!
            .data()

        val nonceAction = script.substringAfter("{action:\"").substringBefore('"')
        val action = script.substringAfter("action:\"").substringBefore('"')

        val nonce = getNonce(nonceAction)

        return doc.select(videoListSelector())
            .parallelMapNotNullBlocking {
                runCatching { getEmbedLinks(it, action, nonce) }.getOrNull()
            }
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.first, it.second)
            }
    }

    private fun getEmbedLinks(element: Element, action: String, nonce: String): Pair<String, String> {
        val decodedData = element.attr("data-content").b64Decode()
            .drop(1)
            .dropLast(1)

        val (id, mirror, quality) = decodedData.split(",").map {
            it.substringAfter(":").replace("\"", "")
        }

        val form = FormBody.Builder().apply {
            add("id", id)
            add("i", mirror)
            add("q", quality)
            add("nonce", nonce)
            add("action", action)
        }.build()

        val doc = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", body = form))
            .execute()
            .body.string()
            .substringAfter(":\"")
            .substringBefore('"')
            .b64Decode()
            .let(Jsoup::parse)

        val url = doc.selectFirst("iframe")!!.attr("src")

        return Pair(quality, url)
    }

    private val filelionsExtractor by lazy { StreamWishExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    private fun getVideosFromEmbed(quality: String, link: String): List<Video> {
        return when {
            "filelions" in link -> {
                filelionsExtractor.videosFromUrl(link, videoNameGen = { "FileLions - $it" })
            }
            "yourupload" in link -> {
                val id = link.substringAfter("id=").substringBefore("&")
                val url = "https://yourupload.com/embed/$id"
                yourUploadExtractor.videoFromUrl(url, headers, "YourUpload - $quality")
            }
            "desustream" in link -> {
                client.newCall(GET(link, headers)).execute().let {
                    val doc = it.asJsoup()
                    val script = doc.selectFirst("script:containsData(sources)")!!.data()
                    val videoUrl = script.substringAfter("sources:[{")
                        .substringAfter("file':'")
                        .substringBefore("'")
                    listOf(Video(videoUrl, "DesuStream - $quality", videoUrl, headers))
                }
            }
            "mp4upload" in link -> {
                client.newCall(GET(link, headers)).execute().let {
                    val doc = it.asJsoup()
                    val script = doc.selectFirst("script:containsData(player.src)")!!.data()
                    val videoUrl = script.substringAfter("src: \"").substringBefore('"')
                    listOf(Video(videoUrl, "Mp4upload - $quality", videoUrl, headers))
                }
            }
            else -> emptyList()
        }
    }

    private fun getNonce(action: String): String {
        val form = FormBody.Builder().add("action", action).build()
        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", body = form))
            .execute()
            .body.string()
            .substringAfter(":\"")
            .substringBefore('"')
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Demons", "demons"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Psychological", "psychological"),
            Pair("Parody", "parody"),
            Pair("Police", "police"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sports", "sports"),
            Pair("Space", "space"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("Vampire", "vampire"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    private fun Element.getInfo(info: String, cut: Boolean = true): String? {
        return selectFirst("p > span:has(b:contains($info))")?.text()
            ?.let {
                when {
                    cut -> it.substringAfter(":")
                    else -> it
                }.trim()
            }
    }

    private fun String?.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(this?.trim() ?: "")?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    private fun String.b64Decode(): String {
        return String(Base64.decode(this, Base64.DEFAULT))
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMM,yyyy", Locale("id", "ID"))
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
