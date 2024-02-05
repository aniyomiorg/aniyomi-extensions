package eu.kanade.tachiyomi.animeextension.ar.anime4up

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.VidYardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Anime4Up : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime4Up"

    override val baseUrl = "https://anime4up.cam"

    override val lang = "ar"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime-list-3/page/$page/")

    override fun popularAnimeSelector() = "div.anime-list-content div.anime-card-poster > div.hover"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt")
        }
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li > a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun getFilterList() = Anime4UpFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/?search_param=animes&s=$query", headers)
        }

        return with(Anime4UpFilters.getSearchParameters(filters)) {
            val url = when {
                genre.isNotBlank() -> "$baseUrl/anime-genre/$genre"
                type.isNotBlank() -> "$baseUrl/anime-type/$type"
                status.isNotBlank() -> "$baseUrl/anime-status/$status"
                else -> throw Exception("اختر فلتر")
            }
            GET(url, headers)
        }
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = document // Shortcut

        thumbnail_url = doc.selectFirst("img.thumbnail")!!.attr("src")
        title = doc.selectFirst("h1.anime-details-title")!!.text()
        // Genres + useful info
        genre = doc.select("ul.anime-genres > li > a, div.anime-info > a").eachText().joinToString()

        description = buildString {
            // Additional info
            doc.select("div.anime-info").eachText().forEach {
                append("$it\n")
            }
            // Description
            doc.selectFirst("p.anime-story")?.text()?.also {
                append("\n$it")
            }
        }

        doc.selectFirst("div.anime-info:contains(حالة الأنمي)")?.text()?.also {
            status = when {
                it.contains("يعرض الان", true) -> SAnime.ONGOING
                it.contains("مكتمل", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.ehover6 > div.episodes-card-title > h3 > a, ul.all-episodes-list li > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = name.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    @Serializable
    data class Qualities(
        val fhd: Map<String, String> = emptyMap(),
        val hd: Map<String, String> = emptyMap(),
        val sd: Map<String, String> = emptyMap(),
    )

    override fun videoListParse(response: Response): List<Video> {
        val base64 = response.asJsoup().selectFirst("input[name=wl]")
            ?.attr("value")
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
            ?: return emptyList()

        val parsedData = json.decodeFromString<Qualities>(base64)
        val streamLinks = with(parsedData) { fhd + hd + sd }

        return streamLinks.values.distinct().parallelCatchingFlatMapBlocking(::extractVideos)
    }

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val gdriveplayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sharedExtractor by lazy { SharedExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidbomExtractor by lazy { VidBomExtractor(client) }
    private val vidyardExtractor by lazy { VidYardExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }

    private fun extractVideos(url: String): List<Video> {
        return when {
            url.contains("drive.google") -> {
                val embedUrlG = "https://gdriveplayer.to/embed2.php?link=$url"
                gdriveplayerExtractor.videosFromUrl(embedUrlG, "GdrivePlayer", headers)
            }
            url.contains("vidyard") -> vidyardExtractor.videosFromUrl(url)
            url.contains("ok.ru") -> okruExtractor.videosFromUrl(url)
            url.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers)
            url.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
            url.contains("voe") -> voeExtractor.videosFromUrl(url)
            url.contains("shared") -> sharedExtractor.videosFromUrl(url)?.let(::listOf)
            DOOD_REGEX.containsMatchIn(url) -> doodExtractor.videosFromUrl(url, "Dood mirror")
            VIDBOM_REGEX.containsMatchIn(url) -> vidbomExtractor.videosFromUrl(url)
            STREAMWISH_REGEX.containsMatchIn(url) -> streamwishExtractor.videosFromUrl(url) { "Mirror: $it" }
            else -> null
        } ?: emptyList()
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

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private val VIDBOM_REGEX = Regex("(?:v[aie]d[bp][aoe]?m|myvii?d|segavid|v[aei]{1,2}dshar[er]?)\\.(?:com|net|org|xyz)(?::\\d+)?/(?:embed[/-])?([A-Za-z0-9]+)")
        private val DOOD_REGEX = Regex("(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream))/[de]/([0-9a-zA-Z]+)")
        private val STREAMWISH_REGEX = Regex("((?:streamwish|anime7u|animezd|ajmidyad|khadhnayad|yadmalik|hayaatieadhab)\\.(?:com|to|sbs))/(?:e/|v/|f/)?([0-9a-zA-Z]+)")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "dood")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
