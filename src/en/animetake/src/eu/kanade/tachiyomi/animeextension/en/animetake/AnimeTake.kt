package eu.kanade.tachiyomi.animeextension.en.animetake

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.gogostreamextractor.GogoStreamExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelFlatMap
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeTake : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeTake"

    override val baseUrl = "https://animetake.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animelist/popular")

    override fun popularAnimeSelector() = "div.col-sm-6"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("div > a").attr("href"))
            thumbnail_url = baseUrl + element.select("div.latestep_image > img").attr("data-src")
            title = element.select("span.latestep_title > h4").first()!!.ownText()
        }
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li.page-item:last-child"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animelist/?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeTakeFilters.getSearchParameters(filters)
        val cleanQuery = query.replace(" ", "+").lowercase()
        return if (query.isNotEmpty()) {
            GET("$baseUrl/search/?search=$cleanQuery&page=$page")
        } else {
            val multiString = buildString {
                if (params.letters.isNotEmpty()) append(params.letters + "&")
                if (params.genres.isNotEmpty()) append(params.genres + "&")
                if (params.score.isNotEmpty()) append(params.score + "&")
                if (params.years.isNotEmpty()) append(params.years + "&")
                if (params.ratings.isNotEmpty()) append(params.ratings + "&")
            }
            GET("$baseUrl/animelist/?page=$page&$multiString")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeTakeFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h3 > b").text()
        anime.genre = document.select("a.animeinfo_label").joinToString {
            it.select("span").text()
        }
        anime.description = document.select("div.visible-md").first()!!.ownText()
        anime.status =
            parseStatus(document.select("div.well > center:contains(Next Episode)").isNotEmpty())
        document.select("div.well > p").first()!!.text().let {
            if (it.isBlank().not()) {
                anime.description = when {
                    anime.description.isNullOrBlank() -> it
                    else -> anime.description + "\n\n" + it
                }
            }
        }
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.tab-content"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodesLink = document.select(episodeListSelector())
        val episodes = mutableListOf<SEpisode>()

        val specialsDiv = episodesLink.select("div#specials")
        if (specialsDiv.isNotEmpty()) {
            episodes.addAll(specialsDiv.select("a[href]").map(::episodeFromElement).reversed())
        }
        episodes.addAll(
            episodesLink.select("div#eps").select("a[href]")
                .map(::episodeFromElement).reversed(),
        )

        return episodes.toList()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val upDate = element.select("div.col-xs-12 > span.front_time").text().trim()
            date_upload = parseDate(upDate)
            val epName = element.select("div.col-xs-12 > div.anime-title > b").text().trim()
            val epNum = epName.split(" ").last()
            name = epName
            episode_number = epNum.toFloatOrNull() ?: 0F
        }
    }

    // ============================ Video Links =============================
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val gogoStreamExtractor by lazy { GogoStreamExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scripts = document.select("div#divscript > script").filter { it ->
            it.data().contains("function")
        }
        return scripts.parallelCatchingFlatMapBlocking { elem ->
            val data = elem.data().trimIndent()
            val url = baseUrl + extractIframeSrc(data)
            if (data.contains("vidstream()")) {
                val iframeSrc = client.newCall(GET(url)).execute().asJsoup()
                    .select("iframe").attr("src")
                extractVideo(iframeSrc)
            } else if (data.contains("fm()")) {
                val iframeSrc = client.newCall(GET(url)).execute().asJsoup()
                    .select("iframe").attr("src")
                filemoonExtractor.videosFromUrl(url = iframeSrc, headers = headers)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun extractVideo(url: String): List<Video> {
        val videos = gogoStreamExtractor.videosFromUrl(url)

        val request = GET(url)
        val response = client.newCall(request).await()
        val document = response.asJsoup()
        val servers = document.select("div#list-server-more > ul > li.linkserver")
        return servers.parallelFlatMap {
            val link = it.attr("data-video")
            when (it.text().lowercase()) {
                "doodstream" -> doodExtractor.videosFromUrl(link)
                "mp4upload" -> mp4uploadExtractor.videosFromUrl(link, headers)
                else -> emptyList()
            }
        } + videos
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun extractIframeSrc(scriptData: String): String {
        val iframeRegex = "<iframe[^>]*>.*?</iframe>".toRegex()
        val iframe = iframeRegex.find(scriptData)!!.value
        val srcRegex = "(?<=src=\").*?(?=[\\*\"])".toRegex()
        return srcRegex.find(iframe)!!.value
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    private fun parseDate(date: String): Long {
        val formatter = SimpleDateFormat("dd LLLL yyyy", Locale.ENGLISH)
        val newDate = formatter.parse(date)
        val dateStr = newDate?.let { DATE_FORMATTER.format(it) }
        return runCatching { DATE_FORMATTER.parse(dateStr!!)?.time }
            .getOrNull() ?: 0L
    }

    private fun parseStatus(statusBool: Boolean): Int {
        return if (statusBool) {
            SAnime.ONGOING
        } else {
            SAnime.COMPLETED
        }
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidstreaming"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Vidstreaming",
            "Filemoon",
            "Doodstream",
            "Mp4upload",
        )
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
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_ENTRIES
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
}
