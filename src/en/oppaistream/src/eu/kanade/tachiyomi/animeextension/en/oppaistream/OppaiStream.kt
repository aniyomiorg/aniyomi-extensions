package eu.kanade.tachiyomi.animeextension.en.oppaistream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OppaiStream : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Oppai Stream"

    override val lang = "en"

    override val baseUrl = "https://oppai.stream"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // popular
    override fun popularAnimeRequest(page: Int): Request {
        return searchAnimeRequest(page, "", AnimeFilterList(OrderByFilter("views")))
    }

    override fun popularAnimeSelector() = searchAnimeSelector()

    override fun popularAnimeNextPageSelector() = searchAnimeNextPageSelector()

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    override fun popularAnimeFromElement(element: Element) = searchAnimeFromElement(element)

    // latest
    override fun latestUpdatesRequest(page: Int): Request {
        return searchAnimeRequest(page, "", AnimeFilterList(OrderByFilter("uploaded")))
    }

    override fun latestUpdatesSelector() = searchAnimeSelector()

    override fun latestUpdatesNextPageSelector() = searchAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    override fun latestUpdatesFromElement(element: Element) = searchAnimeFromElement(element)

    // search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/actions/search.php".toHttpUrl().newBuilder().apply {
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
                        addQueryParameter("genres", genresInclude.joinToString(",") { it })
                        addQueryParameter("blacklist", genresExclude.joinToString(",") { it })
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

    override fun searchAnimeSelector() = "div.episode-shown"

    override fun searchAnimeNextPageSelector() = null

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(searchAnimeSelector())

        val mangas = elements.map { element ->
            searchAnimeFromElement(element)
        }.distinctBy { it.title }

        val hasNextPage = elements.size >= SEARCH_LIMIT

        return AnimesPage(mangas, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            thumbnail_url = element.select("img.cover-img-in").attr("abs:src")
            title = element.select(".title-ep").text()
                .replace(TITLE_CLEANUP_REGEX, "")
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        }
    }

    // Function to fetch thumbnail URL using AniList GraphQL API
    // Only use in animeDetailsParse.
    private fun fetchThumbnailUrlByTitle(title: String): Pair<String?, MutableList<String>>? {
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
        val responseString = response.use { it.body.string() }

        return parseThumbnailUrlFromResponse(responseString)
    }

    private fun parseThumbnailUrlFromResponse(responseString: String): Pair<String?, MutableList<String>>? {
        val responseJson = Json.parseToJsonElement(responseString) as? JsonObject ?: return null
        val data = responseJson["data"] as? JsonObject ?: return null
        val media = data["Media"] as? JsonObject ?: return null
        val coverImage = media["coverImage"] as? JsonObject ?: return null

        val coverURL = when (preferences.getString(PREF_COVER_QUALITY, "large")) {
            "extraLarge" -> coverImage["extraLarge"]?.jsonPrimitive?.content
            "large" -> coverImage["large"]?.jsonPrimitive?.content
            else -> null
        }

        val studiosList = mutableListOf<String>()
        val studios = media["studios"]?.jsonObject?.get("nodes")?.jsonArray
        studios?.forEach { studio ->
            val name = studio.jsonObject["name"]?.jsonPrimitive?.content
            if (!name.isNullOrEmpty()) {
                studiosList.add(name)
            }
        }

        return Pair(coverURL, studiosList)
    }

    override fun getFilterList() = FILTERS

    // details
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            // Fetch from from Anilist when "Anilist Cover" is selected in settings
            val selectedCoverSource = preferences.getString(PREF_COVER_SOURCE, "Anilist-Cover")
            val name = document.selectFirst("div.episode-info > h1")!!.text().substringBefore(" Ep ")
            val newTitle = name.replace(Regex("[^a-zA-Z0-9\\s!.:\"]"), " ")
            val thumbnailUrl = if (selectedCoverSource == "Anilist-Cover") {
                fetchThumbnailUrlByTitle(newTitle)
            } else {
                null // Use default cover
            }

            title = name
            description = document.selectFirst("div.description")?.text()
            genre = document.select("div.tags a").joinToString { it.text() }
            val studios = document.select("div.episode-info a.red").eachText()
            artist = studios.joinToString()

            // Match local studios with anilist studios to increase the accuracy of the poster
            val matchedStudio = thumbnailUrl?.second?.find { it in studios }

            thumbnail_url = matchedStudio?.let { thumbnailUrl.first }
                ?: document.selectFirst("video#episode")?.attr("poster")
        }
    }

    // episodes
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { it.asJsoup() }
        return buildList {
            doc.select(episodeListSelector())
                .map(::episodeFromElement)
                .let(::addAll)

            add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
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
        setUrlWithoutDomain(element.attr("href"))
        val num = element.selectFirst("font.ep")?.text() ?: "1"
        name = "Episode $num"
        episode_number = num.toFloatOrNull() ?: 1F
        scanlator = element.selectFirst("h6 > a")?.text()
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }
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

    override fun videoListSelector() = throw Exception("Not used")

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, "720")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = PREF_QUALITY_TITLE
            entries = arrayOf("2160p", "1080p", "720p")
            entryValues = arrayOf("2160", "1080", "720")
            setDefaultValue("720")
            summary = "%s"
        }.let {
            screen.addPreference(it)
        }

        // Add cover source preference
        ListPreference(screen.context).apply {
            key = PREF_COVER_SOURCE
            title = PREF_COVER_SOURCE_TITLE
            entries = arrayOf("Default Cover", "Anilist Cover")
            entryValues = arrayOf("Default-Cover", "Anilist-Cover")
            summary = "This feature is experimental. It uses a covers for Anilist. If you see the default cover after switching to AniList cover, try clearing the cache in Settings > Advanced > Clear Anime Database > Oppai Steam. It only fetch Anilist covers in anime details page."
            setDefaultValue("Anilist-Cover")
        }.let {
            screen.addPreference(it)
        }

        // Add cover source preference
        ListPreference(screen.context).apply {
            key = PREF_COVER_QUALITY
            title = PREF_COVER_QUALITY_TITLE
            entries = arrayOf("Extra Large", "Large")
            entryValues = arrayOf("extraLarge", "large")
            summary = "%s"
            setDefaultValue("large")
        }.let {
            screen.addPreference(it)
        }
    }

    companion object {
        private const val SEARCH_LIMIT = 36
        private val TITLE_CLEANUP_REGEX = Regex("""\s+\d+$""")

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"

        private const val PREF_COVER_SOURCE = "preferred_cover_source"
        private const val PREF_COVER_SOURCE_TITLE = "Preferred cover source - Beta"

        private const val PREF_COVER_QUALITY = "preferred_cover_quality"
        private const val PREF_COVER_QUALITY_TITLE = "Preferred cover quality - Beta"
    }
}
