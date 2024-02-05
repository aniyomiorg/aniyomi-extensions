package eu.kanade.tachiyomi.animeextension.de.animebase

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.animebase.extractors.UnpackerExtractor
import eu.kanade.tachiyomi.animeextension.de.animebase.extractors.VidGuardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeBase : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime-Base"

    override val baseUrl = "https://anime-base.net"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/favorites", headers)

    override fun popularAnimeSelector() = "div.table-responsive > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href").replace("/link/", "/anime/"))
        thumbnail_url = element.selectFirst("div.thumbnail img")?.absUrl("src")
        title = element.selectFirst("div.caption h3")!!.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/updates", headers)

    override fun latestUpdatesSelector() = "div.box-header + div.box-body > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun getFilterList() = AnimeBaseFilters.FILTER_LIST

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/searching", headers)).execute()
            .asJsoup()
            .selectFirst("form > input[name=_token]")!!
            .attr("value")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeBaseFilters.getSearchParameters(filters)

        return when {
            params.list.isEmpty() -> {
                val body = FormBody.Builder()
                    .add("_token", searchToken)
                    .add("_token", searchToken)
                    .add("name_serie", query)
                    .add("jahr", params.year.toIntOrNull()?.toString() ?: "")
                    .apply {
                        params.languages.forEach { add("dubsub[]", it) }
                        params.genres.forEach { add("genre[]", it) }
                    }.build()
                POST("$baseUrl/searching", headers, body)
            }

            else -> {
                GET("$baseUrl/${params.list}${params.letter}?page=$page", headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()

        return when {
            doc.location().contains("/searching") -> {
                val animes = doc.select(searchAnimeSelector()).map(::searchAnimeFromElement)
                AnimesPage(animes, false)
            }
            else -> { // pages like filmlist or animelist
                val animes = doc.select(popularAnimeSelector()).map(::popularAnimeFromElement)
                val hasNext = doc.selectFirst(searchAnimeNextPageSelector()) != null
                AnimesPage(animes, hasNext)
            }
        }
    }

    override fun searchAnimeSelector() = "div.col-lg-9.col-md-8 div.box-body > a"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = "ul.pagination li > a[rel=next]"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())

        val boxBody = document.selectFirst("div.box-body.box-profile > center")!!
        title = boxBody.selectFirst("h3")!!.text()
        thumbnail_url = boxBody.selectFirst("img")!!.absUrl("src")

        val infosDiv = document.selectFirst("div.box-body > div.col-md-9")!!
        status = parseStatus(infosDiv.getInfo("Status"))
        genre = infosDiv.select("strong:contains(Genre) + p > a").eachText()
            .joinToString()
            .takeIf(String::isNotBlank)

        description = buildString {
            infosDiv.getInfo("Beschreibung")?.also(::append)

            infosDiv.getInfo("Originalname")?.also { append("\nOriginal name: $it") }
            infosDiv.getInfo("Erscheinungsjahr")?.also { append("\nErscheinungsjahr: $it") }
        }
    }

    private fun parseStatus(status: String?) = when (status?.orEmpty()) {
        "Laufend" -> SAnime.ONGOING
        "Abgeschlossen" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun Element.getInfo(selector: String) =
        selectFirst("strong:contains($selector) + p")?.text()?.trim()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).sortedWith(
            compareBy(
                { it.name.startsWith("Film ") },
                { it.name.startsWith("Special ") },
                { it.episode_number },
            ),
        ).reversed()

    override fun episodeListSelector() = "div.tab-content > div > div.panel"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val epname = element.selectFirst("h3")?.text() ?: "Episode 1"
        val language = when (element.selectFirst("button")?.attr("data-dubbed").orEmpty()) {
            "0" -> "Subbed"
            else -> "Dubbed"
        }

        name = epname
        scanlator = language
        episode_number = epname.substringBefore(":").substringAfter(" ").toFloatOrNull() ?: 0F
        val selectorClass = element.classNames().first { it.startsWith("episode-div") }
        setUrlWithoutDomain(element.baseUri() + "?selector=div.panel.$selectorClass")
    }

    // ============================ Video Links =============================
    private val hosterSettings by lazy {
        mapOf(
            "Streamwish" to "https://streamwish.to/e/",
            "Voe.SX" to "https://voe.sx/e/",
            "Lulustream" to "https://lulustream.com/e/",
            "VTube" to "https://vtbe.to/embed-",
            "VidGuard" to "https://vembed.net/e/",
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val selector = response.request.url.queryParameter("selector")
            ?: return emptyList()

        return doc.select("$selector div.panel-body > button").toList()
            .filter { it.text() in hosterSettings.keys }
            .parallelCatchingFlatMapBlocking {
                val language = when (it.attr("data-dubbed")) {
                    "0" -> "SUB"
                    else -> "DUB"
                }

                getVideosFromHoster(it.text(), it.attr("data-streamlink"))
                    .map { video ->
                        Video(
                            video.url,
                            "$language ${video.quality}",
                            video.videoUrl,
                            video.headers,
                            video.subtitleTracks,
                            video.audioTracks,
                        )
                    }
            }
    }

    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val unpackerExtractor by lazy { UnpackerExtractor(client, headers) }
    private val vidguardExtractor by lazy { VidGuardExtractor(client) }

    private fun getVideosFromHoster(hoster: String, urlpart: String): List<Video> {
        val url = hosterSettings.get(hoster)!! + urlpart
        return when (hoster) {
            "Streamwish" -> streamWishExtractor.videosFromUrl(url)
            "Voe.SX" -> voeExtractor.videosFromUrl(url)
            "VTube", "Lulustream" -> unpackerExtractor.videosFromUrl(url, hoster)
            "VidGuard" -> vidguardExtractor.videosFromUrl(url)
            else -> null
        } ?: emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

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
    companion object {
        private const val PREF_LANG_KEY = "preferred_sub"
        private const val PREF_LANG_TITLE = "Standardmäßig Sub oder Dub?"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("Sub", "Dub")
        private val PREF_LANG_VALUES = arrayOf("SUB", "DUB")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
