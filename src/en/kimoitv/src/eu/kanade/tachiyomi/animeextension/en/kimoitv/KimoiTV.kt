package eu.kanade.tachiyomi.animeextension.en.kimoitv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KimoiTV : ParsedAnimeHttpSource() {

    override val name = "KimoiTV"

    override val baseUrl = "https://kimoitv.com"

    override val lang = "en"

    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/list/Anime.html?sort=top".addPage(page), headers)

    override fun popularAnimeSelector(): String = "ul.media > li"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.item")!!.attr("abs:href"))
        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
        title = element.selectFirst("div:matchesOwn(.):not(.text-muted)")!!.ownText()
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.page-item:has(a.bg-dark) ~ li.page-item > a"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/Anime.html?sort=newest".addPage(page), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sortFilter = filterList.find { it is SortFilter } as SortFilter
        val firstSelected = filterList.firstOrNull {
            it.state != 0 && it !is SortFilter
        }?.let { it as UriPartFilter }

        return when {
            query.isNotBlank() -> GET("$baseUrl/search/?q=$query".addPage(page), headers)
            firstSelected != null -> GET("$baseUrl${firstSelected.toUriPart()}${sortFilter.toUriPart()}".addPage(page), headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div#pilled img:not(.image)")?.attr("abs:src")
        description = document.select("div#description > p").joinToString("\n\n") { it.text() }
        genre = document.select("div.section > div > div.chip").joinToString(", ") { it.text() }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select(episodeListSelector()).forEach { season ->
            var nextPageUrl: String? = season.attr("abs:href")
            var counter = 1

            while (nextPageUrl != null) {
                val doc = client.newCall(GET(nextPageUrl)).execute().asJsoup()

                doc.select(episodeListSelector()).forEach { ep ->
                    episodeList.add(
                        SEpisode.create().apply {
                            name = "${season.text()} - ${ep.ownText()}"
                            setUrlWithoutDomain(ep.attr("abs:href"))
                            episode_number = ep.ownText().substringAfter("E").toFloatOrNull() ?: counter.toFloat()
                            scanlator = ep.selectFirst("span")?.text()
                        },
                    )
                    counter++
                }

                nextPageUrl = doc.selectFirst(popularAnimeNextPageSelector())?.attr("abs:href")
            }
        }

        return episodeList
    }

    override fun episodeListSelector() = "ul.link-listview > li > a"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val infoDiv = document.selectFirst("div#fileInfo[data-id]") ?: error("Failed to fetch video")

        val postBody = "d=${infoDiv.attr("data-name")}&id=${infoDiv.attr("data-id")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val postHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Origin", baseUrl)
            .add("Referer", response.request.url.toString())
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val videoDoc = client.newCall(
            POST("$baseUrl/streamvpaid.php", body = postBody, headers = postHeaders),
        ).execute().asJsoup()

        val videoList = videoDoc.select("source").map { src ->
            val videoUrl = src.attr("abs:src")
            val videoHeaders = headers.newBuilder()
                .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                .add("Host", videoUrl.toHttpUrl().host)
                .add("Referer", "$baseUrl/")
                .build()

            Video(videoUrl, "Video", videoUrl, headers = videoHeaders)
        }

        require(videoList.isNotEmpty()) { "Failed to fetch video" }

        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun String.addPage(page: Int): String {
        return if (page == 1) {
            this
        } else {
            this.toHttpUrl().newBuilder().addQueryParameter("page", page.toString()).toString()
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Only one selection at a time works, and it ignores text search"),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Sort applies to every selection"),
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Sub page"),
        SubPageFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Genres"),
        AllGenreFilter(),
        TVSeriesGenreFilter(),
        MovieGenreFilter(),
        DramaGenreFilter(),
        AnimeGenreFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("A-Z List"),
        TVSeriesAZFilter(),
        MoviesAZFilter(),
        DramaAZFilter(),
    )

    private class SortFilter : UriPartFilter(
        "Sort",
        arrayOf(
            Pair("Update", ""),
            Pair("Recently Added", "?sort=newest"),
            Pair("Popularity", "?sort=top"),
        ),
    )

    // Sub page

    private class SubPageFilter : UriPartFilter(
        "Sub-page",
        arrayOf(
            Pair("<select>", ""),
            Pair("TV Series", "/list/Tv-series.html"),
            Pair("Korean Drama", "/list/K-drama.html"),
            Pair("Chinese Drama", "/list/C-drama.html"),
            Pair("Anime", "/list/Anime.html"),
            Pair("Hollywood Movies", "/list/Hollywood.html"),
            Pair("Korean Movies", "/list/K-movie.html"),
            Pair("Chinese Movies", "/list/C-movie.html"),
            Pair("Animation", "/browse/Animation.html"),
        ),
    )

    // Genre

    private class AllGenreFilter : UriPartFilter(
        "All",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/browse/Action.html"),
            Pair("Adventure", "/browse/Adventure.html"),
            Pair("Business", "/browse/Business.html"),
            Pair("Comedy", "/browse/Comedy.html"),
            Pair("Crime", "/browse/Crime.html"),
            Pair("Drama", "/browse/Drama.html"),
            Pair("Family", "/browse/Family.html"),
            Pair("Fantasy", "/browse/Fantasy.html"),
            Pair("Game-Show", "/browse/GamedashShow.html"),
            Pair("History", "/browse/History.html"),
            Pair("Horror", "/browse/Horror.html"),
            Pair("Music", "/browse/Music.html"),
            Pair("Musical", "/browse/Musical.html"),
            Pair("Mystery", "/browse/Mystery.html"),
            Pair("Reality-TV", "/browse/RealitydashTV.html"),
            Pair("Romance", "/browse/Romance.html"),
            Pair("Sci-Fi", "/browse/ScidashFi.html"),
            Pair("Sport", "/browse/Sport.html"),
            Pair("Superhero", "/browse/Superhero.html"),
            Pair("Thriller", "/browse/Thriller.html"),
            Pair("Melodrama", "/browse/Melodrama.html"),
            Pair("Food", "/browse/Food.html"),
            Pair("Youth", "/browse/Youth.html"),
            Pair("School", "/browse/School.html"),
            Pair("Friendship", "/browse/Friendship.html"),
        ),
    )

    private class TVSeriesGenreFilter : UriPartFilter(
        "TV Series",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/genre/Tv-series/c/Action.html"),
            Pair("Adventure", "/genre/Tv-series/c/Adventure.html"),
            Pair("Animation", "/genre/Tv-series/c/Animation.html"),
            Pair("Biography", "/genre/Tv-series/c/Biography.html"),
            Pair("Comedy", "/genre/Tv-series/c/Comedy.html"),
            Pair("Crime", "/genre/Tv-series/c/Crime.html"),
            Pair("Documentary", "/genre/Tv-series/c/Documentary.html"),
            Pair("Drama", "/genre/Tv-series/c/Drama.html"),
            Pair("Family", "/genre/Tv-series/c/Family.html"),
            Pair("Fantasy", "/genre/Tv-series/c/Fantasy.html"),
            Pair("Game-Show", "/genre/Tv-series/c/GamedashShow.html"),
            Pair("History", "/genre/Tv-series/c/History.html"),
            Pair("Horror", "/genre/Tv-series/c/Horror.html"),
            Pair("Music", "/genre/Tv-series/c/Music.html"),
            Pair("Musical", "/genre/Tv-series/c/Musical.html"),
            Pair("Mystery", "/genre/Tv-series/c/Mystery.html"),
            Pair("News", "/genre/Tv-series/c/News.html"),
            Pair("Reality-TV", "/genre/Tv-series/c/RealitydashTV.html"),
            Pair("Romance", "/genre/Tv-series/c/Romance.html"),
            Pair("Sci-Fi", "/genre/Tv-series/c/ScidashFi.html"),
            Pair("Sport", "/genre/Tv-series/c/Sport.html"),
            Pair("Superhero", "/genre/Tv-series/c/Superhero.html"),
            Pair("Talk-Show", "/genre/Tv-series/c/TalkdashShow.html"),
            Pair("Thriller", "/genre/Tv-series/c/Thriller.html"),
            Pair("War", "/genre/Tv-series/c/War.html"),
            Pair("Western", "/genre/Tv-series/c/Western.html"),
        ),
    )

    private class MovieGenreFilter : UriPartFilter(
        "Movies",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/genre/Movies/c/Action.html"),
            Pair("Adventure", "/genre/Movies/c/Adventure.html"),
            Pair("Animation", "/genre/Movies/c/Animation.html"),
            Pair("Biography", "/genre/Movies/c/Biography.html"),
            Pair("Comedy", "/genre/Movies/c/Comedy.html"),
            Pair("Crime", "/genre/Movies/c/Crime.html"),
            Pair("Documentary", "/genre/Movies/c/Documentary.html"),
            Pair("Drama", "/genre/Movies/c/Drama.html"),
            Pair("Family", "/genre/Movies/c/Family.html"),
            Pair("Fantasy", "/genre/Movies/c/Fantasy.html"),
            Pair("Game-Show", "/genre/Movies/c/GamedashShow.html"),
            Pair("History", "/genre/Movies/c/History.html"),
            Pair("Horror", "/genre/Movies/c/Horror.html"),
            Pair("Music", "/genre/Movies/c/Music.html"),
            Pair("Musical", "/genre/Movies/c/Musical.html"),
            Pair("Mystery", "/genre/Movies/c/Mystery.html"),
            Pair("News", "/genre/Movies/c/News.html"),
            Pair("Reality-TV", "/genre/Movies/c/RealitydashTV.html"),
            Pair("Romance", "/genre/Movies/c/Romance.html"),
            Pair("Sci-Fi", "/genre/Movies/c/ScidashFi.html"),
            Pair("Sport", "/genre/Movies/c/Sport.html"),
            Pair("Superhero", "/genre/Movies/c/Superhero.html"),
            Pair("Talk-Show", "/genre/Movies/c/TalkdashShow.html"),
            Pair("Thriller", "/genre/Movies/c/Thriller.html"),
            Pair("War", "/genre/Movies/c/War.html"),
            Pair("Western", "/genre/Movies/c/Western.html"),
        ),
    )

    private class DramaGenreFilter : UriPartFilter(
        "Drama",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/genre/Drama/c/Action.html"),
            Pair("Adventure", "/genre/Drama/c/Adventure.html"),
            Pair("Business", "/genre/Drama/c/Business.html"),
            Pair("Comedy", "/genre/Drama/c/Comedy.html"),
            Pair("Crime", "/genre/Drama/c/Crime.html"),
            Pair("Drama", "/genre/Drama/c/Drama.html"),
            Pair("Family", "/genre/Drama/c/Family.html"),
            Pair("Fantasy", "/genre/Drama/c/Fantasy.html"),
            Pair("Game-Show", "/genre/Drama/c/GamedashShow.html"),
            Pair("History", "/genre/Drama/c/History.html"),
            Pair("Horror", "/genre/Drama/c/Horror.html"),
            Pair("Music", "/genre/Drama/c/Music.html"),
            Pair("Musical", "/genre/Drama/c/Musical.html"),
            Pair("Mystery", "/genre/Drama/c/Mystery.html"),
            Pair("Reality-TV", "/genre/Drama/c/RealitydashTV.html"),
            Pair("Romance", "/genre/Drama/c/Romance.html"),
            Pair("Sci-Fi", "/genre/Drama/c/ScidashFi.html"),
            Pair("Sport", "/genre/Drama/c/Sport.html"),
            Pair("Superhero", "/genre/Drama/c/Superhero.html"),
            Pair("Thriller", "/genre/Drama/c/Thriller.html"),
            Pair("Melodrama", "/genre/Drama/c/Melodrama.html"),
            Pair("Food", "/genre/Drama/c/Food.html"),
            Pair("Youth", "/genre/Drama/c/Youth.html"),
            Pair("School", "/genre/Drama/c/School.html"),
            Pair("Friendship", "/genre/Drama/c/Friendship.html"),
        ),
    )

    private class AnimeGenreFilter : UriPartFilter(
        "Anime",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/genre/Anime/c/Action.html"),
            Pair("Adventure", "/genre/Anime/c/Adventure.html"),
            Pair("Supernatural", "/genre/Anime/c/Supernatural.html"),
            Pair("Comedy", "/genre/Anime/c/Comedy.html"),
            Pair("Ecchi", "/genre/Anime/c/Ecchi.html"),
            Pair("Crime", "/genre/Anime/c/Crime.html"),
            Pair("Drama", "/genre/Anime/c/Drama.html"),
            Pair("Family", "/genre/Anime/c/Family.html"),
            Pair("Fantasy", "/genre/Anime/c/Fantasy.html"),
            Pair("Psychological", "/genre/Anime/c/Psychological.html"),
            Pair("History", "/genre/Anime/c/History.html"),
            Pair("Horror", "/genre/Anime/c/Horror.html"),
            Pair("Music", "/genre/Anime/c/Music.html"),
            Pair("Musical", "/genre/Anime/c/Musical.html"),
            Pair("Mystery", "/genre/Anime/c/Mystery.html"),
            Pair("Romance", "/genre/Anime/c/Romance.html"),
            Pair("Sci-Fi", "/genre/Anime/c/ScidashFi.html"),
            Pair("Sport", "/genre/Anime/c/Sport.html"),
            Pair("Slice of Life", "/genre/Anime/c/Slice-of-Life.html"),
            Pair("Thriller", "/genre/Anime/c/Thriller.html"),
            Pair("Shounen", "/genre/Anime/c/Shounen.html"),
            Pair("Shoujo", "/genre/Anime/c/Shoujo.html"),
            Pair("Seinen", "/genre/Anime/c/Seinen.html"),
            Pair("Food", "/genre/Anime/c/Food.html"),
            Pair("Youth", "/genre/Anime/c/Youth.html"),
            Pair("School", "/genre/Anime/c/School.html"),
        ),
    )

    // A-Z

    private class TVSeriesAZFilter : UriPartFilter(
        "TV Series",
        arrayOf(
            Pair("<select>", ""),
            Pair("0 - 9", "/alpha/Tv-series/c/0.html"),
            Pair("A", "/alpha/Tv-series/c/A.html"),
            Pair("B", "/alpha/Tv-series/c/B.html"),
            Pair("C", "/alpha/Tv-series/c/C.html"),
            Pair("D", "/alpha/Tv-series/c/D.html"),
            Pair("E", "/alpha/Tv-series/c/E.html"),
            Pair("F", "/alpha/Tv-series/c/F.html"),
            Pair("G", "/alpha/Tv-series/c/G.html"),
            Pair("H", "/alpha/Tv-series/c/H.html"),
            Pair("I", "/alpha/Tv-series/c/I.html"),
            Pair("J", "/alpha/Tv-series/c/J.html"),
            Pair("K", "/alpha/Tv-series/c/K.html"),
            Pair("L", "/alpha/Tv-series/c/L.html"),
            Pair("M", "/alpha/Tv-series/c/M.html"),
            Pair("N", "/alpha/Tv-series/c/N.html"),
            Pair("O", "/alpha/Tv-series/c/O.html"),
            Pair("P", "/alpha/Tv-series/c/P.html"),
            Pair("Q", "/alpha/Tv-series/c/Q.html"),
            Pair("R", "/alpha/Tv-series/c/R.html"),
            Pair("S", "/alpha/Tv-series/c/S.html"),
            Pair("T", "/alpha/Tv-series/c/T.html"),
            Pair("U", "/alpha/Tv-series/c/U.html"),
            Pair("V", "/alpha/Tv-series/c/V.html"),
            Pair("W", "/alpha/Tv-series/c/W.html"),
            Pair("X", "/alpha/Tv-series/c/X.html"),
            Pair("Y", "/alpha/Tv-series/c/Y.html"),
            Pair("Z", "/alpha/Tv-series/c/Z.html"),
        ),
    )

    private class MoviesAZFilter : UriPartFilter(
        "Movies",
        arrayOf(
            Pair("<select>", ""),
            Pair("0 - 9", "/alpha/Movies/c/0.html"),
            Pair("A", "/alpha/Movies/c/A.html"),
            Pair("B", "/alpha/Movies/c/B.html"),
            Pair("C", "/alpha/Movies/c/C.html"),
            Pair("D", "/alpha/Movies/c/D.html"),
            Pair("E", "/alpha/Movies/c/E.html"),
            Pair("F", "/alpha/Movies/c/F.html"),
            Pair("G", "/alpha/Movies/c/G.html"),
            Pair("H", "/alpha/Movies/c/H.html"),
            Pair("I", "/alpha/Movies/c/I.html"),
            Pair("J", "/alpha/Movies/c/J.html"),
            Pair("K", "/alpha/Movies/c/K.html"),
            Pair("L", "/alpha/Movies/c/L.html"),
            Pair("M", "/alpha/Movies/c/M.html"),
            Pair("N", "/alpha/Movies/c/N.html"),
            Pair("O", "/alpha/Movies/c/O.html"),
            Pair("P", "/alpha/Movies/c/P.html"),
            Pair("Q", "/alpha/Movies/c/Q.html"),
            Pair("R", "/alpha/Movies/c/R.html"),
            Pair("S", "/alpha/Movies/c/S.html"),
            Pair("T", "/alpha/Movies/c/T.html"),
            Pair("U", "/alpha/Movies/c/U.html"),
            Pair("V", "/alpha/Movies/c/V.html"),
            Pair("W", "/alpha/Movies/c/W.html"),
            Pair("X", "/alpha/Movies/c/X.html"),
            Pair("Y", "/alpha/Movies/c/Y.html"),
            Pair("Z", "/alpha/Movies/c/Z.html"),
        ),
    )

    private class DramaAZFilter : UriPartFilter(
        "Drama",
        arrayOf(
            Pair("<select>", ""),
            Pair("0 - 9", "/alpha/Drama/c/0.html"),
            Pair("A", "/alpha/Drama/c/A.html"),
            Pair("B", "/alpha/Drama/c/B.html"),
            Pair("C", "/alpha/Drama/c/C.html"),
            Pair("D", "/alpha/Drama/c/D.html"),
            Pair("E", "/alpha/Drama/c/E.html"),
            Pair("F", "/alpha/Drama/c/F.html"),
            Pair("G", "/alpha/Drama/c/G.html"),
            Pair("H", "/alpha/Drama/c/H.html"),
            Pair("I", "/alpha/Drama/c/I.html"),
            Pair("J", "/alpha/Drama/c/J.html"),
            Pair("K", "/alpha/Drama/c/K.html"),
            Pair("L", "/alpha/Drama/c/L.html"),
            Pair("M", "/alpha/Drama/c/M.html"),
            Pair("N", "/alpha/Drama/c/N.html"),
            Pair("O", "/alpha/Drama/c/O.html"),
            Pair("P", "/alpha/Drama/c/P.html"),
            Pair("Q", "/alpha/Drama/c/Q.html"),
            Pair("R", "/alpha/Drama/c/R.html"),
            Pair("S", "/alpha/Drama/c/S.html"),
            Pair("T", "/alpha/Drama/c/T.html"),
            Pair("U", "/alpha/Drama/c/U.html"),
            Pair("V", "/alpha/Drama/c/V.html"),
            Pair("W", "/alpha/Drama/c/W.html"),
            Pair("X", "/alpha/Drama/c/X.html"),
            Pair("Y", "/alpha/Drama/c/Y.html"),
            Pair("Z", "/alpha/Drama/c/Z.html"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
