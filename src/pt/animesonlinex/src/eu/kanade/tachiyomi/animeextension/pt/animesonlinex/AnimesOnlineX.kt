package eu.kanade.tachiyomi.animeextension.pt.animesonlinex

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.animeextension.pt.animesonlinex.extractors.GenericExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesonlinex.extractors.GuiaNoticiarioBypasser
import eu.kanade.tachiyomi.animeextension.pt.animesonlinex.extractors.QualitiesExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class AnimesOnlineX : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesOnlineX"

    override val baseUrl by lazy {
        preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT)!!
    }
    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.w_item_a > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")
        val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        anime.setUrlWithoutDomain(url)
        anime.title = img.attr("alt")
        anime.thumbnail_url = img.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        return AnimesPage(animes, false)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "ul.episodios > li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.asJsoup())
        val epList = doc.select(episodeListSelector())
        if (epList.size < 1) {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(response.request.url.toString())
            episode.episode_number = 1F
            episode.name = "Filme"
            return listOf(episode)
        }
        return epList.reversed().map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val origName = element.selectFirst("div.numerando").text()

        episode.episode_number = origName.substringAfter("- ")
            .replace("-", "")
            .toFloat() + if ("Dub" in origName) 0.5F else 0F
        episode.name = "Temp " + origName.replace(" - ", ": Ep ")
        episode.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        episode.date_upload = element.selectFirst("span.date")
            ?.text()
            ?.toDate() ?: 0L
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val urls = document.select("div.source-box:not(#source-player-trailer) div.pframe a")
            .map { it.attr("href") }
        val resolutions = document.select("ul#playeroptionsul > li:not(#player-option-trailer)")
            .map {
                val player = it.selectFirst("span.title").text()
                val expectedQuality = it.selectFirst("span.resol")
                    .text()
                    .replace("HD", "720p")
                "$player - $expectedQuality"
            }
        val videoList = mutableListOf<Video>()
        urls.forEachIndexed { index, it ->
            val url = GuiaNoticiarioBypasser(client, headers).fromUrl(it)
            val videos = runCatching { getPlayerVideos(url, resolutions.get(index)) }
                .getOrNull() ?: emptyList<Video>()

            videoList.addAll(videos)
        }
        return videoList
    }

    private fun getPlayerVideos(url: String, qualityStr: String): List<Video> {

        return when {
            "/vplayer/?source" in url || "embed.redecine.org" in url -> {
                val videoUrl = url.getParam("source") ?: url.getParam("url")!!
                if (".m3u8" in videoUrl) {
                    QualitiesExtractor(client, headers)
                        .getVideoList(videoUrl, qualityStr)
                } else {
                    listOf(Video(videoUrl, qualityStr, videoUrl, headers))
                }
            }
            "/firestream/?" in url || "doramasonline.org" in url ||
                "anicdn.org" in url || "animeshd.org" in url ->
                GenericExtractor(client, headers).getVideoList(url, qualityStr)
            else -> emptyList<Video>()
        }
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/animes/$slug"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()

        val animes = when {
            "/generos/" in url -> {
                document.select(latestUpdatesSelector()).map { element ->
                    popularAnimeFromElement(element)
                }
            }
            else -> {
                document.select(searchAnimeSelector()).map { element ->
                    searchAnimeFromElement(element)
                }
            }
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$slug", headers))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response, slug)
                }
        } else {
            val params = AOXFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    override fun getFilterList(): AnimeFilterList = AOXFilters.filterList

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AOXFilters.FilterSearchParams): Request {
        return when {
            query.isBlank() -> {
                val genre = filters.genre
                var url = "$baseUrl/generos/$genre"
                if (page > 1) url += "/page/$page"
                GET(url, headers)
            }
            else -> GET("$baseUrl/page/$page/?s=$query", headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    override fun searchAnimeSelector(): String = "div.result-item div.details div.title a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)
        val sheader = doc.selectFirst("div.sheader")
        val img = sheader.selectFirst("div.poster > img")
        anime.thumbnail_url = img.attr("src")
        val name = sheader.selectFirst("div.data > h1").text()
        anime.title = name
        val status = sheader.selectFirst("div.alert")?.text()
        anime.status = parseStatus(status)
        anime.genre = sheader.select("div.data > div.sgeneros > a")
            .joinToString(", ") { it.text() }
        val info = doc.selectFirst("div#info")
        var description = info.select("div.wp-content > p")
            .joinToString("\n") { it.text() }
            .substringBefore("Assistir $name") + "\n"

        status?.let { description += "\n$it" }
        info.getInfo("Título")?.let { description += "$it" }
        info.getInfo("Ano")?.let { description += "$it" }
        info.getInfo("Temporadas")?.let { description += "$it" }
        info.getInfo("Episódios")?.let { description += "$it" }
        anime.description = description
        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.fa-chevron-right"

    override fun latestUpdatesSelector(): String = "div.content article > div.poster"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodio/page/$page", headers)

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_LIST
            entryValues = PREF_QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = PREF_BASE_URL_TITLE
            summary = PREF_BASE_URL_SUMMARY
            setDefaultValue(PREF_BASE_URL_DEFAULT)
            dialogTitle = PREF_BASE_URL_TITLE
            dialogMessage = "Padrão: $PREF_BASE_URL_DEFAULT"

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val res = preferences.edit()
                        .putString(key, newValue as String)
                        .commit()
                    Toast.makeText(
                        screen.context,
                        RESTART_ANIYOMI,
                        Toast.LENGTH_LONG
                    ).show()
                    res
                }.getOrNull() ?: false
            }
        }

        screen.addPreference(baseUrlPref)

        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    private val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-bars"

    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        if (menu != null) {
            val originalUrl = menu.parent().attr("href")
            val req = client.newCall(GET(originalUrl, headers)).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status) {
            null -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun Element.getInfo(substring: String): String? {
        val target = this.selectFirst("div.custom_fields:contains($substring)")
            ?: return null
        val key = target.selectFirst("b").text()
        val value = target.selectFirst("span").text()
        return "\n$key: $value"
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private fun String.getParam(param: String): String? {
        return Uri.parse(this).getQueryParameter(param)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (quality in video.quality) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "slug:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_LIST = arrayOf("480p", "720p", "1080p")

        private val PREF_BASE_URL_KEY = "base_url_${AppInfo.getVersionName()}"
        private const val PREF_BASE_URL_TITLE = "URL atual do site"
        private const val PREF_BASE_URL_SUMMARY = "Para uso temporário, essa configuração será apagada ao atualizar a extensão"
        private const val PREF_BASE_URL_DEFAULT = "https://animesonlinex.nz"

        private const val RESTART_ANIYOMI = "Reinicie o Aniyomi pra aplicar as configurações"
    }
}
