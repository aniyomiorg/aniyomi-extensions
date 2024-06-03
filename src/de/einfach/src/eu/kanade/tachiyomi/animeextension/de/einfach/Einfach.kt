package eu.kanade.tachiyomi.animeextension.de.einfach

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.einfach.extractors.MyStreamExtractor
import eu.kanade.tachiyomi.animeextension.de.einfach.extractors.UnpackerExtractor
import eu.kanade.tachiyomi.animeextension.de.einfach.extractors.VidozaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Einfach : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Einfach"

    override val baseUrl = "https://einfach.to"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    // Actually the source doesn't provide a popular entries page, and the
    // "sort by views" filter isn't working, so we'll use the latest series updates instead.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series/page/$page")

    override fun popularAnimeSelector() = "article.box > div.bx > a.tip"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.run {
            absUrl("data-lazy-src").ifEmpty { absUrl("src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination > a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filme/page/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/page/$page/?s=$query")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val info = document.selectFirst("article div > div.infl")!!
        title = info.selectFirst("h1.entry-title")!!.text()
        thumbnail_url = info.selectFirst("img")?.run {
            absUrl("data-lazy-src").ifEmpty { absUrl("src") }
        }

        artist = info.getInfo("Stars:")
        genre = info.getInfo("Genre:")
        author = info.getInfo("Network:")
        status = parseStatus(info.getInfo("Status:").orEmpty())

        description = info.selectFirst("div.entry-content > p")?.ownText()
    }

    private fun Element.getInfo(label: String) =
        selectFirst("li:has(b:contains($label)) > span.colspan")?.text()?.trim()

    private fun parseStatus(status: String) = when (status) {
        "Ongoing" -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url.contains("/filme/")) {
            val episode = SEpisode.create().apply {
                url = anime.url
                name = "Movie - ${anime.title}"
                episode_number = 1F
            }
            return listOf(episode)
        }

        return super.getEpisodeList(anime)
    }

    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.epsdlist > ul > li > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val eplnum = element.selectFirst(".epl-num")?.text().orEmpty().trim()
        episode_number = eplnum.substringAfterLast(" ").toFloatOrNull() ?: 1F

        name = eplnum.ifBlank { "S1 EP 1" } + " - " + element.selectFirst(".epl-title")?.text().orEmpty()
        date_upload = element.selectFirst(".epl-date")?.text().orEmpty().toDate()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        val selection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!

        val links = doc.select(videoListSelector()).asSequence()
            .filter { it.text().lowercase() in selection }
            .mapNotNull { element ->
                val html = element.attr("data-em").let { b64encoded ->
                    runCatching {
                        String(Base64.decode(b64encoded, Base64.DEFAULT))
                    }.getOrNull()
                }

                val url = html?.let(Jsoup::parseBodyFragment)
                    ?.selectFirst("iframe")
                    ?.attr("src")
                    ?: return@mapNotNull null

                val fixedUrl = url.takeIf { it.startsWith("https:") } ?: "https:$url"

                element.text().lowercase() to fixedUrl
            }.toList()

        return links.parallelCatchingFlatMapBlocking { (name, link) ->
            getVideosFromUrl(name, link)
        }
    }

    override fun videoListSelector() = "div.lserv > ul > li > a"

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val lulustreamExtractor by lazy { UnpackerExtractor(client, headers) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val mystreamExtractor by lazy { MyStreamExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidozaExtractor by lazy { VidozaExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }

    private fun getVideosFromUrl(name: String, url: String): List<Video> {
        return when (name) {
            "doodstream" -> doodExtractor.videosFromUrl(url)
            "filelions" -> streamwishExtractor.videosFromUrl(url, videoNameGen = { "FileLions - $it" })
            "filemoon" -> filemoonExtractor.videosFromUrl(url)
            "lulustream" -> lulustreamExtractor.videosFromUrl(url, "LuLuStream")
            "mixdrop" -> mixdropExtractor.videosFromUrl(url)
            "streamtape" -> streamtapeExtractor.videosFromUrl(url)
            "streamwish" -> streamwishExtractor.videosFromUrl(url)
            "vidoza" -> vidozaExtractor.videosFromUrl(url)
            "voe" -> voeExtractor.videosFromUrl(url)
            "stream in hd" -> mystreamExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "path:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("240p", "360p", "480p", "720p", "1080p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_HOSTER_SELECTION_KEY = "pref_hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Enable/Disable video hosters"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf(
            "DoodStream",
            "FileLions",
            "Filemoon",
            "LuLuStream",
            "MixDrop",
            "Streamtape",
            "StreamWish",
            "Vidoza",
            "VOE",
            "Stream in HD",
        )
        private val PREF_HOSTER_SELECTION_VALUES by lazy { PREF_HOSTER_SELECTION_ENTRIES.map(String::lowercase).toTypedArray() }
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_VALUES.toSet() }
    }
}
