package eu.kanade.tachiyomi.animeextension.fr.otakufr

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.otakufr.extractors.UpstreamExtractor
import eu.kanade.tachiyomi.animeextension.fr.otakufr.extractors.VidbmExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class OtakuFR : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "OtakuFR"

    override val baseUrl = "https://otakufr.co"

    override val lang = "fr"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/en-cours".addPage(page), headers)

    override fun popularAnimeSelector(): String = "div.list > article.card"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val a = element.selectFirst("a.episode-name")!!

        return SAnime.create().apply {
            thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
            setUrlWithoutDomain(a.attr("href"))
            title = a.text()
        }
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/toute-la-liste-affiches/?q=$query".addPage(page), headers)
            genreFilter.state != 0 -> GET("$baseUrl${genreFilter.toUriPart()}".addPage(page), headers)
            subPageFilter.state != 0 -> GET("$baseUrl${subPageFilter.toUriPart()}".addPage(page), headers)
            else -> throw Exception("Either search something or select a filter")
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val infoDiv = document.selectFirst("article.card div.episode")!!

        return SAnime.create().apply {
            status = infoDiv.selectFirst("li:contains(Statut)")?.let { parseStatus(it.ownText()) } ?: SAnime.UNKNOWN
            genre = infoDiv.select("li:contains(Genre:) ul li").joinToString(", ") { it.text() }
            author = infoDiv.selectFirst("li:contains(Studio d\\'animation)")?.ownText()
            description = buildString {
                append(infoDiv.select("> p:not(:has(strong)):not(:empty)").joinToString("\n\n") { it.text() })
                append("\n")
                infoDiv.selectFirst("li:contains(Autre Nom)")?.let { append("\n${it.text()}") }
                infoDiv.selectFirst("li:contains(Auteur)")?.let { append("\n${it.text()}") }
                infoDiv.selectFirst("li:contains(Réalisateur)")?.let { append("\n${it.text()}") }
                infoDiv.selectFirst("li:contains(Type)")?.let { append("\n${it.text()}") }
                infoDiv.selectFirst("li:contains(Sortie initiale)")?.let { append("\n${it.text()}") }
                infoDiv.selectFirst("li:contains(Durée)")?.let { append("\n${it.text()}") }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "div.list-episodes > a"

    override fun episodeFromElement(element: Element): SEpisode {
        val epText = element.ownText()

        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            name = epText
            episode_number = Regex(" ([\\d.]+) (?:Vostfr|VF)").find(epText)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
                ?: 1F
            date_upload = element.selectFirst("span")
                ?.text()
                ?.let(::parseDate)
                ?: 0L
        }
    }

    // ============================ Video Links =============================

    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidbmExtractor by lazy { VidbmExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val serversList = document.select("div.tab-content iframe[src]").mapNotNull {
            val url = it.attr("abs:src")

            if (url.contains("parisanime.com")) {
                val docHeaders = headers.newBuilder().apply {
                    add("Accept", "*/*")
                    add("Host", url.toHttpUrl().host)
                    add("Referer", url)
                    add("X-Requested-With", "XMLHttpRequest")
                }.build()

                val newDoc = client.newCall(
                    GET(url, headers = docHeaders),
                ).execute().asJsoup()
                newDoc.selectFirst("div[data-url]")?.attr("data-url")
            } else {
                url
            }
        }

        return serversList.parallelCatchingFlatMapBlocking(::getHosterVideos)
    }

    private fun getHosterVideos(host: String): List<Video> {
        return when {
            host.startsWith("https://doo") -> doodExtractor.videosFromUrl(host, quality = "Doodstream")
            host.contains("streamwish") -> streamwishExtractor.videosFromUrl(host)
            host.contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(host)
            host.contains("vadbam") -> vidbmExtractor.videosFromUrl(host)
            host.contains("sendvid.com") -> sendvidExtractor.videosFromUrl(host)
            host.contains("ok.ru") -> okruExtractor.videosFromUrl(host)
            host.contains("upstream") -> upstreamExtractor.videosFromUrl(host)
            host.startsWith("https://voe") -> voeExtractor.videosFromUrl(host)
            else -> emptyList()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun String.addPage(page: Int): String {
        return if (page == 1) {
            this
        } else {
            this.toHttpUrl().newBuilder().apply {
                addPathSegment("page")
                addPathSegment(page.toString())
            }.build().toString()
        }
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "En cours" -> SAnime.ONGOING
            "Terminé" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale.FRENCH)
        }

        private val HOSTERS = arrayOf(
            "Streamwish",
            "Doodstream",
            "Sendvid",
            "Vidbm",
            "Okru",
            "Voe",
            "Sibnet",
            "Upstream",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Streamwish"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = HOSTERS
            entryValues = HOSTERS
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

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        SubPageFilter(),
    )

    // copy($("div.dropdown-menu > a.dropdown-item").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).attr('href').trim().slice(18)}")`).get().join(',\n'))
    // on /
    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/genre/action/"),
            Pair("Aventure", "/genre/aventure/"),
            Pair("Comedie", "/genre/comedie/"),
            Pair("Crime", "/genre/crime/"),
            Pair("Démons", "/genre/demons/"),
            Pair("Drame", "/genre/drame/"),
            Pair("Ecchi", "/genre/ecchi/"),
            Pair("Espace", "/genre/espace/"),
            Pair("Fantastique", "/genre/fantastique/"),
            Pair("Gore", "/genre/gore/"),
            Pair("Harem", "/genre/harem/"),
            Pair("Historique", "/genre/historique/"),
            Pair("Horreur", "/genre/horreur/"),
            Pair("Isekai", "/genre/isekai/"),
            Pair("Jeux", "/genre/jeu/"),
            Pair("L'école", "/genre/lecole/"),
            Pair("Magical girls", "/genre/magical-girls/"),
            Pair("Magie", "/genre/magie/"),
            Pair("Martial Arts", "/genre/martial-arts/"),
            Pair("Mecha", "/genre/mecha/"),
            Pair("Militaire", "/genre/militaire/"),
            Pair("Musique", "/genre/musique/"),
            Pair("Mysterieux", "/genre/mysterieux/"),
            Pair("Parodie", "/genre/Parodie/"),
            Pair("Police", "/genre/police/"),
            Pair("Psychologique", "/genre/psychologique/"),
            Pair("Romance", "/genre/romance/"),
            Pair("Samurai", "/genre/samurai/"),
            Pair("Sci-Fi", "/genre/sci-fi/"),
            Pair("Seinen", "/genre/seinen/"),
            Pair("Shoujo", "/genre/shoujo/"),
            Pair("Shoujo Ai", "/genre/shoujo-ai/"),
            Pair("Shounen", "/genre/shounen/"),
            Pair("Shounen Ai", "/genre/shounen-ai/"),
            Pair("Sport", "/genre/sport/"),
            Pair("Super Power", "/genre/super-power/"),
            Pair("Surnaturel", "/genre/surnaturel/"),
            Pair("Suspense", "/genre/suspense/"),
            Pair("Thriller", "/genre/thriller/"),
            Pair("Tranche de vie", "/genre/tranche-de-vie/"),
            Pair("Vampire", "/genre/vampire/"),
        ),
    )

    private class SubPageFilter : UriPartFilter(
        "Sup-page",
        arrayOf(
            Pair("<select>", ""),
            Pair("Terminé", "/termine/"),
            Pair("Film", "/film/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
