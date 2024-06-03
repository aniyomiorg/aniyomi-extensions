package eu.kanade.tachiyomi.animeextension.pt.betteranime

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.betteranime.dto.ComponentsDto
import eu.kanade.tachiyomi.animeextension.pt.betteranime.dto.PayloadData
import eu.kanade.tachiyomi.animeextension.pt.betteranime.extractors.BetterAnimeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class BetterAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Better Anime"

    override val baseUrl = "https://betteranime.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(LoginInterceptor(network.client, baseUrl, headers))
        .build()

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    // The site doesn't have a true popular anime tab,
    // so we use the latest added anime page instead.
    override fun popularAnimeParse(response: Response) = latestUpdatesParse(response)

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ultimosAdicionados?page=$page", headers)

    override fun popularAnimeSelector() = TODO()
    override fun popularAnimeFromElement(element: Element) = TODO()
    override fun popularAnimeNextPageSelector() = TODO()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ultimosLancamentos?page=$page", headers)

    override fun latestUpdatesSelector() = "div.list-animes article"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val img = element.selectFirst("img")!!
        val url = element.selectFirst("a")?.attr("href")!!
        setUrlWithoutDomain(url)
        title = element.selectFirst("h3")?.text()!!
        thumbnail_url = "https:" + img.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = "ul.pagination li.page-item:contains(›):not(.disabled)"

    // =============================== Search ===============================
    override fun getFilterList() = BAFilters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH_PATH)) {
            val path = query.removePrefix(PREFIX_SEARCH_PATH)
            client.newCall(GET("$baseUrl/$path", headers))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val calls = buildJsonArray {
            val payloadSerializer = PayloadData.serializer()
            add(json.encodeToJsonElement(payloadSerializer, PayloadData(method = "search")))
            add(
                json.encodeToJsonElement(
                    payloadSerializer,
                    PayloadData(
                        method = "gotoPage",
                        params = listOf(
                            JsonPrimitive(page),
                            JsonPrimitive("page"),
                        ),
                    ),
                ),
            )
        }

        val params = BAFilters.getSearchParameters(filters)
        val updates = buildJsonObject {
            if (params.genres.isNotEmpty()) {
                putJsonArray("byGenres") {
                    params.genres.forEach { add(JsonPrimitive(it)) }
                }
            }
            listOf(
                params.year to "byYear",
                params.language to "byLanguage",
                query to "searchTerm",
            ).forEach { if (it.first.isNotEmpty()) put(it.second, it.first) }
        }

        if (wireToken.isBlank()) {
            updateSnapshot(GET("$baseUrl/pesquisa", headers))
        }

        val data = buildJsonObject {
            put("_token", wireToken)
            putJsonArray("components") {
                add(
                    buildJsonObject {
                        put("calls", calls)
                        put("snapshot", snapshot)
                        put("updates", updates)
                    },
                )
            }
        }
        val reqBody = json.encodeToString(JsonObject.serializer(), data).toRequestBody("application/json".toMediaType())

        val headers = headersBuilder()
            .add("x-livewire", "true")
            .add("x-csrf-token", wireToken)
            .build()
        return POST("$baseUrl/livewire/update", headers, reqBody)
    }

    private var snapshot = ""
    private var wireToken = ""

    private fun updateSnapshot(request: Request) {
        val document = client.newCall(request).execute().asJsoup()
        val wireElement = document.selectFirst("[wire:snapshot]")!!
        snapshot = wireElement.attr("wire:snapshot")
        wireToken = document.selectFirst("script[data-csrf]")!!.attr("data-csrf")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val data = json.decodeFromString<ComponentsDto>(body)
        val html = data.components.firstOrNull()?.effects?.html?.unescape().orEmpty()
        val document = Jsoup.parse(html)
        val animes = document.select(searchAnimeSelector()).map(::searchAnimeFromElement)
        val hasNext = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())

        val infos = doc.selectFirst("div.infos_left > div.anime-info")!!
        val img = doc.selectFirst("div.infos-img > img")!!
        thumbnail_url = "https:" + img.attr("src")
        title = img.attr("alt")
        genre = infos.select("div.anime-genres > a")
            .eachText()
            .joinToString()
        author = infos.getInfo("Produtor")
        artist = infos.getInfo("Estúdio")
        status = parseStatus(infos.getInfo("Estado"))
        description = buildString {
            append(infos.selectFirst("div.anime-description")!!.text() + "\n\n")
            infos.select(">p").eachText().forEach { append("$it\n") }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul#episodesList > li.list-group-item-action > a"

    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).reversed()

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val episodeName = element.text()
        setUrlWithoutDomain(element.attr("href"))
        name = episodeName
        episode_number = episodeName.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    private val extractor by lazy { BetterAnimeExtractor(client, baseUrl, json) }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        return extractor.videoListFromHtml(html)
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("div.anime-title a")?.let { link ->
            client.newCall(GET(link.attr("href"), headers))
                .execute()
                .asJsoup()
        } ?: document
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("p:containsOwn($key) > span")
            ?.text()
            ?.trim()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        const val PREFIX_SEARCH_PATH = "path:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("480p", "720p", "1080p")
    }
}
