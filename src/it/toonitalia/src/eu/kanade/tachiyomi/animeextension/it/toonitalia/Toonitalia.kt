package eu.kanade.tachiyomi.animeextension.it.toonitalia

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.it.toonitalia.extractors.MaxStreamExtractor
import eu.kanade.tachiyomi.animeextension.it.toonitalia.extractors.StreamZExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Toonitalia : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Toonitalia"

    override val baseUrl = "https://toonitalia.green"

    override val lang = "it"

    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun popularAnimeSelector() = "#primary > main#main > article"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("h2 > a")!!.run {
            title = text()
            setUrlWithoutDomain(attr("href"))
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = "nav.pagination a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val isNormalSearch = document.location().contains("/?s=")
        val animes = if (isNormalSearch) {
            document.select(searchAnimeSelector()).map(::searchAnimeFromElement)
        } else {
            document.select(searchIndexAnimeSelector()).map(::searchIndexAnimeFromElement)
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query", headers = headers)
        } else {
            val url = "$baseUrl".toHttpUrl().newBuilder().apply {
                filters.filterIsInstance<IndexFilter>()
                    .firstOrNull()
                    ?.also { addPathSegment(it.toUriPart()) }
            }
            val newUrl = url.toString() + "/?lcp_page0=$page#lcp_instance_0"
            GET(newUrl, headers)
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    private fun searchIndexAnimeSelector() = "div.entry-content > ul.lcp_catlist > li"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    private fun searchIndexAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")!!.run {
            title = text()
            setUrlWithoutDomain(attr("href"))
        }
    }

    override fun searchAnimeNextPageSelector() =
        "nav.navigation div.nav-previous, " + // Normal search
            "ul.lcp_paginator > li > a.lcp_nextlink" // Index search

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.entry-title")!!.text()
        thumbnail_url = document.selectFirst("header.entry-header img")!!.attr("abs:src")

        // Cursed sources should have cursed code!
        description = document.selectFirst("article > div.entry-content")!!
            .also { it.select("center").remove() } // Remove unnecessary data
            .wholeText()
            .replace(",", ", ").replace("  ", " ") // Fix text
            .lines()
            .map(String::trim)
            .filterNot { it.startsWith("Titolo:") }
            .also { lines ->
                genre = lines.firstOrNull { it.startsWith("Genere:") }
                    ?.substringAfter("Genere: ")
            }
            .joinToString("\n")
            .substringAfter("Trama: ")
    }

    // ============================== Episodes ==============================
    private val episodeNumRegex by lazy { Regex("\\s(\\d+x\\d+)\\s?") }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val url = doc.location()

        if ("/film-anime/" in url) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain("$url#0")
                    episode_number = 1F
                    name = doc.selectFirst("h1.entry-title")!!.text()
                },
            )
        }

        val epNames = doc.select(episodeListSelector() + ">td:not(:has(a))").eachText()
        return epNames.mapIndexed { index, item ->
            SEpisode.create().apply {
                setUrlWithoutDomain("$url#$index")
                val (season, episode) = episodeNumRegex.find(item)
                    ?.groupValues
                    ?.last()
                    ?.split("x")
                    ?: listOf("01", "01")
                name = "Stagione $season - Episodi $episode"
                episode_number = "$season.${episode.padStart(3, '0')}".toFloatOrNull() ?: 1F
            }
        }.reversed()
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListSelector() = "article > div.entry-content table tr:has(a)"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeNumber = response.request.url.fragment!!.toInt()

        val episode = document.select(episodeListSelector())
            .getOrNull(episodeNumber)
            ?: return emptyList()

        return episode.select("a").flatMap {
            runCatching {
                val url = it.attr("href")
                val hosterUrl = when {
                    url.contains("uprot.net") -> bypassUprot(url)
                    else -> url
                }
                hosterUrl?.let(::extractVideos)
            }.getOrNull() ?: emptyList()
        }
    }

    private val voeExtractor by lazy { VoeExtractor(client) }
    private val streamZExtractor by lazy { StreamZExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val maxStreamExtractor by lazy { MaxStreamExtractor(client, headers) }

    private fun extractVideos(url: String): List<Video> =
        when {
            "https://voe.sx" in url -> voeExtractor.videosFromUrl(url)
            "https://streamtape" in url -> streamTapeExtractor.videoFromUrl(url)?.let(::listOf)
            "https://maxstream" in url -> maxStreamExtractor.videosFromUrl(url)
            "https://streamz" in url || "streamz.cc" in url -> {
                streamZExtractor.videoFromUrl(url, "StreamZ")?.let(::listOf)
            }
            else -> null
        } ?: emptyList()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTA: ignorato se si utilizza la ricerca di testo!"),
        AnimeFilter.Separator(),
        IndexFilter(getIndexList()),
    )

    private class IndexFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Indice", vals)

    private fun getIndexList() = arrayOf(
        Pair("<selezionare>", ""),
        Pair("Lista Anime e Cartoni", "lista-anime-e-cartoni"),
        Pair("Lista Film Anime", "lista-film-anime"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

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

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
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
    private fun bypassUprot(url: String): String? =
        client.newCall(GET(url, headers)).execute()
            .asJsoup()
            .selectFirst("a:has(button.button.is-info)")
            ?.attr("href")

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "240", "80")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "StreamZ"
        private val PREF_SERVER_ENTRIES = arrayOf("StreamZ", "VOE", "StreamZ Sub-Ita", "VOE Sub-Ita", "MaxStream", "StreamTape")
        private val PREF_SERVER_VALUES = PREF_SERVER_ENTRIES
    }
}
