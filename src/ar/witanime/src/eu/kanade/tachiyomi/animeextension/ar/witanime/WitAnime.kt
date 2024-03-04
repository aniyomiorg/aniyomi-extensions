package eu.kanade.tachiyomi.animeextension.ar.witanime

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SoraPlayExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class WitAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "WIT ANIME"

    override val baseUrl = "https://witanime.pics"

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.anime-list-content div.row div.anime-card-poster div.ehover6"

    override fun popularAnimeNextPageSelector() = "ul.pagination a.next"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/قائمة-الانمي/page/$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")!!.run {
            attr("href").takeUnless { it.contains("javascript:") }
                ?: getEncodedUrl() // Get base64-encoded URLs
        }.also { setUrlWithoutDomain(it) }

        element.selectFirst("img")!!.also {
            title = it.attr("alt")
            thumbnail_url = it.attr("abs:src")
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episode/page/$page/")

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/?search_param=animes&s=$query")

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)

        thumbnail_url = doc.selectFirst("img.thumbnail")!!.attr("src")
        title = doc.selectFirst("h1.anime-details-title")!!.text()
        // Genres + useful info
        genre = doc.select("ul.anime-genres > li > a, div.anime-info > a").eachText().joinToString()

        description = buildString {
            // Additional info
            doc.select("div.anime-info").eachText().forEach {
                append("$it\n")
            }
            // Description
            doc.selectFirst("p.anime-story")?.text()?.also {
                append("\n$it")
            }
        }

        doc.selectFirst("div.anime-info:contains(حالة الأنمي)")?.text()?.also {
            status = when {
                it.contains("يعرض الان", true) -> SAnime.ONGOING
                it.contains("مكتمل", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) =
        getRealDoc(response.asJsoup())
            .select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()

    override fun episodeListSelector() = "div.ehover6 > div.episodes-card-title > h3 a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.getEncodedUrl())
        name = element.text()
        episode_number = name.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("ul#episode-servers li a")
            .distinctBy { it.text().substringBefore(" -") } // remove duplicates by server name
            .parallelCatchingFlatMapBlocking {
                val url = it.attr("data-url")
                    .takeUnless(String::isBlank)
                    ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
                    ?: it.getEncodedUrl()

                extractVideos(url)
            }
    }

    private val soraPlayExtractor by lazy { SoraPlayExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sharedExtractor by lazy { SharedExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }

    private fun extractVideos(url: String): List<Video> {
        return when {
            url.contains("yonaplay") -> extractFromMulti(url)
            url.contains("soraplay") -> {
                when {
                    url.contains("/mirror") -> extractFromMulti(url)
                    else -> soraPlayExtractor.videosFromUrl(url, headers)
                }
            }
            url.contains("dood") -> {
                doodExtractor.videoFromUrl(url, "Dood mirror")
                    ?.let(::listOf)
            }
            url.contains("4shared") -> {
                sharedExtractor.videosFromUrl(url)
                    ?.let(::listOf)
            }
            url.contains("dropbox") -> {
                listOf(Video(url, "Dropbox mirror", url))
            }

            url.contains("dailymotion") -> {
                dailymotionExtractor.videosFromUrl(url)
            }
            url.contains("ok.ru") -> {
                okruExtractor.videosFromUrl(url)
            }
            url.contains("mp4upload.com") -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }
            VIDBOM_REGEX.containsMatchIn(url) -> {
                vidBomExtractor.videosFromUrl(url)
            }
            else -> null
        } ?: emptyList()
    }

    private fun extractFromMulti(url: String): List<Video> {
        val newHeaders = when {
            url.contains("soraplay") ->
                super.headersBuilder().set("referer", "https://yonaplay.org").build()
            else -> headers
        }
        val doc = client.newCall(GET(url, newHeaders)).execute()
            .asJsoup()
        return doc.select(".OD li").flatMap { element ->
            val videoUrl = element.attr("onclick").substringAfter("go_to_player('")
                .substringBefore("')")
                .let {
                    when {
                        it.startsWith("https:") -> it
                        else -> "https:$it"
                    }
                }
            runCatching { extractVideos(videoUrl) }.getOrElse { emptyList() }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("div.anime-page-link a")?.let {
            client.newCall(GET(it.attr("href"), headers)).execute().asJsoup()
        } ?: document
    }

    private fun Element.getEncodedUrl() = attr("onclick")
        .substringAfter("'")
        .substringBefore("'")
        .let { String(Base64.decode(it, Base64.DEFAULT)) }

    companion object {
        // From TukTukCinema(AR)
        private val VIDBOM_REGEX by lazy { Regex("//(?:v[aie]d[bp][aoe]?m)") }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "380p", "360p", "240p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
