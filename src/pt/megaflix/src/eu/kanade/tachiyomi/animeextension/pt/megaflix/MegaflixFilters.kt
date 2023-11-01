package eu.kanade.tachiyomi.animeextension.pt.megaflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MegaflixFilters {

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

    class GenreFilter : QueryPartFilter("Gênero", MegaflixFiltersData.GENRES)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header(MegaflixFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter(),
    )

    fun getGenre(filters: AnimeFilterList) = filters.asQueryPart<GenreFilter>()

    private object MegaflixFiltersData {
        const val IGNORE_SEARCH_MSG = "NOTA: O filtro é IGNORADO ao usar a pesquisa."

        val GENRES = arrayOf(
            Pair("Animação", "animacao"),
            Pair("Aventura", "aventura"),
            Pair("Ação", "acao"),
            Pair("Biografia", "biografia"),
            Pair("Comédia", "comedia"),
            Pair("Crime", "crime"),
            Pair("Documentário", "documentario"),
            Pair("Drama", "drama"),
            Pair("Esporte", "esporte"),
            Pair("Família", "familia"),
            Pair("Fantasia", "fantasia"),
            Pair("Faroeste", "faroeste"),
            Pair("Ficção científica", "ficcao-cientifica"),
            Pair("Guerra", "guerra"),
            Pair("História", "historia"),
            Pair("Mistério", "misterio"),
            Pair("Musical", "musical"),
            Pair("Música", "musica"),
            Pair("Romance", "romance"),
            Pair("Show", "show"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
        )
    }
}
