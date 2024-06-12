package eu.kanade.tachiyomi.animeextension.tr.hentaizm

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.hentaizm.extractors.VideaExtractor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HentaiZM : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "HentaiZM"

    override val baseUrl = "https://www.hentaizm.fun"

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    init {
        runBlocking {
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("user", "demo")
                    .add("pass", "demo") // peak security
                    .add("redirect_to", baseUrl)
                    .build()

                val headers = headersBuilder()
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()

                client.newCall(POST("$baseUrl/giris", headers, body)).execute()
                    .close()
            }
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en-cok-izlenenler/page/$page", headers)

    override fun popularAnimeParse(response: Response) =
        super.popularAnimeParse(response).let { page ->
            val animes = page.animes.distinctBy { it.url }
            AnimesPage(animes, page.hasNextPage)
        }

    override fun popularAnimeSelector() = "div.moviefilm"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst("div.movief > a")!!.text()
            .substringBefore(". Bölüm")
            .substringBeforeLast(" ")
        element.selectFirst("img")!!.attr("abs:src").also {
            thumbnail_url = it
            val slug = it.substringAfterLast("/").substringBefore(".")
            setUrlWithoutDomain("/hentai-detay/$slug")
        }
    }

    override fun popularAnimeNextPageSelector() = "span.current + a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/yeni-eklenenler?c=${page - 1}", headers)

    override fun latestUpdatesParse(response: Response) =
        super.latestUpdatesParse(response).let { page ->
            val animes = page.animes.distinctBy { it.url }
            AnimesPage(animes, page.hasNextPage)
        }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "a[rel=next]:contains(Sonraki Sayfa)"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/hentai-detay/$id"))
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val content = document.selectFirst("div.filmcontent")!!
        title = content.selectFirst("h1")!!.text()
        thumbnail_url = content.selectFirst("img")!!.attr("abs:src")
        genre = content.select("tr:contains(Hentai Türü) > td > a").eachText().joinToString()
        description = content.selectFirst("tr:contains(Özet) + tr > td")
            ?.text()
            ?.takeIf(String::isNotBlank)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div#Bolumler li > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.text().also {
            val num = it.substringBeforeLast(". Bölüm", "")
                .substringAfterLast(" ")
                .ifBlank { "1" }

            episode_number = num.toFloatOrNull() ?: 1F
            name = "$num. Bölüm"
        }
    }

    // ============================ Video Links =============================
    private val videaExtractor by lazy { VideaExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videaItem = doc.selectFirst("div.alternatif a:contains(Videa)")!!
        val path = videaItem.attr("onclick").substringAfter("../../").substringBefore("'")
        val req = client.newCall(GET("$baseUrl/$path", headers)).execute()
            .asJsoup()
        val videaUrl = req.selectFirst("iframe")!!.attr("abs:src")
        return videaExtractor.videosFromUrl(videaUrl)
    }

    private val qualityRegex by lazy { Regex("""(\d+)p""") }
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),

        ).reversed()
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
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
