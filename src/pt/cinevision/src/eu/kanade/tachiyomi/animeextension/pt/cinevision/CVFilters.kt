package eu.kanade.tachiyomi.animeextension.pt.cinevision

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object CVFilters {

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

    class GenreFilter : QueryPartFilter("Gênero", CVFiltersData.genres)

    val filterList = AnimeFilterList(
        AnimeFilter.Header(CVFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter()
    )

    data class FilterSearchParams(
        val genre: String = ""
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(filters.asQueryPart<GenreFilter>())
    }

    private object CVFiltersData {

        const val IGNORE_SEARCH_MSG = "NOTA: O filtro por gênero será IGNORADO ao usar a pesquisa por nome."

        val genres = arrayOf(
            Pair("Ação", "acao-online-1"),
            Pair("Animação", "animacao-online"),
            Pair("Aventura", "aventura-online"),
            Pair("Comédia", "comedia-online"),
            Pair("Crime", "crime-online"),
            Pair("Documentário", "documentario-online"),
            Pair("Drama", "drama-online"),
            Pair("Família", "familia-online"),
            Pair("Fantasia", "fantasia-online"),
            Pair("Faroeste", "faroeste-online"),
            Pair("Ficção científica", "ficcao-cientifica-online"),
            Pair("Guerra", "guerra-online"),
            Pair("Mistério", "misterio-online"),
            Pair("Música", "musica-online"),
            Pair("Romance", "romance-online"),
            Pair("Terror", "terror-online"),
            Pair("Thriller", "thriller-online")
        )
    }
}
