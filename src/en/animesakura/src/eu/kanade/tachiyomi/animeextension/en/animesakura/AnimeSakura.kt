package eu.kanade.tachiyomi.animeextension.en.animesakura

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.googledriveepisodes.GoogleDriveEpisodes
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeSakura : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Anime Sakura"

    override val baseUrl = "https://animesakura.co"

    override val lang = "en"

    // Used for loading anime
    private var infoQuery = ""
    private var max = ""
    private var latestPost = ""
    private var layout = ""
    private var settings = ""
    private var currentReferer = ""

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val formBody = FormBody.Builder().apply {
            add("action", "tie_blocks_load_more")
            add("block[order]", "views")
            add("block[asc_or_desc]", "DESC")
            add("block[id][]", "3")
            add("block[number]", "24")
            addExtra(page)
        }.build()

        val formHeaders = headersBuilder().apply {
            add("Accept", "*/*")
            add("Host", baseUrl.toHttpUrl().host)
            add("Origin", baseUrl)
            add("Referer", "$baseUrl/anime-series/")
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", formHeaders, formBody)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val rawParsed = json.decodeFromString<String>(body)
        val parsed = json.decodeFromString<PostResponse>(rawParsed)
        val document = Jsoup.parseBodyFragment(parsed.code)

        val animeList = document.select("li.post-item")
            .map(::popularAnimeFromElement)

        return AnimesPage(animeList, !parsed.hide_next)
    }

    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
        title = element.selectFirst("h2.post-title")!!.text().substringBefore(" Episode")
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val formBody = FormBody.Builder().apply {
            add("action", "tie_blocks_load_more")
            add("block[asc_or_desc]", "DESC")
            add("block[id][]", "14")
            add("block[number]", "10")
            addExtra(page)
        }.build()

        val formHeaders = headersBuilder().apply {
            add("Accept", "*/*")
            add("Host", baseUrl.toHttpUrl().host)
            add("Origin", baseUrl)
            add("Referer", "$baseUrl/ongoing-anime/")
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", formHeaders, formBody)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val subPage = filters.filterIsInstance<SubPageFilter>().first().toUriPart()
        val genreFilter = filters.filterIsInstance<GenreFilter>().first().toUriPart()

        if (query.isEmpty() && subPage.isNotEmpty()) {
            val formBody = FormBody.Builder().apply {
                add("action", "tie_blocks_load_more")
                add("block[asc_or_desc]", "DESC")
                add("block[id][]", "35")
                add("block[number]", "15")
                addExtra(page)
            }.build()
            val formHeaders = headersBuilder().apply {
                add("Accept", "*/*")
                add("Host", baseUrl.toHttpUrl().host)
                add("Origin", baseUrl)
                add("Referer", "$baseUrl/anime-movies/")
                add("X-Requested-With", "XMLHttpRequest")
            }.build()
            return POST("$baseUrl/wp-admin/admin-ajax.php", formHeaders, formBody)
        }

        return if (page == 1) {
            infoQuery = ""
            max = ""
            latestPost = ""
            layout = ""
            settings = ""
            currentReferer = ""

            val docHeaders = headersBuilder().apply {
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            }.build()

            if (query.isNotEmpty()) {
                val url = baseUrl.toHttpUrl().newBuilder().apply {
                    addPathSegment("")
                    addQueryParameter("s", query)
                }.build()
                currentReferer = url.toString()
                GET(url, docHeaders)
            } else if (genreFilter.isNotEmpty()) {
                currentReferer = "$baseUrl/category/$genreFilter"
                GET("$baseUrl/category/$genreFilter", docHeaders)
            } else {
                currentReferer = "$baseUrl/?s="
                GET("$baseUrl/?s=", docHeaders)
            }
        } else {
            val formBody = FormBody.Builder().apply {
                add("action", "tie_archives_load_more")
                add("query", infoQuery)
                add("max", max)
                add("page", page.toString())
                add("latest_post", latestPost)
                add("layout", layout)
                add("settings", settings)
            }.build()
            val formHeaders = headersBuilder().apply {
                add("Accept", "*/*")
                add("Host", baseUrl.toHttpUrl().host)
                add("Origin", baseUrl)
                add("Referer", currentReferer)
                add("X-Requested-With", "XMLHttpRequest")
            }.build()
            POST("$baseUrl/wp-admin/admin-ajax.php", formHeaders, formBody)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.toString().contains("admin-ajax")) {
            popularAnimeParse(response)
        } else {
            val document = response.asJsoup()
            val animeList = document.select("ul#posts-container > li.post-item").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                    thumbnail_url = element.selectFirst("img")!!.imgAttr()
                    title = element.selectFirst("h2.post-title")!!.text().substringBefore(" Episode")
                }
            }
            val hasNextPage = document.selectFirst("div.pages-nav > a[data-text=load more]") != null
            if (hasNextPage) {
                val container = document.selectFirst("ul#posts-container")!!
                val pagesNav = document.selectFirst("div.pages-nav > a")!!
                layout = container.attr("data-layout")
                infoQuery = pagesNav.attr("data-query")
                max = pagesNav.attr("data-max")
                latestPost = pagesNav.attr("data-latest")
                settings = container.attr("data-settings")
            }

            AnimesPage(animeList, hasNextPage)
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        SubPageFilter(),
        GenreFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Sub-page",
        arrayOf(
            Pair("<select>", ""),
            Pair("Anime Movies", "anime-movies"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Romance", "romance"),
            Pair("Ecchi", "ecchi"),
            Pair("School", "school"),
            Pair("Harem", "harem"),
            Pair("Sci-fi", "sci-fi"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Mystery", "mystery"),
            Pair("Military", "military"),
            Pair("Fantasy", "fantasy"),
            Pair("Isekai", "isekai"),
            Pair("Psychological", "psychological"),
            Pair("Shoujo", "shoujo"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Shounen", "shounen"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural-2"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val moreInfo = Jsoup.parseBodyFragment(
            document.selectFirst("div.toggle-content li p")?.html()?.replace("<br>", "br2n") ?: "",
        ).text().replace("br2n", "\n")
        val realDesc = document.select("div.stream-item ~ p").joinToString("\n\n") { it.text() }

        return SAnime.create().apply {
            status = document.selectFirst("div.toggle-content > ul > li:contains(Status)")?.let {
                parseStatus(it.text())
            } ?: SAnime.UNKNOWN
            description = realDesc + "\n\n$moreInfo"
            genre = document.selectFirst("div.toggle-content > ul > li:contains(Genres)")?.let {
                it.text().substringAfter("Genres").substringAfter("⋩ ").substringBefore(" ❀")
            }
            author = document.selectFirst("div.toggle-content > ul > li:contains(Studios)")?.let {
                it.text().substringAfter("Studios").substringAfter("⋩ ").substringBefore("⁃")
            }
        }
    }

    // ============================== Episodes ==============================

    private val googleDriveEpisodes by lazy { GoogleDriveEpisodes(client, headers) }
    private val indexExtractor by lazy { DriveIndexExtractor(client, headers) }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val trimNames = preferences.trimEpisodeName
        val blackListed = preferences.blKeywords

        document.select("div.toggle:has(> div.toggle-content > a[href*=drive.google.com]),div.toggle:has(a.shortc-button[href*=drive.google.com])").distinctBy { t ->
            getVideoPathsFromElement(t)
        }.forEach { season ->
            season.select("a[href*=drive.google.com]").distinctBy { it.text() }.forEach season@{
                if (blackListed.any { t -> it.text().contains(t, true) }) return@season
                val folderId = it.selectFirst("a[href*=drive.google.com]")!!.attr("abs:href").toHttpUrl().pathSegments[2]
                episodeList.addAll(
                    googleDriveEpisodes.getEpisodesFromFolder(folderId, "${getVideoPathsFromElement(season)} ${it.text()}", 2, trimNames),
                )
            }
        }

        document.select("div.wp-block-buttons > div.wp-block-button a[href*=drive.google.com]").distinctBy {
            it.text()
        }.forEach {
            if (blackListed.any { t -> it.text().contains(t, true) }) return@forEach
            val folderId = it.attr("abs:href").toHttpUrl().pathSegments[2]
            episodeList.addAll(
                googleDriveEpisodes.getEpisodesFromFolder(folderId, it.text(), 2, trimNames),
            )
        }

        document.select("div.toggle:has(> div.toggle-content > a[href*=workers.dev]),div.toggle:has(a.shortc-button[href*=workers.dev])").distinctBy { t ->
            getVideoPathsFromElement(t)
        }.forEach { season ->
            season.select("a[href*=workers.dev]").distinctBy { it.text() }.forEach season@{
                if (blackListed.any { t -> it.text().contains(t, true) }) return@season
                runCatching {
                    episodeList.addAll(
                        indexExtractor.getEpisodesFromIndex(it.attr("abs:href"), "${getVideoPathsFromElement(season)} ${it.text()}", trimNames),
                    )
                }
            }
        }

        return episodeList.reversed()
    }

    private fun getVideoPathsFromElement(element: Element): String {
        return element.selectFirst("h3")!!.text()
            .substringBefore("480p").substringBefore("720p").substringBefore("1080p")
            .replace("Download - ", "", true)
            .replace("Download The Anime From Worker ?", "", true)
            .replace("Download The Anime From Drive ", "", true)
            .trim()
    }

    // ============================ Video Links =============================

    private val googleDriveExtractor by lazy { GoogleDriveExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val httpUrl = episode.url.toHttpUrl()
        val host = httpUrl.host
        return if (host == "drive.google.com") {
            val id = httpUrl.queryParameter("id")!!
            googleDriveExtractor.videosFromUrl(id)
        } else if (host.contains("workers.dev")) {
            getIndexVideoUrl(episode.url)
        } else {
            throw Exception("Unsupported url: ${episode.url}")
        }
    }

    // ============================= Utilities ==============================

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    private fun FormBody.Builder.addExtra(page: Int): FormBody.Builder {
        add("block[pagi]", "show-more")
        add("block[excerpt]", "true")
        add("block[excerpt_length]", "15")
        add("block[post_meta]", "true")
        add("block[read_more]", "true")
        add("block[breaking_effect]", "reveal")
        add("block[sub_style]", "timeline")
        add("block[style]", "timeline")
        add("block[title_length]", "")
        add("block[media_overlay]", "")
        add("block[read_more_text]", "")
        add("page", page.toString())
        add("width", "single")
        return this
    }

    private fun getIndexVideoUrl(url: String): List<Video> {
        val doc = client.newCall(
            GET("$url?a=view"),
        ).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(videodomain)")?.data()
            ?: doc.selectFirst("script:containsData(downloaddomain)")?.data()
            ?: return listOf(Video(url, "Video", url))

        if (script.contains("\"second_domain_for_dl\":false")) {
            return listOf(Video(url, "Video", url))
        }

        val domainUrl = if (script.contains("videodomain", true)) {
            script
                .substringAfter("\"videodomain\":\"")
                .substringBefore("\"")
        } else {
            script
                .substringAfter("\"downloaddomain\":\"")
                .substringBefore("\"")
        }

        val videoUrl = if (domainUrl.isBlank()) {
            url
        } else {
            domainUrl + url.toHttpUrl().encodedPath
        }

        return listOf(Video(videoUrl, "Video", videoUrl))
    }

    @Serializable
    data class PostResponse(
        val hide_next: Boolean,
        val code: String,
    )

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Status: Currently Airing" -> SAnime.ONGOING
            "Status: Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private const val TRIM_EPISODE_NAME_KEY = "trim_episode"
        private const val TRIM_EPISODE_NAME_DEFAULT = true

        private const val KEYWORD_BLACKLIST_KEY = "blacklist_words"
        private const val KEYWORD_BLACKLIST_DEFAULT = "480p,720p"
    }

    private val SharedPreferences.trimEpisodeName
        get() = getBoolean(TRIM_EPISODE_NAME_KEY, TRIM_EPISODE_NAME_DEFAULT)

    private val SharedPreferences.blKeywords
        get() = getString(KEYWORD_BLACKLIST_KEY, KEYWORD_BLACKLIST_DEFAULT)!!
            .split(",")

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_EPISODE_NAME_KEY
            title = "Trim info from episode name"
            setDefaultValue(TRIM_EPISODE_NAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = KEYWORD_BLACKLIST_KEY
            title = "Blacklist keywords"
            summary = "Blacklist keywords, enter as a comma separated list"
            setDefaultValue(KEYWORD_BLACKLIST_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = newValue as String
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)
    }
}
