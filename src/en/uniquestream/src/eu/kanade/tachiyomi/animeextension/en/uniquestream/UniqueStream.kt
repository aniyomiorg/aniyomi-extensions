package eu.kanade.tachiyomi.animeextension.en.uniquestream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class UniqueStream : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "UniqueStream"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://uniquestreaming.net")!! }

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ratings/")

    override fun popularAnimeSelector(): String = "div.content > div.items > article"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = if (element.selectFirst("img")!!.hasAttr("data-wpfc-original-src")) {
                element.selectFirst("img")!!.attr("data-wpfc-original-src")
            } else {
                element.selectFirst("img")!!.attr("src")
            }
            title = element.selectFirst("div.data > h3")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not Used")

    override fun latestUpdatesSelector(): String = throw Exception("Not Used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not Used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not Used")

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage = throw Exception("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val (request, isExact) = searchAnimeRequestExact(page, query, filters)
        return client.newCall(request)
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response, isExact)
            }
    }

    private fun searchAnimeParse(response: Response, isExact: Boolean): AnimesPage {
        val document = response.asJsoup()

        if (isExact) {
            val anime = SAnime.create()
            anime.title = document.selectFirst("div.data > h1")!!.text()
            anime.thumbnail_url = if (document.selectFirst("div.poster > img")!!.hasAttr("data-wpfc-original-src")) {
                document.selectFirst("div.poster > img")!!.attr("data-wpfc-original-src")
            } else {
                document.selectFirst("div.poster > img")!!.attr("src")
            }
            anime.setUrlWithoutDomain(response.request.url.encodedPath)
            return AnimesPage(listOf(anime), false)
        }

        val animes = document.select(searchAnimeSelector()).map { element ->
            searchAnimeFromElement(element)
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    private fun searchAnimeRequestExact(page: Int, query: String, filters: AnimeFilterList): Pair<Request, Boolean> {
        val cleanQuery = query.replace(" ", "+").lowercase()

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val recentFilter = filterList.find { it is RecentFilter } as RecentFilter
        val yearFilter = filterList.find { it is YearFilter } as YearFilter
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        return when {
            query.isNotBlank() -> Pair(GET("$baseUrl/page/$page/?s=$cleanQuery", headers = headers), false)
            genreFilter.state != 0 -> Pair(GET("$baseUrl/genre/${genreFilter.toUriPart()}/page/$page/", headers = headers), false)
            recentFilter.state != 0 -> Pair(GET("$baseUrl/${recentFilter.toUriPart()}/page/$page/", headers = headers), false)
            yearFilter.state != 0 -> Pair(GET("$baseUrl/release/${yearFilter.toUriPart()}/page/$page/", headers = headers), false)
            urlFilter.state.isNotEmpty() -> Pair(GET(urlFilter.state, headers = headers), true)
            else -> Pair(popularAnimeRequest(page), false)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String? = "div.pagination > span.current ~ a"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        RecentFilter(),
        YearFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Get item url from webview"),
        URLFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Action & Adventure", "action-adventure"),
            Pair("Adventure", "adventure"),
            Pair("Animation", "animation"),
            Pair("Anime", "anime"),
            Pair("Asian", "asian"),
            Pair("Bollywood", "bollywood"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Documentary", "documentary"),
            Pair("Drama", "drama"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Foreign", "foreign"),
            Pair("History", "history"),
            Pair("Hollywood", "hollywood"),
            Pair("Horror", "horror"),
            Pair("Kids", "kids"),
            Pair("Korean", "korean"),
            Pair("Malay", "malay"),
            Pair("Malayalam", "malayalam"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("News", "news"),
            Pair("Reality", "reality"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
            Pair("Science Fiction", "science-fiction"),
            Pair("Soap", "soap"),
            Pair("Talk", "talk"),
            Pair("Tamil", "tamil"),
            Pair("Telugu", "telugu"),
            Pair("Thriller", "thriller"),
            Pair("TV Movie", "tv-movie"),
            Pair("War", "war"),
            Pair("War & Politics", "war-politics"),
            Pair("Western", "western"),
        ),
    )

    private class RecentFilter : UriPartFilter(
        "Recent",
        arrayOf(
            Pair("<select>", ""),
            Pair("Recent TV Shows", "tvshows"),
            Pair("Recent Movies", "movies"),
        ),
    )

    private class YearFilter : UriPartFilter(
        "Release Year",
        arrayOf(
            Pair("<select>", ""),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1999", "1999"),
            Pair("1998", "1998"),
            Pair("1997", "1997"),
            Pair("1996", "1996"),
            Pair("1995", "1995"),
            Pair("1994", "1994"),
            Pair("1993", "1993"),
            Pair("1992", "1992"),
            Pair("1991", "1991"),
            Pair("1990", "1990"),
            Pair("1989", "1989"),
            Pair("1988", "1988"),
            Pair("1987", "1987"),
            Pair("1986", "1986"),
            Pair("1985", "1985"),
            Pair("1984", "1984"),
            Pair("1983", "1983"),
            Pair("1982", "1982"),
            Pair("1981", "1981"),
            Pair("1980", "1980"),
            Pair("1979", "1979"),
            Pair("1978", "1978"),
            Pair("1977", "1977"),
            Pair("1976", "1976"),
            Pair("1975", "1975"),
            Pair("1974", "1974"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class URLFilter : AnimeFilter.Text("Url")

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("div.data > h1")!!.text()
            thumbnail_url = if (document.selectFirst("div.poster > img")!!.hasAttr("data-wpfc-original-src")) {
                document.selectFirst("div.poster > img")!!.attr("data-wpfc-original-src")
            } else {
                document.selectFirst("div.poster > img")!!.attr("src")
            }
            status = SAnime.COMPLETED
            description = document.selectFirst("div:contains(Synopsis) > div > p")?.text() ?: ""
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        if (response.request.url.encodedPath.startsWith("/movies/")) {
            val episode = SEpisode.create()
            episode.name = document.selectFirst("div.data > h1")!!.text().replace(":", "")
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(response.request.url.encodedPath)
            episodeList.add(episode)
        } else {
            var counter = 1
            for (season in document.select("div#seasons > div")) {
                val seasonList = mutableListOf<SEpisode>()
                for (ep in season.select("ul > li")) {
                    if (ep.childrenSize() > 0) {
                        val episode = SEpisode.create()

                        episode.name = "Season ${ep.selectFirst("div.numerando")!!.ownText()} - ${ep.selectFirst("a[href]")!!.ownText()}"
                        episode.episode_number = counter.toFloat()
                        episode.setUrlWithoutDomain(ep.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath)

                        seasonList.add(episode)
                        counter++
                    }
                }
                episodeList.addAll(seasonList)
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not Used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val videoList = mutableListOf<Video>()
        val document = client.newCall(
            GET(baseUrl + episode.url, headers = headers),
        ).execute().asJsoup()

        val type = if (episode.url.startsWith("/tvshows/")) "tv" else "movie"

        document.select("ul#playeroptionsul > li:not([id=player-option-trailer])").forEach { server ->
            val postBody = "action=doo_player_ajax&post=${server.attr("data-post")}&nume=${server.attr("data-nume")}&type=$type"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val postHeaders = Headers.headersOf(
                "Accept", "*/*",
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin", "https://uniquestreaming.net",
                "Referer", "$baseUrl${episode.url}",
                "User-Agent", "Mozilla/5.0 (Linux; Android 12; SM-T870 Build/SP2A.220305.013; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/106.0.5249.126 Safari/537.36",
                "X-Requested-With", "XMLHttpRequest",
            )

            val embedResponse = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", body = postBody, headers = postHeaders),
            ).execute()

            var embedUrl = json.decodeFromString<EmbedResponse>(embedResponse.body.string()).embed_url
            if (!embedUrl.startsWith("http")) embedUrl = "https:$embedUrl"

            val embedHeaders = Headers.headersOf(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Host",
                embedUrl.toHttpUrl().host,
                "Referer",
                "$baseUrl/",
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; SM-T870 Build/SP2A.220305.013; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/106.0.5249.126 Safari/537.36",
            )

            val embedDocument = client.newCall(
                GET(embedUrl, headers = embedHeaders),
            ).execute().asJsoup()

            val script = embedDocument.selectFirst("script:containsData(m3u8)")!!.data()
            val playlistUrl = script
                .substringAfter("let url = '").substringBefore("'")

            val subtitleList = mutableListOf<Track>()
            if (script.contains("srt")) {
                try {
                    subtitleList.add(
                        Track(
                            script.substringAfter("track['file']")
                                .substringAfter("'")
                                .substringBefore("'"),
                            script.substringAfter("track['label']")
                                .substringAfter("'")
                                .substringBefore("'"),
                        ),
                    )
                } catch (a: Exception) { }
            }

            val playlistHeaders = Headers.headersOf(
                "Accept",
                "*/*",
                "Referer",
                playlistUrl,
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; SM-T870 Build/SP2A.220305.013; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/106.0.5249.126 Safari/537.36",
            )

            val masterPlaylist = client.newCall(
                GET(playlistUrl, headers = playlistHeaders),
            ).execute().body.string()
            val playlistHost = playlistUrl.toHttpUrl().host

            val audioList = mutableListOf<Track>()
            if (masterPlaylist.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
                try {
                    val line = masterPlaylist.substringAfter("#EXT-X-MEDIA:TYPE=AUDIO")
                        .substringBefore("\n")
                    var audioUrl = line.substringAfter("URI=\"").substringBefore("\"")
                    if (!audioUrl.startsWith("http")) {
                        audioUrl = "https://$playlistHost$audioUrl"
                    }

                    audioList.add(
                        Track(
                            audioUrl,
                            line.substringAfter("NAME=\"").substringBefore("\""),
                        ),
                    )
                } catch (a: Exception) { }
            }

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = it.substringAfter("RESOLUTION=").substringAfter("x")
                        .substringBefore("\n").substringBefore(",") + "p"
                    var videoUrl = it.substringAfter("\n").substringBefore("\n")

                    if (!videoUrl.startsWith("http")) {
                        videoUrl = "https://$playlistHost$videoUrl"
                    }

                    try {
                        if (audioList.isEmpty()) {
                            videoList.add(Video(videoUrl, quality, videoUrl, headers = playlistHeaders, subtitleTracks = subtitleList))
                        } else {
                            videoList.add(
                                Video(videoUrl, quality, videoUrl, headers = playlistHeaders, subtitleTracks = subtitleList, audioTracks = audioList),
                            )
                        }
                    } catch (a: Exception) {
                        videoList.add(Video(videoUrl, quality, videoUrl, headers = playlistHeaders))
                    }
                }
        }

        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    @Serializable
    data class EmbedResponse(
        val embed_url: String,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("uniquestreaming.net")
            entryValues = arrayOf("https://uniquestreaming.net")
            setDefaultValue("https://uniquestreaming.net")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("2160p", "1080p", "720p", "480p")
            entryValues = arrayOf("2160", "1080", "720", "480")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
    }
}
