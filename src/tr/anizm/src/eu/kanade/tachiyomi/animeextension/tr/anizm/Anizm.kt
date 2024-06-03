package eu.kanade.tachiyomi.animeextension.tr.anizm

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.anizm.AnizmFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.tr.anizm.extractors.AincradExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Anizm : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Anizm"

    override val baseUrl = "https://anizm.net"

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.popularAnimeCarousel a.slideAnimeLink"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst(".title")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        element.attr("href")
            .substringBefore("-bolum-izle")
            .substringBeforeLast("-")
            .also { setUrlWithoutDomain(it) }
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime-izle?sayfa=$page", headers)

    override fun latestUpdatesSelector() = "div#episodesMiddle div.posterBlock > a"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "div.nextBeforeButtons > div.ui > a.right:not(.disabled)"

    // =============================== Search ===============================
    private val animeList by lazy {
        client.newCall(GET("$baseUrl/getAnimeListForSearch", headers)).execute()
            .parseAs<List<SearchItemDto>>()
            .asSequence()
    }

    override fun getFilterList(): AnimeFilterList = AnizmFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            val params = AnizmFilters.getSearchParameters(filters).apply {
                animeName = query
            }
            val filtered = animeList.applyFilterParams(params)
            val results = filtered.chunked(30).toList()
            val hasNextPage = results.size > page
            val currentPage = if (results.size == 0) {
                emptyList<SAnime>()
            } else {
                results.get(page - 1).map {
                    SAnime.create().apply {
                        title = it.title
                        url = "/" + it.slug
                        thumbnail_url = baseUrl + "/storage/pcovers/" + it.thumbnail
                    }
                }
            }
            AnimesPage(currentPage, hasNextPage)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h2.anizm_pageTitle")!!.text()
        thumbnail_url = document.selectFirst("div.infoPosterImg > img")!!.attr("abs:src")
        val infosDiv = document.selectFirst("div.anizm_boxContent")!!
        genre = infosDiv.select("span.dataValue > span.tag > span.label").eachText().joinToString()
        artist = infosDiv.selectFirst("span.dataTitle:contains(Stüdyo) + span")?.text()

        description = buildString {
            infosDiv.selectFirst("div.infoDesc")?.text()?.also(::append)

            infosDiv.select("li.dataRow:not(:has(span.ui.tag)):not(:has(div.star)) > span")
                .forEach {
                    when {
                        it.hasClass("dataTitle") -> append("\n${it.text()}: ")
                        else -> append(it.text())
                    }
                }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.episodeListTabContent div > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        episode_number = element.text().filter(Char::isDigit).toFloatOrNull() ?: 1F
        name = element.text()
    }

    // ============================ Video Links =============================
    @Serializable
    data class ResponseDto(val data: String)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        val fansubUrls = doc.select("div#fansec > a")
            .filterSubs()
            .map { it.text().fixedFansubName() to it.attr("translator") }
            .ifEmpty {
                throw Exception("No fansubs available! Have you filtered them out?")
            }

        val chosenHosts = preferences.getStringSet(PREF_HOSTS_SELECTION_KEY, PREF_HOSTS_SELECTION_DEFAULT)!!

        val playerUrls = fansubUrls.flatMap { pair ->
            val (fansub, url) = pair
            runCatching {
                client.newCall(GET(url, headers)).execute()
                    .parseAs<ResponseDto>()
                    .data
                    .let(Jsoup::parse)
                    .select("a.videoPlayerButtons")
                    .toList()
                    .filter { host ->
                        val hostName = host.text().trim()
                        chosenHosts.any { hostName.contains(it, true) }
                    }
                    .map { fansub to it.attr("video").replace("/video/", "/player/") }
            }.getOrElse { emptyList() }
        }

        return playerUrls.parallelCatchingFlatMapBlocking { pair ->
            val (fansub, url) = pair
            getVideosFromUrl(url).map {
                Video(
                    it.url,
                    "[$fansub] ${it.quality}",
                    it.videoUrl,
                    it.headers,
                    it.subtitleTracks,
                    it.audioTracks,
                )
            }
        }
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private val aincradExtractor by lazy { AincradExtractor(client, headers, json) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    private fun getVideosFromUrl(firstUrl: String): List<Video> {
        val url = noRedirectClient.newCall(GET(firstUrl, headers)).execute()
            .use { it.headers["location"] }
            ?: return emptyList()

        return when {
            "filemoon.sx" in url -> filemoonExtractor.videosFromUrl(url, headers = headers)
            "sendvid.com" in url -> sendvidExtractor.videosFromUrl(url)
            "video.sibnet" in url -> sibnetExtractor.videosFromUrl(url)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers)
            "ok.ru" in url || "odnoklassniki.ru" in url -> okruExtractor.videosFromUrl(url)
            "yourupload" in url -> yourUploadExtractor.videoFromUrl(url, headers)
            "streamtape" in url -> streamtapeExtractor.videoFromUrl(url)?.let(::listOf)
            "dood" in url -> doodExtractor.videoFromUrl(url)?.let(::listOf)
            "drive.google" in url -> {
                val newUrl = "https://gdriveplayer.to/embed2.php?link=$url"
                gdrivePlayerExtractor.videosFromUrl(newUrl, "GdrivePlayer", headers)
            }
            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
            "voe.sx" in url -> voeExtractor.videosFromUrl(url)
            "anizmplayer.com" in url -> aincradExtractor.videosFromUrl(url)
            else -> null
        } ?: emptyList()
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
            key = PREF_FANSUB_SELECTION_KEY
            title = PREF_FANSUB_SELECTION_TITLE
            PREF_FANSUB_SELECTION_ENTRIES.let {
                entries = it
                entryValues = it
                setDefaultValue(it.toSet())
            }

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_ADDITIONAL_FANSUBS_KEY
            title = PREF_ADDITIONAL_FANSUBS_TITLE
            dialogTitle = PREF_ADDITIONAL_FANSUBS_DIALOG_TITLE
            dialogMessage = PREF_ADDITIONAL_FANSUBS_DIALOG_MESSAGE
            setDefaultValue(PREF_ADDITIONAL_FANSUBS_DEFAULT)
            summary = PREF_ADDITIONAL_FANSUBS_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = newValue as String
                    Toast.makeText(screen.context, PREF_ADDITIONAL_FANSUBS_TOAST, Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTS_SELECTION_KEY
            title = PREF_HOSTS_SELECTION_TITLE
            entries = PREF_HOSTS_SELECTION_ENTRIES
            entryValues = PREF_HOSTS_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTS_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) }, // preferred quality first
                { it.quality.substringBefore("]") }, // then group by fansub
                // then group by quality
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun String.fixedFansubName(): String =
        substringBefore("- BD")
            .substringBefore("Fansub")
            .substringBefore("Bağımsız")
            .trim()

    private fun Elements.filterSubs(): List<Element> {
        val allFansubs = PREF_FANSUB_SELECTION_ENTRIES
        val chosenFansubs = preferences.getStringSet(PREF_FANSUB_SELECTION_KEY, allFansubs.toSet())!!

        return toList().filter {
            val text = it.text().fixedFansubName()
            text in chosenFansubs || text !in allFansubs
        }
    }

    private val PREF_FANSUB_SELECTION_ENTRIES: Array<String> get() {
        val additional = preferences.getString(PREF_ADDITIONAL_FANSUBS_KEY, "")!!
            .split(",")
            .map { it.fixedFansubName() }
            .filter(String::isNotBlank)
            .toSet()

        return (DEFAULT_FANSUBS + additional).sorted().toTypedArray()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_FANSUB_SELECTION_KEY = "pref_fansub_selection"
        private const val PREF_FANSUB_SELECTION_TITLE = "Enable/Disable Fansubs"
        private val DEFAULT_FANSUBS by lazy {
            setOf(
                "Adonis",
                "Akatsuki",
                "AnimeSeverler",
                "AniSekai",
                "Aoi",
                "ARE-YOU-SURE",
                "ÇeviriBükücüler",
                "DeiraSubs",
                "Güncellenecek",
                "hitokirireaper",
                "Holy",
                "Lawsonia",
                "LoliSubs",
                "LowSubs",
                "Magnum357",
                "NaoSubs",
                "Origami",
                "PijamalıKoi",
                "Tempest",
                "UragiriSubs",
                "whosgoodbadass",
                "Yuki",
                "YuushaSubs",
            )
        }

        private const val PREF_ADDITIONAL_FANSUBS_KEY = "pref_additional_fansubs_key"
        private const val PREF_ADDITIONAL_FANSUBS_TITLE = "Add custom fansubs to the selection preference"
        private const val PREF_ADDITIONAL_FANSUBS_DEFAULT = ""
        private const val PREF_ADDITIONAL_FANSUBS_DIALOG_TITLE = "Enter a list of additional fansubs, separated by a comma."
        private const val PREF_ADDITIONAL_FANSUBS_DIALOG_MESSAGE = "Example: AntichristHaters Fansub, 2cm erect subs"
        private const val PREF_ADDITIONAL_FANSUBS_SUMMARY = "You can add more fansubs to the previous preference from here."
        private const val PREF_ADDITIONAL_FANSUBS_TOAST = "Reopen the extension's preferences for it to take effect."

        private const val PREF_HOSTS_SELECTION_KEY = "pref_hosts_selection"
        private const val PREF_HOSTS_SELECTION_TITLE = "Disable/enable video hosts"
        private val PREF_HOSTS_SELECTION_ENTRIES = arrayOf(
            "Aincrad",
            "DoodStream",
            "FileMoon",
            "GDrive",
            "MP4Upload",
            "Odnoklassniki",
            "SendVid",
            "Sibnet",
            "StreamTape",
            "UQload",
            "Voe",
            "YourUpload",
        )
        private val PREF_HOSTS_SELECTION_DEFAULT by lazy { PREF_HOSTS_SELECTION_ENTRIES.toSet() }
    }
}
