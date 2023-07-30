package eu.kanade.tachiyomi.animeextension.id.animeindo

import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.OrderFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.SeasonFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.StudioFilter
import eu.kanade.tachiyomi.animeextension.id.animeindo.AnimeIndoFilters.TypeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Element

class AnimeIndo : AnimeStream(
    "id",
    "AnimeIndo",
    "https://animeindo.quest",
) {
    override val animeListUrl = "$baseUrl/pages/animelist"

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/page/$page/?order=popular")

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/page/$page/?order=update")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeIndoFilters.getSearchParameters(filters)
        val multiString = buildString {
            if (params.genres.isNotEmpty()) append(params.genres + "&")
            if (params.seasons.isNotEmpty()) append(params.seasons + "&")
            if (params.studios.isNotEmpty()) append(params.studios + "&")
        }

        return GET("$animeListUrl/page/$page/?title=$query&$multiString&status=${params.status}&type=${params.type}&order=${params.order}")
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
}
