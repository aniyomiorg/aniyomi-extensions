package eu.kanade.tachiyomi.animeextension.pt.animesup

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimesUpFilters {

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

    class GenreFilter : QueryPartFilter("Gênero", AnimesUpFiltersData.genres)

    val filterList = AnimeFilterList(
        AnimeFilter.Header(AnimesUpFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter()
    )

    data class FilterSearchParams(
        val genre: String = ""
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(filters.asQueryPart<GenreFilter>())
    }

    private object AnimesUpFiltersData {

        const val IGNORE_SEARCH_MSG = "NOTA: O filtro por gênero será IGNORADO ao usar a pesquisa por nome."

        val genres = arrayOf(
            Pair("Ação", "acao"),
            Pair("Alienígena", "alienigena"),
            Pair("Aventura", "aventura"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Comédia", "comedia"),
            Pair("Culinária", "culinaria"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Esporte", "esporte"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "sci-fi"),
            Pair("Harém", "harem"),
            Pair("Isekai", "isekai"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Overpower", "overpower"),
            Pair("Reencarnação", "reencarnacao"),
            Pair("Romance", "romance"),
            Pair("Seinen", "seinen"),
            Pair("Shounen", "shounen"),
            Pair("Shoujo", "shoujo"),
            Pair("Slice Of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Vampiro", "vampiro"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri")
        )
    }
}
