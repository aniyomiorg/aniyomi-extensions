package eu.kanade.tachiyomi.animeextension.ar.okanime

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Okanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Okanime"

    override val baseUrl = "https://www.okanime.xyz"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.container > div.section:last-child div.anime-card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("div.anime-title > h4 > a")!!.also {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/espisode-list?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "ul.pagination > li:last-child:not(.disabled)"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        "$baseUrl/search/?s=$query"
            .let { if (page > 1) "$it&page=$page" else it }
            .let(::GET)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("div.author-info-title > h1")!!.text()
        genre = document.select("div.review-author-info a").eachText().joinToString()

        val infosdiv = document.selectFirst("div.text-right")!!
        thumbnail_url = infosdiv.selectFirst("img")!!.attr("src")
        status = infosdiv.selectFirst("div.full-list-info:contains(حالة الأنمي) a").let {
            when (it?.text() ?: "") {
                "يعرض الان" -> SAnime.ONGOING
                "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
        description = buildString {
            document.selectFirst("div.review-content")
                ?.text()
                ?.let { append("$it\n") }

            infosdiv.select("div.full-list-info").forEach { info ->
                info.select("small")
                    .eachText()
                    .joinToString(": ")
                    .let { append("\n$it") }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.row div.episode-card div.anime-title a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.text().also {
            name = it
            episode_number = it.substringAfterLast(" ").toFloatOrNull() ?: 1F
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return response.asJsoup()
            .select("a.ep-link")
            .parallelCatchingFlatMapBlocking { element ->
                val quality = element.selectFirst("span")?.text().orEmpty().let {
                    when (it) {
                        "HD" -> "720p"
                        "FHD" -> "1080p"
                        "SD" -> "480p"
                        else -> "240p"
                    }
                }
                val url = element.attr("data-src")
                extractVideosFromUrl(url, quality, hosterSelection)
            }
    }

    // Inspirated by JavGuru(all)
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }

    private fun extractVideosFromUrl(url: String, quality: String, selection: Set<String>): List<Video> {
        return when {
            "https://doo" in url && "/e/" in url && selection.contains("Dood") -> {
                doodExtractor.videoFromUrl(url, "DoodStream - $quality")
                    ?.let(::listOf)
            }
            "mp4upload" in url && selection.contains("Mp4upload") -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }
            "ok.ru" in url && selection.contains("Okru") -> {
                okruExtractor.videosFromUrl(url)
            }
            "voe.sx" in url && selection.contains("Voe") -> {
                voeExtractor.videosFromUrl(url)
            }
            VID_BOM_DOMAINS.any(url::contains) && selection.contains("VidBom") -> {
                vidBomExtractor.videosFromUrl(url)
            }
            else -> null
        }.orEmpty()
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

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
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    companion object {
        const val PREFIX_SEARCH = "id:"

        private val VID_BOM_DOMAINS = listOf("vidbam", "vadbam", "vidbom", "vidbm")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")

        private const val PREF_HOSTER_SELECTION_KEY = "pref_hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Enable/Disable hosts"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Dood", "Voe", "Mp4upload", "VidBom", "Okru")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_ENTRIES.toSet() }
    }
}
