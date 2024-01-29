package eu.kanade.tachiyomi.animeextension.id.kuramanime

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Kuramanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val name = "Kuramanime"

    override val baseUrl = "https://kuramanime.pro"

    override val lang = "id"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?page=$page")

    override fun popularAnimeSelector() = "div.product__item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("a > div")?.attr("data-setbg")
        title = element.selectFirst("div.product__item__text > h5")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "div.product__pagination > a:last-child"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?order_by=updated&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/anime?search=$query&page=$page")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anime__details__pic")?.attr("data-setbg")

        val details = document.selectFirst("div.anime__details__text")!!

        title = details.selectFirst("div > h3")!!.text().replace("Judul: ", "")

        val infos = details.selectFirst("div.anime__details__widget")!!
        artist = infos.select("li:contains(Studio:) > a").eachText().joinToString().takeUnless(String::isEmpty)
        status = parseStatus(infos.selectFirst("li:contains(Status:) > a")?.text())

        genre = infos.select("li:contains(Genre:) > a, li:contains(Tema:) > a, li:contains(Demografis:) > a")
            .eachText()
            .joinToString { it.trimEnd(',', ' ') }
            .takeUnless(String::isEmpty)

        description = buildString {
            details.selectFirst("p#synopsisField")?.text()?.also(::append)

            details.selectFirst("div.anime__details__title > span")?.text()
                ?.also { append("\n\nAlternative names: $it\n") }

            infos.select("ul > li").eachText().forEach { append("\n$it") }
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Sedang Tayang" -> SAnime.ONGOING
            "Selesai Tayang" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val html = document.selectFirst(episodeListSelector())?.attr("data-content")
            ?: return emptyList()

        val newDoc = response.asJsoup(html)

        val limits = newDoc.select("a.btn-secondary")

        return when {
            limits.isEmpty() -> { // 12 episodes or less
                newDoc.select("a")
                    .filterNot { it.attr("href").contains("batch") }
                    .map(::episodeFromElement)
                    .reversed()
            }
            else -> { // More than 12 episodes
                val (start, end) = limits.eachText().take(2).map {
                    it.filter(Char::isDigit).toInt()
                }

                val location = document.location()

                (end downTo start).map { episodeNumber ->
                    SEpisode.create().apply {
                        name = "Ep $episodeNumber"
                        episode_number = episodeNumber.toFloat()
                        setUrlWithoutDomain("$location/episode/$episodeNumber")
                    }
                }
            }
        }
    }

    override fun episodeListSelector() = "a#episodeLists"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = name.filter(Char::isDigit).toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "video#player > source"

    // Shall we add "archive", "archive-v2"? archive.org usually returns a beautiful 403 xD
    private val supportedHosters = listOf("kuramadrive", "kuramadrive-v2", "streamtape")

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        val scriptData = doc.selectFirst("[data-js]")?.attr("data-js")
            ?.let(::getScriptData)
            ?: return emptyList()

        val csrfToken = doc.selectFirst("meta[name=csrf-token]")
            ?.attr("csrf-token")
            ?: return emptyList()

        val servers = doc.select("select#changeServer > option")
            .map { it.attr("value") to it.text().substringBefore(" (") }
            .filter { supportedHosters.contains(it.first) }

        val episodeUrl = response.request.url

        val headers = headersBuilder()
            .set("Referer", episodeUrl.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return servers.flatMap { (server, serverName) ->
            runCatching {
                val newHeaders = headers.newBuilder()
                    .set("X-CSRF-TOKEN", csrfToken)
                    .set("X-Fuck-ID", scriptData.tokenId)
                    .set("X-Request-ID", getRandomString())
                    .set("X-Request-Index", "0")
                    .build()

                val hash = client.newCall(GET("$baseUrl/" + scriptData.authPath, newHeaders)).execute()
                    .body.string()
                    .trim('"')

                val newUrl = episodeUrl.newBuilder()
                    .addQueryParameter(scriptData.tokenParam, hash)
                    .addQueryParameter(scriptData.serverParam, server)
                    .build()

                val playerDoc = client.newCall(GET(newUrl.toString(), headers)).execute()
                    .asJsoup()

                if (server == "streamtape") {
                    val url = playerDoc.selectFirst("div.video-content iframe")!!.attr("src")
                    streamtapeExtractor.videosFromUrl(url)
                } else {
                    playerDoc.select("video#player > source").map {
                        val src = it.attr("src")
                        Video(src, "${it.attr("size")}p - $serverName", src)
                    }
                }
            }.getOrElse { emptyList<Video>() }
        }
    }

    private fun getScriptData(scriptName: String): ScriptDataDto? {
        val scriptUrl = "$baseUrl/assets/js/$scriptName.js"
        val scriptCode = client.newCall(GET(scriptUrl, headers)).execute()
            .body.string()

        // Trust me, I hate this too.
        val scriptJson = scriptCode.lines()
            .filter { it.contains(": '") || it.contains(": \"") }
            .map {
                val (key, value) = it.split(":", limit = 2).map(String::trim)
                val fixedValue = value.replace("'", "\"").substringBeforeLast(',')
                "\"$key\": $fixedValue"
            }.joinToString(prefix = "{", postfix = "}")

        return runCatching {
            json.decodeFromString<ScriptDataDto>(scriptJson)
        }.onFailure { it.printStackTrace() }.getOrNull()
    }

    @Serializable
    internal data class ScriptDataDto(
        @SerialName("MIX_PREFIX_AUTH_ROUTE_PARAM")
        private val authPathPrefix: String,

        @SerialName("MIX_AUTH_ROUTE_PARAM")
        private val authPathSuffix: String,

        @SerialName("MIX_AUTH_KEY") private val authKey: String,
        @SerialName("MIX_AUTH_TOKEN") private val authToken: String,

        @SerialName("MIX_PAGE_TOKEN_KEY") val tokenParam: String,
        @SerialName("MIX_STREAM_SERVER_KEY") val serverParam: String,
    ) {
        val authPath = authPathPrefix + authPathSuffix
        val tokenId = "$authKey:$authToken"
    }

    private fun getRandomString(length: Int = 8): String {
        val allowedChars = ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

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
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
