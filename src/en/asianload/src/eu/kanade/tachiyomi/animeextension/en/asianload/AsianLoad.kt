package eu.kanade.tachiyomi.animeextension.en.asianload

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class AsianLoad : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AsianLoad"

    override val baseUrl = "https://asianhd1.com"

    override val lang = "en"

    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "ul.listing.items li a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/popular?page=$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.selectFirst("div.img div.picture img")!!.run {
            thumbnail_url = attr("src")
            title = attr("alt")
        }
    }

    override fun popularAnimeNextPageSelector() = "li.next a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search.html?keyword=$query&page=$page"
        } else {
            filters
                .filterIsInstance<TypeList>()
                .firstOrNull()
                ?.takeIf { it.state > 0 }
                ?.let { filter ->
                    val type = getTypeList()[filter.state].query
                    "$baseUrl/$type?page=$page"
                }
                ?: throw Exception("Choose Filter")
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val vidDetails = document.selectFirst("div.video-details")!!
        title = vidDetails.selectFirst("span.date")!!.text()
        description = vidDetails.selectFirst("div.post-entry")?.text()
        thumbnail_url = document.selectFirst("meta[image]")?.attr("content")
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.listing.items.lists li a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epNum = element.selectFirst("div.name")!!.text().substringAfter("Episode ")
        name = element.selectFirst("div.type span")!!.text() + " Episode: $epNum"
        episode_number = when {
            epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        date_upload = element.selectFirst("span.date")?.text()?.toDate() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val document = client.newCall(GET(baseUrl + episode.url)).execute()
            .asJsoup()
        val iframe = document.selectFirst("iframe")!!.attr("abs:src")
        return GET(iframe)
    }

    override fun videoListParse(response: Response): List<Video> =
        response.asJsoup() // document
            .select(videoListSelector()) // players
            .flatMap(::videosFromElement) // videos

    override fun videoListSelector() = "ul.list-server-items li"

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    private fun videosFromElement(element: Element): List<Video> {
        val url = element.attr("data-video")
        return when {
            "dood" in url -> doodExtractor.videoFromUrl(url)?.let(::listOf)
            "streamtape" in url -> streamTapeExtractor.videoFromUrl(url)?.let(::listOf)
            "dwish" in url -> streamWishExtractor.videosFromUrl(url)
            "mixdrop" in url -> mixDropExtractor.videoFromUrl(url)
            else -> null
        } ?: emptyList()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        TypeList(typesName),
    )

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("Drame Type", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList() = listOf(
        Type("Select", ""),
        Type("Recently Added Sub", ""),
        Type("Recently Added Raw", "recently-added-raw"),
        Type("Drama Movie", "movies"),
        Type("KShow", "kshow"),
        Type("Ongoing Series", "ongoing-series"),
    )

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
    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape")
    }
}
