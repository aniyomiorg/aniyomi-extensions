package eu.kanade.tachiyomi.animeextension.pt.animeshouse

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AHFilters {

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

    class GenreFilter : QueryPartFilter("Gênero", AHFiltersData.genres)

    val filterList = AnimeFilterList(
        AnimeFilter.Header(AHFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter()
    )

    data class FilterSearchParams(
        val genre: String = ""
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(filters.asQueryPart<GenreFilter>())
    }

    private object AHFiltersData {

        const val IGNORE_SEARCH_MSG = "NOTA: O filtro por gênero será IGNORADO ao usar a pesquisa por nome."

        val genres = arrayOf(
            Pair("Ação", "acao"),
            Pair("Aventura", "aventura"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Esporte", "esporte"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Harém", "harem"),
            Pair("Mecha", "mecha"),
            Pair("Mistério", "misterio"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Seinen", "seinen"),
            Pair("Shounen", "shounen"),
            Pair("Slice Of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes")
        )
    }
}
