package eu.kanade.tachiyomi.animeextension.de.animebase

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.OkHttpClient
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

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.table-responsive a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/favorites", headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href").replace("/link/", "/anime/"))
        thumbnail_url = element.selectFirst("div.thumbnail img")?.absUrl("src")
        title = element.selectFirst("div.caption h3")!!.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("Not used")

    override fun latestUpdatesSelector() = throw Exception("Not used")

    // =============================== Search ===============================
    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/searching", headers)).execute()
            .use { it.asJsoup() }
            .selectFirst("form > input[name=_token]")!!
            .attr("value")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder()
            .add("_token", searchToken)
            .add("_token", searchToken)
            .add("name_serie", query)
            .add("jahr", "")
            .build()
        return POST("$baseUrl/searching", headers, body)
    }

    override fun searchAnimeSelector(): String = "div.col-lg-9.col-md-8 div.box-body a"

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = null

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
        super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.tab-content > div > div.panel"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val epname = element.selectFirst("h3")?.text() ?: "Episode 1"
        name = epname
        episode_number = epname.substringBefore(":").substringAfter(" ").toFloatOrNull() ?: 0F
        val selectorClass = element.classNames().first { it.startsWith("episode-div") }
        setUrlWithoutDomain(element.baseUri() + "?selector=div.panel.$selectorClass")
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response) =
        throw Exception("This source only uses StreamSB as video hoster, and StreamSB is down.")

    override fun List<Video>.sort(): List<Video> {
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(lang) },
        ).reversed()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_sub"
        private const val PREF_LANG_TITLE = "Standardmäßig Sub oder Dub?"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("Sub", "Dub")
        private val PREF_LANG_VALUES = arrayOf("SUB", "DUB")
    }
}
