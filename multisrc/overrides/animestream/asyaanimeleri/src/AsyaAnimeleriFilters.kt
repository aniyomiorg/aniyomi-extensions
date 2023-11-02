package eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.CheckBoxFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.QueryPartFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.asQueryPart
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.filterInitialized
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.getPairListByIndex
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.parseCheckbox

object AsyaAnimeleriFilters {

    internal class GenresFilter(name: String) : CheckBoxFilterList(name, GENRES_LIST)
    internal class StudioFilter(name: String) : CheckBoxFilterList(name, STUDIO_LIST)
    internal class CountryFilter(name: String) : CheckBoxFilterList(name, COUNTRY_LIST)

    internal class NetworkFilter(name: String) : CheckBoxFilterList(name, NETWORK_LIST)

    internal class StatusFilter(name: String) : QueryPartFilter(name, STATUS_LIST)
    internal class TypeFilter(name: String) : QueryPartFilter(name, TYPE_LIST)
    internal class OrderFilter(name: String) : QueryPartFilter(name, ORDER_LIST)

    internal data class FilterSearchParams(
        val genres: String = "",
        val studios: String = "",
        val countries: String = "",
        val networks: String = "",
        val status: String = "",
        val type: String = "",
        val order: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty() || !filterInitialized()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(GENRES_LIST, "genre"),
            filters.parseCheckbox<StudioFilter>(STUDIO_LIST, "studio"),
            filters.parseCheckbox<CountryFilter>(COUNTRY_LIST, "country"),
            filters.parseCheckbox<NetworkFilter>(NETWORK_LIST, "network"),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<OrderFilter>(),
        )
    }

    private val GENRES_LIST by lazy { getPairListByIndex(0) }
    private val STUDIO_LIST by lazy { getPairListByIndex(2) }
    private val COUNTRY_LIST by lazy { getPairListByIndex(3) }
    private val NETWORK_LIST by lazy { getPairListByIndex(4) }
    private val STATUS_LIST by lazy { getPairListByIndex(5) }
    private val TYPE_LIST by lazy { getPairListByIndex(6) }
    private val ORDER_LIST by lazy { getPairListByIndex(7) }
}
