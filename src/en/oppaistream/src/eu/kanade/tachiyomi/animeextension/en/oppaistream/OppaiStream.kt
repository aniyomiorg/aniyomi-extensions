package eu.kanade.tachiyomi.animeextension.en.oppaistream

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.oppaistream.dto.AnilistResponseDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_INCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class OppaiStream : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Oppai Stream"

    override val lang = "en"

    override val baseUrl = "https://oppai.stream"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/$SEARCH_PATH?order=views&page=$page&limit=$SEARCH_LIMIT")

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    override fun popularAnimeSelector() = searchAnimeSelector()

    override fun popularAnimeFromElement(element: Element) = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/$SEARCH_PATH?order=uploaded&page=$page&limit=$SEARCH_LIMIT")

    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    override fun latestUpdatesSelector() = searchAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = searchAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun getFilterList() = FILTERS

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/$SEARCH_PATH".toHttpUrl().newBuilder().apply {
            addQueryParameter("text", query.trim())
            filters.forEach { filter ->
                when (filter) {
                    is OrderByFilter -> {
                        addQueryParameter("order", filter.selectedValue())
                    }
                    is GenreListFilter -> {
                        val genresInclude = mutableListOf<String>()
                        val genresExclude = mutableListOf<String>()
                        filter.state.forEach { genreState ->
                            when (genreState.state) {
                                STATE_INCLUDE -> genresInclude.add(genreState.value)
                                STATE_EXCLUDE -> genresExclude.add(genreState.value)
                            }
                        }
                        addQueryParameter("genres", genresInclude.joinToString(","))
                        addQueryParameter("blacklist", genresExclude.joinToString(","))
                    }
                    is StudioListFilter -> {
                        addQueryParameter("studio", filter.state.filter { it.state }.joinToString(",") { it.value })
                    }
                    else -> {}
                }
                addQueryParameter("page", page.toString())
                addQueryParameter("limit", SEARCH_LIMIT.toString())
            }
        }.build().toString()

        return GET(url, headers)
    }

    override fun searchAnimeSelector() = "div.episode-shown > div > a"

    override fun searchAnimeNextPageSelector() = null

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(searchAnimeSelector())

        val anime = elements.map(::searchAnimeFromElement).distinctBy { it.title }

        val hasNextPage = elements.size >= SEARCH_LIMIT

        return AnimesPage(anime, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img.cover-img-in")?.attr("abs:src")
        title = element.selectFirst(".title-ep")!!.text().replace(TITLE_CLEANUP_REGEX, "")
        setUrlWithoutDomain(
            element.attr("href").replace(Regex("(?<=\\?e=)(.*?)(?=&f=)")) {
                java.net.URLEncoder.encode(it.groupValues[1], "UTF-8")
            },
        )
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        // Fetch from from Anilist when "Anilist Cover" is selected in settings
        val name = document.selectFirst("div.episode-info > h1")!!.text().substringBefore(" Ep ")
        title = name
        description = document.selectFirst("div.description")?.text()?.substringBeforeLast(" Watch ")
        genre = document.select("div.tags a").joinToString { it.text() }
        val studios = document.select("div.episode-info a.red").eachText()
        artist = studios.joinToString()

        val useAnilistCover = preferences.getBoolean(PREF_ANILIST_COVER_KEY, PREF_ANILIST_COVER_DEFAULT)
        val thumbnailUrl = if (useAnilistCover) {
            val newTitle = name.replace(Regex("[^a-zA-Z0-9\\s!.:\"]"), " ")
            runCatching { fetchThumbnailUrlByTitle(newTitle) }.getOrNull()
        } else {
            null // Use default cover (episode preview)
        }

        // Match local studios with anilist studios to increase the accuracy of the poster
        val matchedStudio = thumbnailUrl?.second?.find { it in studios }

        thumbnail_url = matchedStudio?.let { thumbnailUrl.first }
            ?: document.selectFirst("video#episode")?.attr("poster")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return buildList {
            doc.select(episodeListSelector())
                .map(::episodeFromElement)
                .let(::addAll)

            add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(
                        doc.location().replace(Regex("(?<=\\?e=)(.*?)(?=&f=)")) {
                            java.net.URLEncoder.encode(it.groupValues[1], "UTF-8")
                        },
                    )
                    val num = doc.selectFirst("div.episode-info > h1")!!.text().substringAfter(" Ep ")
                    name = "Episode $num"
                    episode_number = num.toFloatOrNull() ?: 1F
                    scanlator = doc.selectFirst("div.episode-info a.red")?.text()
                },
            )
        }.sortedByDescending { it.episode_number }
    }

    override fun episodeListSelector() = "div.more-same-eps > div > div > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(
            element.attr("href").replace(Regex("(?<=\\?e=)(.*?)(?=&f=)")) {
                java.net.URLEncoder.encode(it.groupValues[1], "UTF-8")
            },
        )
        val num = element.selectFirst("font.ep")?.text() ?: "1"
        name = "Episode $num"
        episode_number = num.toFloatOrNull() ?: 1F
        scanlator = element.selectFirst("h6 > a")?.text()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val script = doc.selectFirst("script:containsData(var availableres)")!!.data()
        val subtitles = doc.select("track[kind=captions]").map {
            Track(it.attr("src"), it.attr("label"))
        }

        return script.substringAfter("var availableres = {").substringBefore('}')
            .split(',')
            .map {
                val (resolution, url) = it.replace("\"", "").replace("\\", "").split(':', limit = 2)
                val fixedResolution = when (resolution) {
                    "4k" -> "2160p"
                    else -> "${resolution}p"
                }
                Video(url, fixedResolution, url, headers, subtitles)
            }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
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

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ANILIST_COVER_KEY
            title = PREF_ANILIST_COVER_TITLE
            summary = PREF_ANILIST_COVER_SUMMARY
            setDefaultValue(PREF_ANILIST_COVER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_COVER_QUALITY_KEY
            title = PREF_COVER_QUALITY_TITLE
            entries = PREF_COVER_QUALITY_ENTRIES
            entryValues = PREF_COVER_QUALITY_VALUES
            setDefaultValue(PREF_COVER_QUALITY_DEFAULT)
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

    // Function to fetch thumbnail URL using AniList GraphQL API
    // Only use in animeDetailsParse.
    private fun fetchThumbnailUrlByTitle(title: String): Pair<String, List<String>>? {
        val query = """
            query {
                Media(search: "$title", type: ANIME, isAdult: true) {
                    coverImage {
                        extraLarge
                        large
                    }
                    studios {
                        nodes {
                            name
                        }
                    }
                }
            }
        """.trimIndent()

        val requestBody = FormBody.Builder()
            .add("query", query)
            .build()

        val request = POST("https://graphql.anilist.co", body = requestBody)

        val response = client.newCall(request).execute()

        return parseThumbnailUrlFromObject(response.parseAs<AnilistResponseDto>())
    }

    private fun parseThumbnailUrlFromObject(obj: AnilistResponseDto): Pair<String, List<String>>? {
        val media = obj.data.media ?: return null

        val coverURL = when (preferences.getString(PREF_COVER_QUALITY_KEY, PREF_COVER_QUALITY_DEFAULT)) {
            "extraLarge" -> media.coverImage.extraLarge
            else -> media.coverImage.large
        }

        val studiosList = media.studios.names

        return Pair(coverURL, studiosList)
    }

    companion object {
        private const val SEARCH_PATH = "actions/search.php"
        private const val SEARCH_LIMIT = 36
        private val TITLE_CLEANUP_REGEX = Regex("""\s+\d+$""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_ANILIST_COVER_KEY = "preferred_anilist_cover"
        private const val PREF_ANILIST_COVER_TITLE = "Use Anilist as cover source - Beta"
        private const val PREF_ANILIST_COVER_DEFAULT = true
        private const val PREF_ANILIST_COVER_SUMMARY = "This feature is experimental. " +
            "It enables fetching covers from Anilist. If you see the default cover " +
            "after switching to AniList cover, try clearing the cache in " +
            "Settings > Advanced > Clear Anime Database > Oppai Steam. It only fetch Anilist covers in anime details page."

        private const val PREF_COVER_QUALITY_KEY = "preferred_cover_quality"
        private const val PREF_COVER_QUALITY_TITLE = "Preferred Anilist cover quality - Beta"
        private const val PREF_COVER_QUALITY_DEFAULT = "large"
        private val PREF_COVER_QUALITY_ENTRIES = arrayOf("Extra Large", "Large")
        private val PREF_COVER_QUALITY_VALUES = arrayOf("extraLarge", "large")
    }
}
