package eu.kanade.tachiyomi.animeextension.en.uniquestream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class UniqueStream : DooPlay(
    "en",
    "UniqueStream",
    "https://uniquestream.net",
) {

    private val json: Json by injectLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ratings/${page.toPage()}")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector() = "div.pagination > *:last-child:not(span):not(.current)"

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val recentFilter = filterList.find { it is RecentFilter } as RecentFilter
        val yearFilter = filterList.find { it is YearFilter } as YearFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/${page.toPage()}?s=$cleanQuery", headers = headers)
            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}/${page.toPage()}/", headers = headers)
            recentFilter.state != 0 -> GET("$baseUrl/${recentFilter.toUriPart()}/${page.toPage()}", headers = headers)
            yearFilter.state != 0 -> GET("$baseUrl/release/${yearFilter.toUriPart()}/${page.toPage()}", headers = headers)
            else -> popularAnimeRequest(page)
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        RecentFilter(),
        YearFilter(),
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
            Pair("2024", "2024"),
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

    override val fetchGenres = false

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = client.newCall(
            GET(baseUrl + episode.url, headers = headers),
        ).execute().asJsoup()

        val type = if (episode.url.startsWith("/tvshows/")) "tv" else "movie"

        document.select("ul#playeroptionsul > li:not([id=player-option-trailer])").forEach { server ->
            val postBody = "action=doo_player_ajax&post=${server.attr("data-post")}&nume=${server.attr("data-nume")}&type=$type"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val postHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Origin", baseUrl)
                .add("Referer", "$baseUrl${episode.url}")
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val embedResponse = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", body = postBody, headers = postHeaders),
            ).execute()

            val embedUrl = json.decodeFromString<EmbedResponse>(
                embedResponse.body.string(),
            ).embed_url.replace(Regex("^//"), "https://")

            val embedHeaders = headers.newBuilder()
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Host", embedUrl.toHttpUrl().host)
                .set("Referer", "$baseUrl/")
                .build()

            val embedDocument = client.newCall(
                GET(embedUrl, headers = embedHeaders),
            ).execute().asJsoup()

            val script = embedDocument.selectFirst("script:containsData(m3u8)")!!.data()
            val playlistUrl = script
                .substringAfter("let url = '").substringBefore("'")

            val subtitleList = mutableListOf<Track>()
            if (script.contains("srt")) {
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
            }

            val playlistHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .set("Referer", playlistUrl)
                .build()

            val masterPlaylist = client.newCall(
                GET(playlistUrl, headers = playlistHeaders),
            ).execute().body.string()

            val playlistHost = playlistUrl.toHttpUrl().host

            val audioList = mutableListOf<Track>()
            if (masterPlaylist.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
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
            }

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = it.substringAfter("RESOLUTION=").substringAfter("x")
                        .substringBefore("\n").substringBefore(",") + "p"
                    var videoUrl = it.substringAfter("\n").substringBefore("\n")

                    if (!videoUrl.startsWith("http")) {
                        videoUrl = "https://$playlistHost$videoUrl"
                    }

                    if (audioList.isEmpty()) {
                        videoList.add(Video(videoUrl, quality, videoUrl, headers = playlistHeaders, subtitleTracks = subtitleList))
                    } else {
                        videoList.add(
                            Video(videoUrl, quality, videoUrl, headers = playlistHeaders, subtitleTracks = subtitleList, audioTracks = audioList),
                        )
                    }
                }
        }

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList.sort()
    }

    // ============================== Settings ==============================

    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    override val prefQualityEntries = prefQualityValues

    // ============================= Utilities ==============================

    @Serializable
    data class EmbedResponse(
        val embed_url: String,
    )

    override fun Element.getImageUrl(): String? {
        return when {
            hasAttr("data-wpfc-original-src") -> attr("abs:data-wpfc-original-src")
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Int.toPage(): String {
        return if (this == 1) "" else "page/$this/"
    }
}
