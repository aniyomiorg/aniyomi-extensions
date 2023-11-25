package eu.kanade.tachiyomi.animeextension.en.gogoanime

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.en.gogoanime.extractors.GogoCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

@ExperimentalSerializationApi
class GogoAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Gogoanime"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular.html?page=$page", headers)

    override fun popularAnimeSelector(): String = "div.img a"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        title = element.attr("title")
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesSelector(): String = "div.img a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val imgUrl = element.selectFirst("img")!!.attr("src")

        val newUrl = imgUrl.replaceFirst("https://", "").substringAfter("/").replaceFirst("cover", "/category").substringBeforeLast('.')

        val finalUrl = newUrl.let { url ->
            url.lastIndexOf('-').let { lastIndex ->
                val suffix = url.substring(lastIndex + 1)
                if (lastIndex == -1 || !suffix.all { it.isDigit() } || suffix.length < 3) {
                    newUrl
                } else {
                    url.substring(0, lastIndex)
                }
            }
        }
        return SAnime.create().apply {
            setUrlWithoutDomain(finalUrl)
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            title = element.attr("title")
        }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = GogoAnimeFilters.getSearchParameters(filters)

        return when {
            params.genre.isNotEmpty() -> GET("$baseUrl/genre/${params.genre}?page=$page", headers)
            params.recent.isNotEmpty() -> GET("https://ajax.gogo-load.com/ajax/page-recent-release.html?page=$page&type=${params.recent}", headers)
            params.season.isNotEmpty() -> GET("$baseUrl/${params.season}?page=$page", headers)
            else -> GET("$baseUrl/filter.html?keyword=$query&${params.filter}&page=$page", headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = GogoAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val infoDocument = document.selectFirst("div.anime-info a[href]")?.let {
            client.newCall(GET(it.attr("abs:href"), headers)).execute().asJsoup()
        } ?: document

        return SAnime.create().apply {
            title = infoDocument.select("div.anime_info_body_bg h1").text()
            genre = infoDocument.select("p.type:eq(5) a").joinToString("") { it.text() }
            description = infoDocument.selectFirst("p.type:eq(4)")!!.ownText()
            status = parseStatus(infoDocument.select("p.type:eq(7) a").text())

            // add alternative name to anime description
            val altName = "Other name(s): "
            infoDocument.selectFirst("p.type:eq(8)")?.ownText()?.let {
                if (it.isBlank().not()) {
                    description = when {
                        description.isNullOrBlank() -> altName + it
                        else -> description + "\n\n$altName" + it
                    }
                }
            }
        }
    }

    // ============================== Episodes ==============================

    private fun episodesRequest(totalEpisodes: String, id: String): List<SEpisode> {
        val request = GET("https://ajax.gogo-load.com/ajax/load-list-episode?ep_start=0&ep_end=$totalEpisodes&id=$id", headers)
        val epResponse = client.newCall(request).execute()
        val document = epResponse.asJsoup()
        return document.select("a").map { episodeFromElement(it) }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val totalEpisodes = document.select(episodeListSelector()).last()!!.attr("ep_end")
        val id = document.select("input#movie_id").attr("value")
        return episodesRequest(totalEpisodes, id)
    }

    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val ep = element.selectFirst("div.name")!!.ownText().substringAfter(" ")
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            episode_number = ep.toFloat()
            name = "Episode $ep"
        }
    }

    // ============================ Video Links =============================

    private val gogoExtractor by lazy { GogoCdnExtractor(client, json) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        return document.select("div.anime_muti_link > ul > li").parallelMap { server ->
            runCatching {
                val className = server.className()
                if (!hosterSelection.contains(className)) return@runCatching emptyList()
                val serverUrl = server.selectFirst("a")
                    ?.attr("abs:data-video")
                    ?: return@runCatching emptyList()

                getHosterVideos(className, serverUrl)
            }.getOrElse { emptyList() }
        }.flatten().sort().ifEmpty { throw Exception("Failed to extract videos") }
    }

    private fun getHosterVideos(className: String, serverUrl: String): List<Video> {
        return when (className) {
            "anime", "vidcdn" -> gogoExtractor.videosFromUrl(serverUrl)
            "streamwish" -> streamwishExtractor.videosFromUrl(serverUrl)
            "doodstream" -> doodExtractor.videosFromUrl(serverUrl)
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(serverUrl, headers)
            "filelions" -> {
                streamwishExtractor.videosFromUrl(serverUrl, videoNameGen = { quality -> "FileLions - $quality" })
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        private val HOSTERS = arrayOf(
            "Gogostream",
            "Vidstreaming",
            "Doodstream",
            "StreamWish",
            "Mp4upload",
            "FileLions",
        )
        private val HOSTERS_NAMES = arrayOf( // Names that appears in the gogo html
            "vidcdn",
            "anime",
            "doodstream",
            "streamwish",
            "mp4upload",
            "filelions",
        )

        private val PREF_DOMAIN_KEY = "preferred_domain_name_v${BuildConfig.VERSION_CODE}"
        private const val PREF_DOMAIN_TITLE = "Override BaseUrl"
        private const val PREF_DOMAIN_DEFAULT = "https://anitaku.to"
        private const val PREF_DOMAIN_SUMMARY = "For temporary uses. Updating the extension will erase this setting."

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Gogostream"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val PREF_HOSTER_DEFAULT = HOSTERS_NAMES.toSet()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            summary = PREF_DOMAIN_SUMMARY
            dialogTitle = PREF_DOMAIN_TITLE
            dialogMessage = "Default: $PREF_DOMAIN_DEFAULT"
            setDefaultValue(PREF_DOMAIN_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val newValueString = newValue as String
                Toast.makeText(screen.context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, newValueString.trim()).commit()
            }
        }.also(screen::addPreference)

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

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
