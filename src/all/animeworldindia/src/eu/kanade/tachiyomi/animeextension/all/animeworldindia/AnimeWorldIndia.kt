package eu.kanade.tachiyomi.animeextension.all.animeworldindia

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
import eu.kanade.tachiyomi.util.asJsoup
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

class AnimeWorldIndia(
    final override val lang: String,
    private val language: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeWorld India"

    override val baseUrl = "https://anime-world.in"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/advanced-search/page/$page/?s_lang=$lang&s_orderby=viewed")

    override fun popularAnimeSelector() = searchAnimeSelector()

    override fun popularAnimeFromElement(element: Element) = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector() = searchAnimeNextPageSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = searchAnimeNextPageSelector()

    override fun latestUpdatesSelector() = searchAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = searchAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/advanced-search/page/$page/?s_lang=$lang&s_orderby=update")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val searchParams = AnimeWorldIndiaFilters().getSearchParams(filters)
        return GET("$baseUrl/advanced-search/page/$page/?s_keyword=$query&s_lang=$lang$searchParams")
    }

    override fun searchAnimeSelector() = "div.col-span-1"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
        title = element.selectFirst("div.font-medium.line-clamp-2.mb-3")!!.text()
    }

    override fun searchAnimeNextPageSelector() = "ul.page-numbers li:has(span.current) + li"

    override fun getFilterList() = AnimeWorldIndiaFilters().filters

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h2.text-4xl")!!.text()
        genre = document.select("span.leading-6 a[class~=border-opacity-30]").joinToString { it.text() }
        description = document.selectFirst("div[data-synopsis]")?.text()
        author = document.selectFirst("span.leading-6 a[href*=\"producer\"]:first-child")?.text()
        artist = document.selectFirst("span.leading-6 a[href*=\"studio\"]:first-child")?.text()
        status = parseStatus(document)
    }

    private val selector = "ul li:has(div.w-1.h-1.bg-gray-500.rounded-full) + li"

    private fun parseStatus(document: Document): Int {
        return when (document.selectFirst("$selector a:not(:contains(Ep))")?.text()) {
            null -> SAnime.UNKNOWN
            "Movie" -> SAnime.COMPLETED
            else -> {
                val epParts = document.selectFirst("$selector a:not(:contains(TV))")
                    ?.text()
                    ?.drop(3)
                    ?.split("/")
                    ?.takeIf { it.size >= 2 }
                    ?: return SAnime.UNKNOWN
                if (epParts.first().trim().compareTo(epParts[1].trim()) == 0) {
                    SAnime.COMPLETED
                } else {
                    SAnime.ONGOING
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    @Serializable
    data class SeasonDto(val episodes: EpisodeTypeDto)

    @Serializable
    data class EpisodeTypeDto(val all: List<EpisodeDto>) {
        @Serializable
        data class EpisodeDto(val id: Int, val metadata: MetadataDto)

        @Serializable
        data class MetadataDto(
            val number: String,
            val title: String,
            val released: String? = null,
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val isMovie = document.selectFirst("nav li > a[href*=\"type/movies/\"]") != null

        val seasonsJson = json.decodeFromString<List<SeasonDto>>(
            document.html()
                .substringAfter("var season_list = ")
                .substringBefore("var season_label =")
                .trim().dropLast(1),
        )

        var episodeNumberFallback = 1F
        val isSingleSeason = seasonsJson.size == 1
        return seasonsJson.flatMapIndexed { seasonNumber, season ->
            val seasonName = if (isSingleSeason) "" else "Season ${seasonNumber + 1}"

            season.episodes.all.reversed().map { episode ->
                val episodeTitle = episode.metadata.title
                val epNum = episode.metadata.number.toIntOrNull() ?: episodeNumberFallback.toInt()

                val episodeName = when {
                    isMovie -> "Movie"
                    else -> buildString {
                        if (seasonName.isNotBlank()) append("$seasonName - ")
                        append("Episode $epNum")
                        if (episodeTitle.isNotBlank()) append(" - $episodeTitle")
                    }
                }

                SEpisode.create().apply {
                    name = episodeName
                    episode_number = when {
                        isSingleSeason -> epNum.toFloat()
                        else -> episodeNumberFallback
                    }
                    episodeNumberFallback++
                    setUrlWithoutDomain("$baseUrl/wp-json/kiranime/v1/episode?id=${episode.id}")
                    date_upload = episode.metadata.released?.toLongOrNull()?.times(1000) ?: 0L
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    @Serializable
    private data class PlayerDto(
        val type: String,
        val url: String,
        val language: String,
        val server: String,
    )

    private val mystreamExtractor by lazy { MyStreamExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body.string()
        val documentTrimmed = body
            .substringAfterLast("\"players\":")
            .substringBefore(",\"noplayer\":")
            .trim()

        val playersList = json.decodeFromString<List<PlayerDto>>(documentTrimmed)
            .filter { it.type == "stream" && it.url.isNotBlank() }
            .also { require(it.isNotEmpty()) { "No streams available!" } }
            .filter { language.isEmpty() || it.language.equals(language) }
            .also { require(it.isNotEmpty()) { "No videos for your language!" } }

        return playersList.flatMap {
            when (it.server) {
                "Mystream" -> mystreamExtractor.videosFromUrl(it.url, it.language)
                else -> emptyList()
            }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================ Preferences =============================
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
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "240")
    }
}
