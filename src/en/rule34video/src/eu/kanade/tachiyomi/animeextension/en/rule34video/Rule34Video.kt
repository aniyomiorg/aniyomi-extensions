package eu.kanade.tachiyomi.animeextension.en.rule34video

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Rule34Video : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com"

    override val lang = "en"

    override val supportsLatest = false

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/latest-updates/$page/")

    override fun popularAnimeSelector() = "div.item.thumb"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.th")!!.attr("href"))
        title = element.selectFirst("a.th div.thumb_title")!!.text()
        thumbnail_url = element.selectFirst("a.th div.img img")?.attr("abs:data-original")
    }

    override fun popularAnimeNextPageSelector() = "div.item.pager.next a"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    private inline fun <reified R> AnimeFilterList.getUriPart() =
        (find { it is R } as? UriPartFilter)?.toUriPart() ?: ""

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val orderFilter = filters.getUriPart<OrderFilter>()
        val categoryFilter = filters.getUriPart<CategoryBy>()
        val sortType = when (orderFilter) {
            "latest-updates" -> "post_date"
            "most-popular" -> "video_viewed"
            "top-rated" -> "rating"
            else -> ""
        }

        val tagFilter = (filters.find { it is TagFilter } as? TagFilter)?.state ?: ""

        val url = "$baseUrl/search_ajax.php?tag=${tagFilter.ifBlank { "." }}"
        val response = client.newCall(GET(url, headers)).execute()
        tagDocument = response.asJsoup()

        val tagSearch = filters.getUriPart<TagSearch>()

        return if (query.isNotEmpty()) {
            if (query.startsWith(PREFIX_SEARCH)) {
                val newQuery = query.removePrefix(PREFIX_SEARCH).dropLastWhile { it.isDigit() }
                GET("$baseUrl/search/$newQuery")
            } else {
                GET("$baseUrl/search/${query.replace(Regex("\\s"), "-")}/?flag1=$categoryFilter&sort_by=$sortType&from_videos=$page&tag_ids=all%2C$tagSearch")
            }
        } else {
            GET("$baseUrl/search/?flag1=$categoryFilter&sort_by=$sortType&from_videos=$page&tag_ids=all%2C$tagSearch")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.title_video")!!.text()
        val info = document.selectFirst("#tab_video_info")!!
        author = info.select("div.label:contains(Artist:) + a").eachText().joinToString()
        description = buildString {
            info.selectFirst("div.label:contains(Description:) > em")?.text()?.also { append("$it\n") }
            info.selectFirst("i.icon-eye + span")?.text()?.also { append("\nViews : ${it.replace(" ", ",")}") }
            info.selectFirst("i.icon-clock + span")?.text()?.also { append("\nDuration : $it") }
            document.select("div.label:contains(Download) ~ a.tag_item")
                .eachText()
                .joinToString { it.substringAfter(" ") }
                .also { append("\nQuality : $it") }
        }
        genre = document.select("div.label:contains(Tags) ~ a.tag_item:not(:contains(Suggest))")
            .eachText()
            .joinToString()
        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Video"
            },
        )
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val headers = headersBuilder()
            .apply {
                val cookies = client.cookieJar.loadForRequest(response.request.url)
                    .filterNot { it.name in listOf("__ddgid_", "__ddgmark_") }
                    .map { "${it.name}=${it.value}" }
                    .joinToString("; ")
                val xsrfToken = cookies.split("XSRF-TOKEN=").getOrNull(1)?.substringBefore(";")?.replace("%3D", "=")
                xsrfToken?.let { add("X-XSRF-TOKEN", it) }
                add("Cookie", cookies)
                add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                add("Referer", response.request.url.toString())
                add("Accept-Language", "en-US,en;q=0.5")
            }.build()

        val document = response.asJsoup()

        return document.select("div.label:contains(Download) ~ a.tag_item")
            .mapNotNull { element ->
                val originalUrl = element.attr("href")
                // We need to do that because this url returns a http 403 error
                // if you try to connect using http/1.1, which is the protocol
                // that the player uses. OkHttp uses http/2 by default, so we
                // fetch the video url first via okhttp and then pass it for the player.
                val url = noRedirectClient.newCall(GET(originalUrl, headers)).execute()
                    .use { it.headers["location"] }
                    ?: return@mapNotNull null
                val quality = element.text().substringAfter(" ")
                Video(url, quality, url, headers)
            }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p") ?: return this
        return sortedWith(compareByDescending { it.quality == quality })
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = entries
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

    // ============================== Filters ===============================
    private var tagDocument = Document("")

    private fun tagsResults(document: Document): Array<Pair<String, String>> {
        val tagList = mutableListOf(Pair("<Select>", ""))
        tagList.addAll(
            document.select("div.item").map {
                val tagValue = it.selectFirst("input")!!.attr("value")
                val tagName = it.selectFirst("label")!!.text()
                Pair(tagName, tagValue)
            },
        )
        return tagList.toTypedArray()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        OrderFilter(),
        CategoryBy(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Entered a \"tag\", click on \"filter\" then Click \"reset\" to load tags."),
        TagFilter(),
        TagSearch(tagsResults(tagDocument)),
    )

    private class TagFilter : AnimeFilter.Text("Click \"reset\" without any text to load all A-Z tags.", "")

    private class TagSearch(results: Array<Pair<String, String>>) : UriPartFilter(
        "Tag Filter ",
        results,
    )

    private class CategoryBy : UriPartFilter(
        "Category Filter ",
        arrayOf(
            Pair("All", ""),
            Pair("Straight", "2109"),
            Pair("Futa", "15"),
            Pair("Gay", "192"),
            Pair("Music", "4747"),
            Pair("Iwara", "1821"),
        ),
    )

    private class OrderFilter : UriPartFilter(
        "Sort By ",
        arrayOf(
            Pair("Latest", "latest-updates"),
            Pair("Most Viewed", "most-popular"),
            Pair("Top Rated", "top-rated"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p", "360p")
    }
}
