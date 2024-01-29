package eu.kanade.tachiyomi.animeextension.fr.mykdrama

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.CheckBoxFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.asQueryPart
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.filterInitialized
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.getPairListByIndex
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.parseCheckbox

object MyKdramaFilters {

    internal class GenresFilter(name: String) : CheckBoxFilterList(name, GENRES_LIST)
    internal class CountryFilter(name: String) : CheckBoxFilterList(name, COUNTRY_LIST)

    internal class StatusFilter(name: String) : AnimeStreamFilters.QueryPartFilter(name, STATUS_LIST)
    internal class TypeFilter(name: String) : AnimeStreamFilters.QueryPartFilter(name, TYPE_LIST)
    internal class OrderFilter(name: String) : AnimeStreamFilters.QueryPartFilter(name, ORDER_LIST)

    internal data class FilterSearchParams(
        val genres: String = "",
        val countries: String = "",
        val status: String = "",
        val type: String = "",
        val order: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty() || !filterInitialized()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(GENRES_LIST, "genre"),
            filters.parseCheckbox<CountryFilter>(COUNTRY_LIST, "country"),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<OrderFilter>(),
        )
    }

    private val GENRES_LIST by lazy { getPairListByIndex(0) }
    private val COUNTRY_LIST by lazy { getPairListByIndex(3) }
    private val STATUS_LIST by lazy { getPairListByIndex(5) }
    private val TYPE_LIST by lazy { getPairListByIndex(6) }
    private val ORDER_LIST by lazy { getPairListByIndex(7) }
}
