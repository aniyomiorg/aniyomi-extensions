package eu.kanade.tachiyomi.animeextension.ar.animetitans

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.AnimeTitansExtractor
import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.VidBomExtractor
import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.VidYardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.util.Base64
import java.util.Locale

class AnimeTitans : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeTitans"

    override val baseUrl = "https://animetitans.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val AnimeUrlDirectory: String = "/anime"

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMM d, yyy", Locale.US)

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular (Search with popular order and nothing else)
    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList(OrderByFilter("popular")))
    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // Latest (Search with update order and nothing else)
    override fun latestUpdatesRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList(OrderByFilter("update")))
    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    // Episodes

    override fun episodeListSelector() = "ul li[data-index]"

    private fun parseEpisodeDate(date: String): Long {
        return SimpleDateFormat("MMM d, yyy", Locale("ar")).parse(date)?.time ?: 0L
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select(".epl-num").text())
        val urlElements = element.select("a")
        episode.setUrlWithoutDomain(urlElements.attr("href"))
        episode.name = element.select(".epl-title").text().ifBlank { urlElements.first().text() }
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        episode.date_upload = element.select(".epl-date").first()?.text()?.let { parseEpisodeDate(it) } ?: 0L
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "select.mirror option"

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val location = element.ownerDocument().location()
            val videoEncode = element.attr("value")
            val qualityy = element.text()
            val decoder: Base64.Decoder = Base64.getDecoder()
            val decoded = String(decoder.decode(videoEncode))
            val embedUrl = decoded.substringAfter("src=\"").substringBefore("\"")
            Log.i("embedUrl", "$embedUrl")
            when {
                embedUrl.contains("vidyard")
                -> {
                    val headers = headers.newBuilder()
                        .set("Referer", "https://play.vidyard.com")
                        .set("Accept-Encoding", "gzip, deflate, br")
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("TE", "trailers")
                        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                        .build()
                    val id = embedUrl.substringAfter("com/").substringBefore("?")
                    val vidUrl = "https://play.vidyard.com/player/" + id + ".json"
                    val videos = VidYardExtractor(client).videosFromUrl(vidUrl, headers)
                    videoList.addAll(videos)
                }
                embedUrl.contains("animetitans.net")
                -> {
                    val headers = headers.newBuilder()
                        .set("Referer", "https://animetitans.net/")
                        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36")
                        .set("Accept", "*/*")
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("Accept-Encoding", "gzip, deflate, br")
                        .build()
                    val videos = AnimeTitansExtractor(client).videosFromUrl(embedUrl, headers)
                    videoList.addAll(videos)
                }
                embedUrl.contains("sbembed.com") || embedUrl.contains("sbembed1.com") || embedUrl.contains("sbplay.org") ||
                    embedUrl.contains("sbvideo.net") || embedUrl.contains("streamsb.net") || embedUrl.contains("sbplay.one") ||
                    embedUrl.contains("cloudemb.com") || embedUrl.contains("playersb.com") || embedUrl.contains("tubesb.com") ||
                    embedUrl.contains("sbplay1.com") || embedUrl.contains("embedsb.com") || embedUrl.contains("watchsb.com") ||
                    embedUrl.contains("sbplay2.com") || embedUrl.contains("japopav.tv") || embedUrl.contains("viewsb.com") ||
                    embedUrl.contains("sbfast") || embedUrl.contains("sbfull.com") || embedUrl.contains("javplaya.com") ||
                    embedUrl.contains("ssbstream.net") || embedUrl.contains("p1ayerjavseen.com") || embedUrl.contains("sbthe.com") ||
                    embedUrl.contains("vidmovie.xyz") || embedUrl.contains("sbspeed.com") || embedUrl.contains("streamsss.net") ||
                    embedUrl.contains("sblanh.com")
                -> {
                    val videos = StreamSBExtractor(client).videosFromUrl(embedUrl, headers)
                    videoList.addAll(videos)
                }
                embedUrl.contains("drive.google")
                -> {
                    val embedUrlG = "https://gdriveplayer.to/embed2.php?link=" + embedUrl
                    val videos = GdrivePlayerExtractor(client).videosFromUrl(embedUrlG)
                    videoList.addAll(videos)
                }
                embedUrl.contains("fembed") ||
                    embedUrl.contains("anime789.com") || embedUrl.contains("24hd.club") || embedUrl.contains("fembad.org") ||
                    embedUrl.contains("vcdn.io") || embedUrl.contains("sharinglink.club") || embedUrl.contains("moviemaniac.org") ||
                    embedUrl.contains("votrefiles.club") || embedUrl.contains("femoload.xyz") || embedUrl.contains("albavido.xyz") ||
                    embedUrl.contains("feurl.com") || embedUrl.contains("dailyplanet.pw") || embedUrl.contains("ncdnstm.com") ||
                    embedUrl.contains("jplayer.net") || embedUrl.contains("xstreamcdn.com") || embedUrl.contains("fembed-hd.com") ||
                    embedUrl.contains("gcloud.live") || embedUrl.contains("vcdnplay.com") || embedUrl.contains("superplayxyz.club") ||
                    embedUrl.contains("vidohd.com") || embedUrl.contains("vidsource.me") || embedUrl.contains("cinegrabber.com") ||
                    embedUrl.contains("votrefile.xyz") || embedUrl.contains("zidiplay.com") || embedUrl.contains("ndrama.xyz") ||
                    embedUrl.contains("fcdn.stream") || embedUrl.contains("mediashore.org") || embedUrl.contains("suzihaza.com") ||
                    embedUrl.contains("there.to") || embedUrl.contains("femax20.com") || embedUrl.contains("javstream.top") ||
                    embedUrl.contains("viplayer.cc") || embedUrl.contains("sexhd.co") || embedUrl.contains("fembed.net") ||
                    embedUrl.contains("mrdhan.com") || embedUrl.contains("votrefilms.xyz") || // embedUrl.contains("") ||
                    embedUrl.contains("embedsito.com") || embedUrl.contains("dutrag.com") || // embedUrl.contains("") ||
                    embedUrl.contains("youvideos.ru") || embedUrl.contains("streamm4u.club") || // embedUrl.contains("") ||
                    embedUrl.contains("moviepl.xyz") || embedUrl.contains("asianclub.tv") || // embedUrl.contains("") ||
                    embedUrl.contains("vidcloud.fun") || embedUrl.contains("fplayer.info") || // embedUrl.contains("") ||
                    embedUrl.contains("diasfem.com") || embedUrl.contains("javpoll.com") || embedUrl.contains("reeoov.tube") ||
                    embedUrl.contains("suzihaza.com") || embedUrl.contains("ezsubz.com") || embedUrl.contains("vidsrc.xyz") ||
                    embedUrl.contains("diampokusy.com") || embedUrl.contains("diampokusy.com") || embedUrl.contains("i18n.pw") ||
                    embedUrl.contains("vanfem.com") || embedUrl.contains("fembed9hd.com") || embedUrl.contains("votrefilms.xyz") || embedUrl.contains("watchjavnow.xyz")
                -> {
                    val videos = FembedExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }
                embedUrl.contains("animetitans.net")
                -> {
                    val headers = headers.newBuilder()
                        .set("Referer", "$baseUrl/")
                        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36")
                        .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("Accept-Encoding", "gzip, deflate, br")
                        .build()
                    val videos = AnimeTitansExtractor(client).videosFromUrl(embedUrl, headers)
                    videoList.addAll(videos)
                }
                embedUrl.contains("4shared") -> {
                    val video = SharedExtractor(client).videoFromUrl(embedUrl, qualityy)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                /*embedUrl.contains("mp4upload") -> {
                    val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                    val video = Mp4uploadExtractor().getVideoFromUrl(embedUrl, headers)
                    videoList.add(video)
                }*/
                embedUrl.contains("vidbom.com") ||
                    embedUrl.contains("vidbem.com") || embedUrl.contains("vidbm.com") || embedUrl.contains("vedpom.com") ||
                    embedUrl.contains("vedbom.com") || embedUrl.contains("vedbom.org") || embedUrl.contains("vadbom.com") ||
                    embedUrl.contains("vidbam.org") || embedUrl.contains("myviid.com") || embedUrl.contains("myviid.net") ||
                    embedUrl.contains("myvid.com") || embedUrl.contains("vidshare.com") || embedUrl.contains("vedsharr.com") ||
                    embedUrl.contains("vedshar.com") || embedUrl.contains("vedshare.com") || embedUrl.contains("vadshar.com") || embedUrl.contains("vidshar.org")
                -> {
                    val videos = VidBomExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // Search
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        if (query.startsWith(URL_SEARCH_PREFIX).not()) return super.fetchSearchAnime(page, query, filters)

        val AnimePath = try {
            animePathFromUrl(query.substringAfter(URL_SEARCH_PREFIX))
                ?: return Observable.just(AnimesPage(emptyList(), false))
        } catch (e: Exception) {
            return Observable.error(e)
        }

        return fetchAnimeDetails(
            SAnime.create()
                .apply { this.url = "$AnimeUrlDirectory/$AnimePath" }
        )
            .map {
                // Isn't set in returned Anime
                it.url = "$AnimeUrlDirectory/$id"
                AnimesPage(listOf(it), false)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url.addPathSegments("page/$page/").addQueryParameter("s", query)
        } else {
            url.addPathSegment(AnimeUrlDirectory.substring(1)).addPathSegments("page/$page/")
            filters.forEach { filter ->
                when (filter) {
                    is AuthorFilter -> {
                        url.addQueryParameter("author", filter.state)
                    }
                    is YearFilter -> {
                        url.addQueryParameter("yearx", filter.state)
                    }
                    is StatusFilter -> {
                        url.addQueryParameter("status", filter.selectedValue())
                    }
                    is TypeFilter -> {
                        url.addQueryParameter("type", filter.selectedValue())
                    }
                    is OrderByFilter -> {
                        url.addQueryParameter("order", filter.selectedValue())
                    }
                    is GenreListFilter -> {
                        filter.state
                            .filter { it.state != AnimeFilter.TriState.STATE_IGNORE }
                            .forEach {
                                val value = if (it.state == AnimeFilter.TriState.STATE_EXCLUDE) "-${it.value}" else it.value
                                url.addQueryParameter("genre[]", value)
                            }
                    }
                    is SeasonListFilter -> {
                        filter.state
                            .filter { it.state != AnimeFilter.TriState.STATE_IGNORE }
                            .forEach {
                                val value = if (it.state == AnimeFilter.TriState.STATE_EXCLUDE) "-${it.value}" else it.value
                                url.addQueryParameter("season[]", value)
                            }
                    }
                    is StudioListFilter -> {
                        filter.state
                            .filter { it.state != AnimeFilter.TriState.STATE_IGNORE }
                            .forEach {
                                val value = if (it.state == AnimeFilter.TriState.STATE_EXCLUDE) "-${it.value}" else it.value
                                url.addQueryParameter("studio[]", value)
                            }
                    }

                    else -> { /* Do Nothing */ }
                }
            }
        }
        return GET(url.toString())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (genrelist == null) {
            genrelist = parseGenres(response.asJsoup(response.peekBody(Long.MAX_VALUE).string()))
            seasonlist = parseSeasons(response.asJsoup(response.peekBody(Long.MAX_VALUE).string()))
            studiolist = parseStudios(response.asJsoup(response.peekBody(Long.MAX_VALUE).string()))
        }

        return super.searchAnimeParse(response)
    }

    override fun searchAnimeSelector() = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.select("img").attr("src")
        title = element.select("a").attr("title")
        setUrlWithoutDomain(element.select("a").attr("href"))
    }

    override fun searchAnimeNextPageSelector() = "div.pagination .next, div.hpage .r"

    // Anime details
    open val seriesDetailsSelector = "div.bigcontent, div.animefull, div.main-info, div.postbody"
    open val seriesTitleSelector = "h1.entry-title"
    open val seriesArtistSelector = "span:contains(الاستديو) a"
    open val seriesAuthorSelector = "span:contains(المخرج) a"
    open val seriesDescriptionSelector = ".entry-content[itemprop=description]"
    open val seriesAltNameSelector = ".alter"
    open val seriesGenreSelector = ".genxed a"
    open val seriesTypeSelector = "span:contains(النوع)"
    open val seriesStatusSelector = "span:contains(الحالة)"
    open val seriesThumbnailSelector = ".thumb img"

    open val altNamePrefix = " :أسماء أخرى"

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst(seriesDetailsSelector)?.let { seriesDetails ->
            title = seriesDetails.selectFirst(seriesTitleSelector)?.text().orEmpty()
            artist = seriesDetails.selectFirst(seriesArtistSelector)?.ownText().removeEmptyPlaceholder()
            author = seriesDetails.selectFirst(seriesAuthorSelector)?.ownText().removeEmptyPlaceholder()
            description = seriesDetails.select(seriesDescriptionSelector).joinToString("\n") { it.text() }
            // Add alternative name to Anime description
            val altName = seriesDetails.selectFirst(seriesAltNameSelector)?.ownText().takeIf { it.isNullOrBlank().not() }
            altName?.let {
                description = "$description\n\n$altName$altNamePrefix".trim()
            }
            val genres = seriesDetails.select(seriesGenreSelector).map { it.text() }.toMutableList()
            // Add series type (Anime/manhwa/manhua/other) to genre
            seriesDetails.selectFirst(seriesTypeSelector)?.ownText().takeIf { it.isNullOrBlank().not() }?.let { genres.add(it) }
            genre = genres.map { genre ->
                genre.lowercase(Locale.forLanguageTag(lang)).replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.forLanguageTag(lang))
                    else char.toString()
                }
            }
                .joinToString { it.trim() }

            status = seriesDetails.selectFirst(seriesStatusSelector)?.text().parseStatus()
            thumbnail_url = seriesDetails.select(seriesThumbnailSelector).attr("src")
        }
    }

    private fun String?.removeEmptyPlaceholder(): String? {
        return if (this.isNullOrBlank() || this == "-" || this == "N/A") null else this
    }

    open fun String?.parseStatus(): Int = when {
        this == null -> SAnime.UNKNOWN
        listOf("مستمر", "publishing").any { this.contains(it, ignoreCase = true) } -> SAnime.ONGOING
        this.contains("مكتمل", ignoreCase = true) -> SAnime.COMPLETED
        // this.contains("مؤجل", ignoreCase = true) -> SAnime.HIATUS
        else -> SAnime.UNKNOWN
    }

    // Filters
    private class AuthorFilter : AnimeFilter.Text("Author")

    private class YearFilter : AnimeFilter.Text("Year")

    open class SelectFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: String? = null
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0
    ) {
        fun selectedValue() = vals[state].second
    }

    protected class StatusFilter : SelectFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
            Pair("Hiatus", "hiatus"),
            Pair("UpComing", "upcoming")
        )
    )

    protected class TypeFilter : SelectFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("Anime", "tv"),
            Pair("Movie", "movie"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special")
        )
    )

    protected class OrderByFilter(defaultOrder: String? = null) : SelectFilter(
        "Sort By",
        arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular")
        ),
        defaultOrder
    )

    protected class Genre(name: String, val value: String) : AnimeFilter.TriState(name)
    protected class GenreListFilter(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genre", genres)

    private var genrelist: List<Genre>? = null
    protected open fun getGenreList(): List<Genre> {
        // Filters are fetched immediately once an extension loads
        // We're only able to get filters after a loading the Anime directory,
        // and resetting the filters is the only thing that seems to reinflate the view
        return genrelist ?: listOf(Genre("Press reset to attempt to fetch genres", ""))
    }

    protected class Season(name: String, val value: String) : AnimeFilter.TriState(name)
    protected class SeasonListFilter(seasons: List<Season>) : AnimeFilter.Group<Season>("Season", seasons)

    private var seasonlist: List<Season>? = null
    protected open fun getSeasonList(): List<Season> {
        return seasonlist ?: listOf(Season("Press reset to attempt to fetch Seasons", ""))
    }

    protected class Studio(name: String, val value: String) : AnimeFilter.TriState(name)
    protected class StudioListFilter(studios: List<Studio>) : AnimeFilter.Group<Studio>("Studio", studios)

    private var studiolist: List<Studio>? = null
    protected open fun getStudioList(): List<Studio> {
        return studiolist ?: listOf(Studio("Press reset to attempt to fetch Studios", ""))
    }

    override fun getFilterList(): AnimeFilterList {
        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Separator(),
            AuthorFilter(),
            YearFilter(),
            StatusFilter(),
            TypeFilter(),
            OrderByFilter(),
            AnimeFilter.Header("Genre exclusion is not available for all sources"),
            GenreListFilter(getGenreList()),
            SeasonListFilter(getSeasonList()),
            StudioListFilter(getStudioList()),
        )
        return AnimeFilterList(filters)
    }

    // Helpers
    /**
     * Given some string which represents an http urlString, returns path for a Anime
     * which can be used to fetch its details at "$baseUrl$AnimeUrlDirectory/$AnimePath"
     *
     * @param urlString: String
     *
     * @returns Path of a Anime, or null if none could be found
     */
    private fun animePathFromUrl(urlString: String): String? {
        val baseAnimeUrl = "$baseUrl$AnimeUrlDirectory".toHttpUrl()
        val url = urlString.toHttpUrlOrNull() ?: return null

        val isAnimeUrl = (baseAnimeUrl.host == url.host && pathLengthIs(url, 2) && url.pathSegments[0] == baseAnimeUrl.pathSegments[0])
        if (isAnimeUrl) return url.pathSegments[1]

        val potentiallyChapterUrl = pathLengthIs(url, 1)
        if (potentiallyChapterUrl) {
            val response = client.newCall(GET(urlString, headers)).execute()
            if (response.isSuccessful.not()) {
                response.close()
                throw IllegalStateException("HTTP error ${response.code}")
            } else if (response.isSuccessful) {
                val links = response.asJsoup().select("a[itemprop=item]")
                //  near the top of page: home > Anime > current chapter
                if (links.size == 3) {
                    return links[1].attr("href").toHttpUrlOrNull()?.encodedPath
                }
            }
        }

        return null
    }

    private fun pathLengthIs(url: HttpUrl, n: Int, strict: Boolean = false): Boolean {
        return url.pathSegments.size == n && url.pathSegments[n - 1].isNotEmpty() ||
            (!strict && url.pathSegments.size == n + 1 && url.pathSegments[n].isEmpty())
    }

    private fun parseGenres(document: Document): List<Genre>? {
        return document.selectFirst("div.filter:contains(التصنيف) ul.scrollz")?.select("li")?.map { li ->
            Genre(
                li.selectFirst("label").text(),
                li.selectFirst("input[type=checkbox]").attr("value")
            )
        }
    }

    private fun parseSeasons(document: Document): List<Season>? {
        return document.selectFirst("div.filter:contains(الموسم) ul.scrollz")?.select("li")?.map { li ->
            Season(
                li.selectFirst("label").text(),
                li.selectFirst("input[type=checkbox]").attr("value")
            )
        }
    }

    private fun parseStudios(document: Document): List<Studio>? {
        return document.selectFirst("div.filter:contains(الاستديو) ul.scrollz")?.select("li")?.map { li ->
            Studio(
                li.selectFirst("label").text(),
                li.selectFirst("input[type=checkbox]").attr("value")
            )
        }
    }

    // Unused
    override fun popularAnimeSelector(): String = throw UnsupportedOperationException("Not used")
    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException("Not used")
    override fun popularAnimeNextPageSelector(): String? = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException("Not used")

    companion object {
        const val URL_SEARCH_PREFIX = "url:"

        // More info: https://issuetracker.google.com/issues/36970498
        @Suppress("RegExpRedundantEscape")
        private val ANIME_PAGE_ID_REGEX = "post_id\\s*:\\s*(\\d+)\\}".toRegex()
        private val CHAPTER_PAGE_ID_REGEX = "chapter_id\\s*=\\s*(\\d+);".toRegex()

        val JSON_IMAGE_LIST_REGEX = "\"images\"\\s*:\\s*(\\[.*?])".toRegex()
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "4shared")
            entryValues = arrayOf("1080", "720", "480", "360", "Doodstream", "4shared")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
