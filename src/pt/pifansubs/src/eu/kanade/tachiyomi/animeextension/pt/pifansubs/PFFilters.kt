package eu.kanade.tachiyomi.animeextension.pt.pifansubs

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object PFFilters {

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

    class GenreFilter : QueryPartFilter("Gênero", PFFiltersData.genres)

    val filterList = AnimeFilterList(
        AnimeFilter.Header(PFFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter()
    )

    data class FilterSearchParams(
        val genre: String = ""
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(filters.asQueryPart<GenreFilter>())
    }

    private object PFFiltersData {

        const val IGNORE_SEARCH_MSG = "NOTA: O filtro por gênero será IGNORADO ao usar a pesquisa por nome."

        val genres = arrayOf(
            Pair("+18", "18"),
            Pair("Adulto", "adulto"),
            Pair("Animação", "animacao"),
            Pair("Anime", "anime"),
            Pair("Aventura", "aventura"),
            Pair("Ação e Aventura", "adventure"),
            Pair("Ação", "acao"),
            Pair("Comédia", "comedia"),
            Pair("Crime", "crime"),
            Pair("Drama", "drama"),
            Pair("Escolar", "escolar"),
            Pair("Família", "familia"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção científica", "cientifica"),
            Pair("Guerra", "guerra"),
            Pair("Histórico", "historia"),
            Pair("Mistério", "misterio"),
            Pair("Musical", "musica"),
            Pair("Programa de Tv", "news"),
            Pair("Reality", "reality"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi & Fantasy", "fantasy"),
            Pair("Shounen-Ai", "ai"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Yaoi", "yaoi")
        )
    }
}
