package eu.kanade.tachiyomi.animeextension.en.edytjedhgmdhm

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URI
import java.net.URISyntaxException

class Edytjedhgmdhm : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "edytjedhgmdhm"

    override val baseUrl by lazy { getBaseUrlPref() }

    private val videoFormats = arrayOf(".mkv", ".mp4", ".avi")

    override val lang = "en"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================ Initializers ============================

    private val asiandramaList by lazy {
        client.newCall(GET("$baseUrl/asiandrama/")).execute()
            .asJsoup()
            .select(LINK_SELECTOR)
    }

    private val kdramaList by lazy {
        client.newCall(GET("$baseUrl/kdrama/")).execute()
            .asJsoup()
            .select(LINK_SELECTOR)
    }

    private val miscList by lazy {
        client.newCall(GET("$baseUrl/misc/")).execute()
            .asJsoup()
            .select(LINK_SELECTOR)
    }

    private val moviesList by lazy {
        client.newCall(GET("$baseUrl/movies/")).execute()
            .asJsoup()
            .select(LINK_SELECTOR)
    }

    private val tvsList by lazy {
        client.newCall(GET("$baseUrl/tvs/")).execute()
            .asJsoup()
            .select(LINK_SELECTOR)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val results = tvsList.chunked(CHUNKED_SIZE).toList()

        val hasNextPage = results.size > page
        val animeList = if (results.isEmpty()) {
            emptyList()
        } else {
            results[page - 1].map {
                SAnime.create().apply {
                    title = it.text()
                    url = getUrlWithoutDomain(it.attr("abs:href"))
                    thumbnail_url = ""
                }
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()

    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter
        val subPage = subPageFilter.toUriPart()

        val resultList = when (subPage) {
            "/tvs/" -> tvsList
            "/movies/" -> moviesList
            "/misc/" -> miscList
            "/kdrama/" -> kdramaList
            "/asiandrama/" -> asiandramaList
            else -> emptyList()
        }

        val results = if (query.isNotEmpty() && resultList.isNotEmpty()) {
            resultList.filter { it.text().contains(query, true) }
                .chunked(CHUNKED_SIZE).toList()
        } else {
            resultList.chunked(CHUNKED_SIZE).toList()
        }

        val hasNextPage = results.size > page
        val animeList = if (results.isEmpty()) {
            emptyList()
        } else {
            results[page - 1].map {
                SAnime.create().apply {
                    title = it.text()
                    url = getUrlWithoutDomain(it.attr("abs:href"))
                    thumbnail_url = ""
                }
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search will only search inside selected sub-page"),
        SubPageFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Select subpage",
        arrayOf(
            Pair("TVs", "/tvs/"),
            Pair("Movies", "/movies/"),
            Pair("Misc", "/misc/"),
            Pair("K-drama", "/kdrama/"),
            Pair("Asian drama", "/asiandrama/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()

        fun traverseDirectory(url: String) {
            val doc = client.newCall(GET(url)).execute().asJsoup()

            var counter = 1

            doc.select(LINK_SELECTOR).forEach { link ->
                val href = link.attr("abs:href")

                if (href.isNotBlank()) {
                    if (href.endsWith("/")) {
                        traverseDirectory(href)
                    }
                    if (videoFormats.any { t -> href.endsWith(t) }) {
                        val paths = href.toHttpUrl().pathSegments

                        val extraInfo = if (paths.size > 3) {
                            "/" + paths.subList(2, paths.size - 1).joinToString("/") { it.trimInfo() }
                        } else {
                            "/"
                        }

                        val size = link.parent()?.parent()?.nextElementSibling()?.attr("data-order")?.toLongOrNull()?.let {
                            formatBytes(it)
                        }

                        episodeList.add(
                            SEpisode.create().apply {
                                name = videoFormats.fold(paths.last()) { acc, suffix -> acc.removeSuffix(suffix).trimInfo() }
                                this.url = getUrlWithoutDomain(href)
                                scanlator = "${if (size == null) "" else "$size • "}$extraInfo"
                                date_upload = -1L
                                episode_number = counter.toFloat()
                            },
                        )
                        counter++
                    }
                }
            }
        }

        traverseDirectory(baseUrl + anime.url)

        return episodeList.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        listOf(Video(baseUrl + episode.url, "Video", baseUrl + episode.url))

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes"
            bytes == 1L -> "$bytes byte"
            else -> ""
        }
    }

    // Same as the one in `AnimeHttpSource` but path, query, and fragment are replaced with it's
    // "raw" equivalent as to not decode url characters when it shouldn't
    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.rawPath
            if (uri.query != null) {
                out += "?" + uri.rawQuery
            }
            if (uri.fragment != null) {
                out += "#" + uri.rawFragment
            }
            out
        } catch (e: URISyntaxException) {
            orig
        }
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString
    }

    companion object {
        private const val CHUNKED_SIZE = 30
        private const val LINK_SELECTOR = "table tbody a:not([href=..])"

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private val PREF_DOMAIN_ENTRY_VALUES = arrayOf(
            "https://edytjedhgmdhm.abfhaqrhbnf.workers.dev",
            "https://odd-bird-1319.zwuhygoaqe.workers.dev",
            "https://hello-world-flat-violet-6291.wstnjewyeaykmdg.workers.dev",
            "https://worker-mute-fog-66ae.ihrqljobdq.workers.dev",
            "https://worker-square-heart-580a.uieafpvtgl.workers.dev",
            "https://worker-little-bread-2c2f.wqwmiuvxws.workers.dev",
        )
        private val PREF_DOMAIN_ENTRIES = PREF_DOMAIN_ENTRY_VALUES.map {
            it.substringAfter("//").substringBefore(".")
        }.toTypedArray()
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_ENTRY_VALUES.first()
    }

    private fun getBaseUrlPref(): String =
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_ENTRY_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
