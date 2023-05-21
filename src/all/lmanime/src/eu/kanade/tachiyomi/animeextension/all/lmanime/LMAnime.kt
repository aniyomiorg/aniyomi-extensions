package eu.kanade.tachiyomi.animeextension.all.lmanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.lmanime.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class LMAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "LMAnime"

    override val baseUrl = "https://lmanime.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val ahref = element.selectFirst("h4 > a.series")!!
            setUrlWithoutDomain(ahref.attr("href"))
            title = ahref.text()
            thumbnail_url = element.selectFirst("img")!!.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector() = null

    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.serieslist.wpop-alltime li"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.asJsoup())
        return doc.select(episodeListSelector()).map(::episodeFromElement)
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            element.selectFirst("div.epl-title")!!.text().let {
                name = it
                episode_number = it.substringBefore(" (")
                    .substringAfterLast(" ")
                    .toFloatOrNull() ?: 0F
            }

            date_upload = element.selectFirst("div.epl-date")?.text().toDate()
        }
    }

    override fun episodeListSelector() = "div.eplister > ul > li > a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            title = doc.selectFirst("h1.entry-title")!!.text()
            thumbnail_url = doc.selectFirst("div.thumb > img")!!.attr("src")

            val infos = doc.selectFirst("div.info-content")!!
            genre = infos.select("div.genxed > a").eachText().joinToString()
            status = parseStatus(infos.getInfo("Status"))
            artist = infos.getInfo("Studio")
            author = infos.getInfo("Fansub")

            description = buildString {
                doc.selectFirst("div.entry-content")?.text()?.let {
                    append("$it\n\n")
                }

                infos.select("div.spe > span").eachText().forEach {
                    append("$it\n")
                }
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "select.mirror > option[data-index]"

    override fun videoListParse(response: Response): List<Video> {
        val items = response.asJsoup().select(videoListSelector())
        val allowed = preferences.getStringSet(PREF_ALLOWED_LANGS_KEY, PREF_ALLOWED_LANGS_DEFAULT)!!
        return items
            .filter { element ->
                val text = element.text()
                allowed.any { it in text }
            }.parallelMap {
                val language = it.text().substringBefore(" ")
                val url = getHosterUrl(it.attr("value"))
                getVideoList(url, language)
            }.flatten()
    }

    private fun getHosterUrl(encodedStr: String): String {
        return Base64.decode(encodedStr, Base64.DEFAULT)
            .let(::String) // bytearray -> string
            .substringAfter("iframe")
            .substringAfter("src=\"")
            .substringBefore('"')
            .let {
                // sometimes the url doesnt specify its protocol
                if (it.startsWith("http")) {
                    it
                } else {
                    "https:$it"
                }
            }
    }

    private fun getVideoList(url: String, language: String): List<Video> {
        return runCatching {
            when {
                "ok.ru" in url ->
                    OkruExtractor(client).videosFromUrl(url, "$language -")
                "fembed" in url ->
                    FembedExtractor(client).videosFromUrl(url, "$language -")
                "dailymotion.com" in url ->
                    DailymotionExtractor(client).videosFromUrl(url, "Dailymotion ($language)")
                else -> null
            }
        }.getOrNull() ?: emptyList()
    }

    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = "div.pagination a.next"

    override fun getFilterList() = LMAnimeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val genre = LMAnimeFilters.getGenre(filters)
            GET("$baseUrl/genres/$genre/page/$page")
        }
    }

    override fun searchAnimeSelector() = "div.listupd article a.tip"

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.selectFirst("div.tt")!!.ownText()
            thumbnail_url = element.selectFirst("img")!!.attr("src")
        }
    }

    override fun latestUpdatesNextPageSelector() = "div.hpage a:contains(Next)"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun latestUpdatesSelector() = "div.listupd.normal article a.tip"

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
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
        }

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_ENTRIES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val allowedPref = MultiSelectListPreference(screen.context).apply {
            key = PREF_ALLOWED_LANGS_KEY
            title = PREF_ALLOWED_LANGS_TITLE
            entries = PREF_ALLOWED_LANGS_ENTRIES
            entryValues = PREF_ALLOWED_LANGS_ENTRIES
            setDefaultValue(PREF_ALLOWED_LANGS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(langPref)
        screen.addPreference(allowedPref)
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("div.naveps a:contains(All episodes)")?.let { link ->
            client.newCall(GET(link.attr("href")))
                .execute()
                .asJsoup()
        } ?: document
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completed" -> SAnime.COMPLETED
            "Ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(text: String): String? {
        return selectFirst("span:contains($text)")
            ?.run {
                selectFirst("a")?.text() ?: ownText()
            }
    }

    private fun String?.toDate(): Long {
        return this?.let {
            runCatching {
                DATE_FORMATTER.parse(this)?.time
            }.getOrNull()
        } ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(lang) },
            ),
        ).reversed()
    }

    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private val DATE_FORMATTER by lazy { SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH) }

        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("144p", "288p", "480p", "720p", "1080p")

        private const val PREF_LANG_KEY = "pref_language"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "English"
        private val PREF_LANG_ENTRIES = arrayOf(
            "English",
            "Español",
            "Indonesian",
            "Portugués",
            "Türkçe",
            "العَرَبِيَّة",
            "ไทย",
        )

        private const val PREF_ALLOWED_LANGS_KEY = "pref_allowed_languages"
        private const val PREF_ALLOWED_LANGS_TITLE = "Allowed languages to fetch videos"
        private val PREF_ALLOWED_LANGS_ENTRIES = PREF_LANG_ENTRIES
        private val PREF_ALLOWED_LANGS_DEFAULT = PREF_ALLOWED_LANGS_ENTRIES.toSet()
    }
}
