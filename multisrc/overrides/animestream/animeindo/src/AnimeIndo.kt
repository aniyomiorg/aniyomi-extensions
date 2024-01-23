package eu.kanade.tachiyomi.animeextension.id.animeindo

import android.util.Log
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.OrderFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.SeasonFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.StudioFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.TypeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Element

class AnimeIndo : AnimeStream(
    "id",
    "AnimeIndo",
    "https://animeindo.skin",
) {
    override val animeListUrl = "$baseUrl/browse"

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/browse?sort=view&page=$page")

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/browse?sort=created_at&page=$page")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeIndoFilters.getSearchParameters(filters)
        val multiString = buildString {
            if (params.genres.isNotEmpty()) append(params.genres + "&")
            if (params.seasons.isNotEmpty()) append(params.seasons + "&")
            if (params.studios.isNotEmpty()) append(params.studios + "&")
        }

        return GET("$animeListUrl/browse?page=$page&title=$query&$multiString&status=${params.status}&type=${params.type}&order=${params.order}")
    }

    override fun searchAnimeSelector() = "div.animepost > div > a"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("div.title")!!.text()
        thumbnail_url = element.selectFirst("img")!!.getImageUrl()
    }

    override fun searchAnimeNextPageSelector() = "div.pagination a:has(i#nextpagination)"

    // ============================== Filters ===============================
    override val filtersSelector = "div.filtersearch tbody > tr:not(:has(td.filter_title:contains(Search))) > td.filter_act"

    override fun getFilterList(): AnimeFilterList {
        return if (AnimeStreamFilters.filterInitialized()) {
            AnimeFilterList(
                OrderFilter(orderFilterText),
                StatusFilter(statusFilterText),
                TypeFilter(typeFilterText),
                AnimeFilter.Separator(),
                GenresFilter(genresFilterText),
                SeasonFilter(seasonsFilterText),
                StudioFilter(studioFilterText),
            )
        } else {
            AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
        }
    }

    // =========================== Anime Details ============================
    override fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()?.lowercase()) {
            "finished airing" -> SAnime.COMPLETED
            "currently airing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.listeps li:has(.epsleft)"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val ahref = element.selectFirst("a")!!
        setUrlWithoutDomain(ahref.attr("href"))
        val num = ahref.text()
        name = "Episode $num"
        episode_number = num.trim().toFloatOrNull() ?: 0F
        date_upload = element.selectFirst("span.date")?.text().toDate()
    }

    // ============================ Video Links =============================
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return with(name) {
            when {
                contains("streamtape") -> streamTapeExtractor.videoFromUrl(url)?.let(::listOf).orEmpty()
                contains("mp4") -> mp4uploadExtractor.videosFromUrl(url, headers)
                contains("yourupload") -> yourUploadExtractor.videoFromUrl(url, headers)
                url.contains("ok.ru") -> okruExtractor.videosFromUrl(url)
                contains("gdrive") -> {
                    val gdriveUrl = when {
                        baseUrl in url -> "https:" + url.toHttpUrl().queryParameter("data")!!
                        else -> url
                    }
                    gdrivePlayerExtractor.videosFromUrl(gdriveUrl, "Gdrive", headers)
                }
                else -> {
                    // just to detect video hosts easily
                    Log.i("AnimeIndo", "Unrecognized at getVideoList => Name -> $name || URL => $url")
                    emptyList()
                }
            }
        }
    }
}
