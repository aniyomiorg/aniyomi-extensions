package eu.kanade.tachiyomi.animeextension.id.animeindo

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.CheckBoxFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.QueryPartFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.asQueryPart
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.filterElements
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.filterInitialized
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.parseCheckbox

object AnimeIndoFilters {
    internal class GenresFilter(name: String) : CheckBoxFilterList(name, GENRES_LIST)
    internal class SeasonFilter(name: String) : CheckBoxFilterList(name, SEASON_LIST)
    internal class StudioFilter(name: String) : CheckBoxFilterList(name, STUDIO_LIST)

    internal class StatusFilter(name: String) : QueryPartFilter(name, STATUS_LIST)
    internal class TypeFilter(name: String) : QueryPartFilter(name, TYPE_LIST)
    internal class OrderFilter(name: String) : QueryPartFilter(name, ORDER_LIST)

    internal data class FilterSearchParams(
        val genres: String = "",
        val seasons: String = "",
        val studios: String = "",
        val status: String = "",
        val type: String = "",
        val order: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        if (!filterInitialized()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(GENRES_LIST, "genre"),
            filters.parseCheckbox<SeasonFilter>(SEASON_LIST, "season"),
            filters.parseCheckbox<StudioFilter>(STUDIO_LIST, "studio"),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<OrderFilter>(),
        )
    }

    private fun getPairListByIndex(index: Int) = filterElements.get(index)
        .select("ul > li, td > label")
        .map { element ->
            val key = element.text()
            val value = element.selectFirst("input")!!.attr("value")
            Pair(key, value)
        }.toTypedArray()

    private val ORDER_LIST by lazy {
        getPairListByIndex(0)
            .filterNot { it.first.contains("Most favorite", true) }
            .toTypedArray()
    }

    private val STATUS_LIST by lazy { getPairListByIndex(1) }
    private val TYPE_LIST by lazy { getPairListByIndex(2) }
    private val GENRES_LIST by lazy { getPairListByIndex(3) }
    private val SEASON_LIST by lazy { getPairListByIndex(4) }
    private val STUDIO_LIST by lazy { getPairListByIndex(5) }
}
