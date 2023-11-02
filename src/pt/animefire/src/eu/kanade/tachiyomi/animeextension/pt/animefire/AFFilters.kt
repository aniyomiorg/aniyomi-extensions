package eu.kanade.tachiyomi.animeextension.pt.animefire

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AFFilters {
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

    class GenreFilter : QueryPartFilter("Gênero", AFFiltersData.GENRES)
    class SeasonFilter : QueryPartFilter("Temporada", AFFiltersData.SEASONS)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header(AFFiltersData.IGNORE_SEARCH_MSG),
        SeasonFilter(),
        AnimeFilter.Header(AFFiltersData.IGNORE_SEASON_MSG),
        GenreFilter(),
    )

    data class FilterSearchParams(
        val genre: String = "",
        val season: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(
            filters.asQueryPart<GenreFilter>(),
            filters.asQueryPart<SeasonFilter>(),
        )
    }

    private object AFFiltersData {

        const val IGNORE_SEARCH_MSG = "NOTA: Os filtros abaixos são IGNORADOS durante a pesquisa."
        const val IGNORE_SEASON_MSG = "NOTA: O filtro de gêneros IGNORA o de temporadas."
        val EVERY = Pair("Qualquer um", "")

        val SEASONS = arrayOf(
            EVERY,
            Pair("Outono", "outono"),
            Pair("Inverno", "inverno"),
            Pair("Primavera", "primavera"),
            Pair("Verão", "verao"),
        )

        val GENRES = arrayOf(
            Pair("Ação", "acao"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Aventura", "aventura"),
            Pair("Comédia", "comedia"),
            Pair("Demônios", "demonios"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Espaço", "espaco"),
            Pair("Esporte", "esporte"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Harém", "harem"),
            Pair("Horror", "horror"),
            Pair("Jogos", "jogos"),
            Pair("Josei", "josei"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Musical", "musical"),
            Pair("Paródia", "parodia"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo-ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoder", "superpoder"),
            Pair("Suspense", "suspense"),
            Pair("Vampiros", "vampiros"),
            Pair("Vida Escolar", "vida-escolar"),
        )
    }
}
