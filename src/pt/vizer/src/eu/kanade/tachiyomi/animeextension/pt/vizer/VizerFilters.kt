package eu.kanade.tachiyomi.animeextension.pt.vizer

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object VizerFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {

        fun toQueryPart() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    class TypeFilter : QueryPartFilter("Tipo", VizerFiltersData.TYPES)
    class MinYearFilter : QueryPartFilter("Ano (min)", VizerFiltersData.MIN_YEARS)
    class MaxYearFilter : QueryPartFilter("Ano (max)", VizerFiltersData.MAX_YEARS)
    class GenreFilter : QueryPartFilter("Categoria", VizerFiltersData.GENRES)

    class SortFilter : AnimeFilter.Sort(
        "Ordernar por",
        VizerFiltersData.ORDERS.map { it.first }.toTypedArray(),
        Selection(0, false),
    )

    val FILTER_LIST get() = AnimeFilterList(
        TypeFilter(),
        MinYearFilter(),
        MaxYearFilter(),
        GenreFilter(),
        SortFilter(),
    )

    data class FilterSearchParams(
        val type: String = "anime",
        val minYear: String = "1890",
        val maxYear: String = "2022",
        val genre: String = "all",
        val orderBy: String = "rating",
        val orderWay: String = "desc",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val sortFilter = filters.firstOrNull { it is SortFilter } as? SortFilter
        val (orderBy, ascending) = sortFilter?.state?.run {
            val order = VizerFiltersData.ORDERS[index].second
            val orderWay = if (ascending) "asc" else "desc"

            Pair(order, orderWay)
        } ?: Pair("rating", "desc")

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<MinYearFilter>(),
            filters.asQueryPart<MaxYearFilter>(),
            filters.asQueryPart<GenreFilter>(),
            orderBy,
            ascending,
        )
    }

    private object VizerFiltersData {
        val TYPES = arrayOf(
            Pair("Animes", "anime"),
            Pair("Filmes", "Movies"),
            Pair("Series", "Series"),
        )
        val MAX_YEARS = (2022 downTo 1890).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val MIN_YEARS = MAX_YEARS.reversed().toTypedArray()

        val ORDERS = arrayOf(
            Pair("Popularidade", "vzViews"),
            Pair("Ano", "year"),
            Pair("Título", "title"),
            Pair("Rating", "rating"),
        )

        val GENRES = arrayOf(
            Pair("Todas", "all"),
            Pair("Animação", "animacao"),
            Pair("Aventura", "aventura"),
            Pair("Ação", "acao"),
            Pair("Comédia", "comedia"),
            Pair("Crime", "crime"),
            Pair("Documentário", "documentario"),
            Pair("Drama", "drama"),
            Pair("Família", "familia"),
            Pair("Fantasia", "fantasia"),
            Pair("Faroeste", "faroeste"),
            Pair("Guerra", "guerra"),
            Pair("LGBTQ+", "lgbt"),
            Pair("Mistério", "misterio"),
            Pair("Músical", "musical"),
            Pair("Romance", "romance"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
        )
    }
}
