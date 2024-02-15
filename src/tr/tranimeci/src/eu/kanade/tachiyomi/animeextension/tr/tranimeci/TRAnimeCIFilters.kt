package eu.kanade.tachiyomi.animeextension.tr.tranimeci

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.CheckBoxFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.QueryPartFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.asQueryPart
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.filterInitialized
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.getPairListByIndex
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.parseCheckbox

object TRAnimeCIFilters {
    internal class GenresFilter(name: String) : CheckBoxFilterList(name, GENRES_LIST)
    internal class CountryFilter(name: String) : QueryPartFilter(name, COUNTRY_LIST)
    internal class SeasonFilter(name: String) : QueryPartFilter(name, SEASON_LIST)

    internal class TypeFilter(name: String) : QueryPartFilter(name, TYPE_LIST)
    internal class StudioFilter(name: String) : QueryPartFilter(name, STUDIO_LIST)

    internal data class FilterSearchParams(
        val genres: String = "",
        val country: String = "",
        val season: String = "",
        val type: String = "",
        val studio: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty() || !filterInitialized()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(GENRES_LIST, "category"),
            filters.asQueryPart<CountryFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<StudioFilter>(),
        )
    }

    private fun getPairListByIndexSorted(index: Int) =
        getPairListByIndex(index)
            .sortedBy { it.first.lowercase() }
            .toTypedArray()

    private val EVERY get() = arrayOf(Pair("Tüm", ""))

    private val GENRES_LIST by lazy { getPairListByIndexSorted(0) }
    private val COUNTRY_LIST by lazy { EVERY + getPairListByIndexSorted(1) }
    private val SEASON_LIST by lazy { EVERY + getPairListByIndexSorted(2) }
    private val TYPE_LIST by lazy { EVERY + getPairListByIndexSorted(4) }
    private val STUDIO_LIST by lazy { EVERY + getPairListByIndexSorted(5) }
}
