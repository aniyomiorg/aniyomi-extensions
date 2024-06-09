package eu.kanade.tachiyomi.animeextension.pt.animescx

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimesCX : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Animes CX"

    override val baseUrl = "https://animescx.com.br"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/doramas-legendados/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(popularAnimeSelector()).map(::popularAnimeFromElement)

        return AnimesPage(animes, doc.hasNextPage())
    }

    override fun popularAnimeSelector() = "div.listaAnimes_Riverlab_Container > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.infolistaAnimes_RiverLab")!!.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/doramas-em-lancamento/page/$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/page/$page/?s=$query", headers)

    override fun searchAnimeSelector() = "article.rl_episodios:has(.rl_AnimeIndexImg)"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        with(element.selectFirst("a")!!) {
            setUrlWithoutDomain(attr("href"))
            title = text()
        }

        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun searchAnimeNextPageSelector() = "a.next.page-numbers"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val infos = document.selectFirst("div.rl_anime_metadados")!!
        thumbnail_url = infos.selectFirst("img")?.absUrl("src")
        title = infos.selectFirst(".rl_nome_anime")!!.text()

        genre = infos.getInfo("Gêneros").replace(";", ",")
        status = when (infos.getInfo("Status")) {
            "Completo" -> SAnime.COMPLETED
            "Lançando", "Sendo Legendado!" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        description = infos.getInfo("Sinopse")
    }

    private fun Element.getInfo(text: String) =
        selectFirst(".rl_anime_meta:contains($text)")?.ownText().orEmpty()

    // ============================== Episodes ==============================
    override fun episodeListSelector() = ".rl_anime_episodios > article.rl_episodios"

    override fun episodeListParse(response: Response) = buildList {
        var doc = response.asJsoup()

        do {
            if (isNotEmpty()) {
                val url = doc.selectFirst("a.rl_anime_pagination:contains(›)")!!.absUrl("href")
                doc = client.newCall(GET(url, headers)).execute().asJsoup()
            }

            doc.select(episodeListSelector())
                .map(::episodeFromElement)
                .also(::addAll)
        } while (doc.hasNextPage())

        reverse()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val num = element.selectFirst("header")!!.text().substringAfterLast(' ')
        episode_number = num.toFloatOrNull() ?: 0F
        name = "Episódio $num"
        scanlator = element.selectFirst("div.rl_episodios_info:contains(Fansub)")?.ownText()

        url = json.encodeToString(
            buildJsonObject {
                element.select("div.rl_episodios_opcnome[onclick]").forEach {
                    putJsonArray(it.text(), { getVideoHosts(it.attr("onclick"), element) })
                }
            },
        )
    }

    private fun JsonArrayBuilder.getVideoHosts(onclick: String, element: Element) {
        val itemId = onclick.substringAfterLast("rlToggle('").substringBefore("'")
        element.select("#$itemId a.rl_episodios_link").toList()
            .filter { it.text() != "Mega" }
            .forEach { el ->
                val urlId = el.attr("href").substringAfter("id=")
                val url = String(Base64.decode(urlId, Base64.DEFAULT)).reversed()
                add(json.encodeToJsonElement(VideoHost.serializer(), VideoHost(el.text(), url)))
            }
    }

    @Serializable
    class VideoHost(val name: String, val url: String)

    // ============================ Video Links =============================
    private val gdriveExtractor by lazy { GoogleDriveExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val data = episode.url.parseAs<Map<String, List<VideoHost>>>()

        return data.flatMap { (quality, items) ->
            items.flatMap {
                when (it.name) {
                    "MediaFire" -> {
                        val doc = client.newCall(GET(it.url, headers)).await().asJsoup()
                        val url = doc.selectFirst("a#downloadButton")?.attr("href")
                        url?.let { listOf(Video(url, "Mediafire - $quality", url, headers)) }.orEmpty()
                    }
                    "Google Drive" -> {
                        GDRIVE_REGEX.find(it.url)?.groupValues?.get(0)
                            ?.let { gdriveExtractor.videosFromUrl(it, "GDrive - $quality") }
                            .orEmpty()
                    }
                    else -> emptyList()
                }
            }
        }.sort()
    }
    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException()
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

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun String.getPage() = substringAfterLast("/page/").substringBefore("/")

    private fun Document.hasNextPage() =
        selectFirst("a.rl_anime_pagination:last-child")
            ?.let { it.attr("href").getPage() != location().getPage() }
            ?: false

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val GDRIVE_REGEX = Regex("""[\w-]{28,}""")

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "FULL HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("MP4", "SD", "HD", "FULL HD")
    }
}
