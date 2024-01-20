package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Aniwave : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Aniwave"

    override val id: Long = 98855593379717478

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    override val lang = "en"

    override val supportsLatest = true

    private val utils by lazy { AniwaveUtils() }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val refererHeaders = headers.newBuilder().apply {
        add("Referer", "$baseUrl/")
    }.build()

    private val markFiller by lazy { preferences.getBoolean(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filter?sort=trending&page=$page", refererHeaders)

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.select("a.name").let { a ->
            setUrlWithoutDomain(a.attr("href").substringBefore("?"))
            title = a.text()
        }
        thumbnail_url = element.select("div.poster img").attr("src")
    }

    override fun popularAnimeNextPageSelector(): String =
        "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/filter?sort=recently_updated&page=$page", refererHeaders)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filters = AniwaveFilters.getSearchParameters(filters)

        val vrf = if (query.isNotBlank()) utils.vrfEncrypt(query) else ""
        var url = "$baseUrl/filter?keyword=$query"

        if (filters.genre.isNotBlank()) url += filters.genre
        if (filters.country.isNotBlank()) url += filters.country
        if (filters.season.isNotBlank()) url += filters.season
        if (filters.year.isNotBlank()) url += filters.year
        if (filters.type.isNotBlank()) url += filters.type
        if (filters.status.isNotBlank()) url += filters.status
        if (filters.language.isNotBlank()) url += filters.language
        if (filters.rating.isNotBlank()) url += filters.rating

        return GET("$url&sort=${filters.sort}&page=$page&$vrf", refererHeaders)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniwaveFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.select("h1.title").text()
        genre = document.select("div:contains(Genre) > span > a").joinToString { it.text() }
        description = document.select("div.synopsis > div.shorting > div.content").text()
        author = document.select("div:contains(Studio) > span > a").text()
        status = parseStatus(document.select("div:contains(Status) > span").text())

        val altName = "Other name(s): "
        document.select("h1.title").attr("data-jp").let {
            if (it.isNotBlank()) {
                description = when {
                    description.isNullOrBlank() -> altName + it
                    else -> description + "\n\n$altName" + it
                }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
            .selectFirst("div[data-id]")!!.attr("data-id")
        val vrf = utils.vrfEncrypt(id)

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + anime.url)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return GET("$baseUrl/ajax/episode/list/$id?$vrf#${anime.url}", listHeaders)
    }

    override fun episodeListSelector() = "div.episodes ul > li > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.url.fragment!!
        val document = response.parseAs<ResultResponse>().toDocument()

        val episodeElements = document.select(episodeListSelector())
        return episodeElements.parallelMapBlocking { episodeFromElements(it, animeUrl) }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromElements(element: Element, url: String): SEpisode {
        val title = element.parent()?.attr("title") ?: ""

        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = if (element.attr("data-sub").toInt().toBoolean()) "Sub" else ""
        val dub = if (element.attr("data-dub").toInt().toBoolean()) "Dub" else ""
        val softSub = if (SOFTSUB_REGEX.find(title) != null) "SoftSub" else ""

        val extraInfo = if (element.hasClass("filler") && markFiller) {
            " • Filler Episode"
        } else {
            ""
        }
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()
        val namePrefix = "Episode $epNum"

        return SEpisode.create().apply {
            this.name = "Episode $epNum" +
                if (name.isNotEmpty() && name != namePrefix) ": $name" else ""
            this.url = "$ids&epurl=$url/ep-$epNum"
            episode_number = epNum.toFloat()
            date_upload = RELEASE_REGEX.find(title)?.let {
                parseDate(it.groupValues[1])
            } ?: 0L
            scanlator = arrayOf(sub, softSub, dub).filter(String::isNotBlank).joinToString(", ") + extraInfo
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val ids = episode.url.substringBefore("&")
        val vrf = utils.vrfEncrypt(ids)
        val url = "/ajax/server/list/$ids?$vrf"
        val epurl = episode.url.substringAfter("epurl=")

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + epurl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return GET("$baseUrl$url#$epurl", listHeaders)
    }

    data class VideoData(
        val type: String,
        val serverId: String,
        val serverName: String,
    )

    override fun videoListParse(response: Response): List<Video> {
        val epurl = response.request.url.fragment!!
        val document = response.parseAs<ResultResponse>().toDocument()
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        val typeSelection = preferences.getStringSet(PREF_TYPE_TOGGLE_KEY, PREF_TYPES_TOGGLE_DEFAULT)!!

        return document.select("div.servers > div").parallelFlatMapBlocking { elem ->
            val type = elem.attr("data-type").replaceFirstChar { it.uppercase() }
            elem.select("li").mapNotNull { serverElement ->
                val serverId = serverElement.attr("data-link-id")
                val serverName = serverElement.text().lowercase()
                if (hosterSelection.contains(serverName, true).not()) return@mapNotNull null
                if (typeSelection.contains(type, true).not()) return@mapNotNull null

                VideoData(type, serverId, serverName)
            }
        }
            .parallelFlatMapBlocking { extractVideo(it, epurl) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    private fun extractVideo(server: VideoData, epUrl: String): List<Video> {
        val vrf = utils.vrfEncrypt(server.serverId)

        val listHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", baseUrl + epUrl)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val response = client.newCall(
            GET("$baseUrl/ajax/server/${server.serverId}?$vrf", listHeaders),
        ).execute()
        if (response.code != 200) return emptyList()

        return runCatching {
            val parsed = response.parseAs<ServerResponse>()
            val embedLink = utils.vrfDecrypt(parsed.result.url)
            when (server.serverName) {
                "vidplay", "mycloud" -> {
                    val hosterName = when (server.serverName) {
                        "vidplay" -> "VidPlay"
                        else -> "MyCloud"
                    }
                    vidsrcExtractor.videosFromUrl(embedLink, hosterName, server.type)
                }
                "filemoon" -> filemoonExtractor.videosFromUrl(embedLink, "Filemoon - ${server.type} - ")
                "streamtape" -> streamtapeExtractor.videoFromUrl(embedLink, "StreamTape - ${server.type}")?.let(::listOf) ?: emptyList()
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(embedLink, headers, suffix = " - ${server.type}")
                else -> emptyList()
            }
        }.getOrElse { emptyList() }
    }

    private fun Int.toBoolean() = this == 1

    private fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean {
        return any { it.equals(s, ignoreCase) }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    @Synchronized
    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Releasing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private val SOFTSUB_REGEX by lazy { Regex("""\bsoftsub\b""", RegexOption.IGNORE_CASE) }
        private val RELEASE_REGEX by lazy { Regex("""Release: (\d+\/\d+\/\d+ \d+:\d+)""") }

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ENGLISH)
        }

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://aniwave.to"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "vidplay"

        private const val PREF_MARK_FILLERS_KEY = "mark_fillers"
        private const val PREF_MARK_FILLERS_DEFAULT = true

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = arrayOf(
            "VidPlay",
            "MyCloud",
            "Filemoon",
            "StreamTape",
            "Mp4Upload",
        )
        private val HOSTERS_NAMES = arrayOf(
            "vidplay",
            "mycloud",
            "filemoon",
            "streamtape",
            "mp4upload",
        )
        private val PREF_HOSTER_DEFAULT = HOSTERS_NAMES.toSet()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES = arrayOf("Sub", "Softsub", "Dub")
        private val PREF_TYPES_TOGGLE_DEFAULT = TYPES.toSet()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain"
            entries = arrayOf("aniwave.to", "aniwave.li", "aniwave.ws", "aniwave.vc")
            entryValues = arrayOf("https://aniwave.to", "https://aniwave.li", "https://aniwave.ws", "https://aniwave.vc")
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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
            key = PREF_LANG_KEY
            title = "Preferred Type"
            entries = TYPES
            entryValues = TYPES
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
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(PREF_MARK_FILLERS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_TYPE_TOGGLE_KEY
            title = "Enable/Disable Types"
            entries = TYPES
            entryValues = TYPES
            setDefaultValue(PREF_TYPES_TOGGLE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
