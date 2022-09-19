package eu.kanade.tachiyomi.animeextension.pt.animesonlinex

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AOXFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray()
    ) {
        fun toQueryPart() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return this.filterIsInstance<R>().joinToString("") {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class GenreFilter : QueryPartFilter("Gênero", AOXFiltersData.genres)

    val filterList = AnimeFilterList(
        AnimeFilter.Header(AOXFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter()
    )

    data class FilterSearchParams(
        val genre: String = ""
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(filters.asQueryPart<GenreFilter>())
    }

    private object AOXFiltersData {

        const val IGNORE_SEARCH_MSG = "NOTA: O filtro por gênero será IGNORADO ao usar a pesquisa por nome."

        val genres = arrayOf(
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Aventura", "aventura"),
            Pair("Ação", "acao"),
            Pair("Comédia", "comedia"),
            Pair("Crime", "crime"),
            Pair("Demônio", "demonio"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Esporte", "esport"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Cientifica", "ficcao-cientifica"),
            Pair("Histórico", "historico"),
            Pair("Josei", "josei"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Romance", "romance"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Terror", "terror")
        )
    }
}
