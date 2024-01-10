package eu.kanade.tachiyomi.animeextension.pt.anitube

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnitubeFilters {
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

    class GenreFilter : QueryPartFilter("Gênero", AnitubeFiltersData.GENRES)
    class CharacterFilter : QueryPartFilter("Inicia com", AnitubeFiltersData.INITIAL_CHARS)
    class YearFilter : QueryPartFilter("Ano", AnitubeFiltersData.YEARS)
    class SeasonFilter : QueryPartFilter("Temporada", AnitubeFiltersData.SEASONS)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header(AnitubeFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter(),
        CharacterFilter(),
        AnimeFilter.Header(AnitubeFiltersData.IGNORE_SEASON_MSG),
        SeasonFilter(),
        YearFilter(),
    )

    data class FilterSearchParams(
        val genre: String = "",
        val season: String = "",
        val year: String = "2024",
        val initialChar: String = "todos",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(
            filters.asQueryPart<GenreFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<CharacterFilter>(),
        )
    }

    private object AnitubeFiltersData {

        const val IGNORE_SEARCH_MSG = "NOTA: Os filtros abaixos são IGNORADOS durante a pesquisa."
        const val IGNORE_SEASON_MSG = "Nota: o filtro de temporada IGNORA o filtro de gênero/letra."
        val EVERY = Pair("Qualquer um", "")

        val SEASONS = arrayOf(
            EVERY,
            Pair("Outono", "outono"),
            Pair("Inverno", "inverno"),
            Pair("Primavera", "primavera"),
            Pair("Verão", "verao"),
        )

        val YEARS = (2024 downTo 1979).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val INITIAL_CHARS = arrayOf(
            Pair("Qualquer letra", "todos"),
        ) + ('A'..'Z').map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRES = arrayOf(
            EVERY,
            Pair("Ação", "acao"),
            Pair("Artes marciais", "artes-marciais"),
            Pair("Aventura", "aventura"),
            Pair("CGI", "cgi"),
            Pair("Comédia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demônios", "demonios"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Espaço", "espaco"),
            Pair("Esporte", "esporte"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harém", "harem"),
            Pair("Harém Reverso", "harem-reverso"),
            Pair("Hentai", "hentai"),
            Pair("Histórico", "historico"),
            Pair("Idol", "idol"),
            Pair("Isekai", "isekai"),
            Pair("Jogos", "jogos"),
            Pair("Josei", "josei"),
            Pair("Kodomo", "kodomo"),
            Pair("Live Action", "live-action"),
            Pair("Magia", "magia"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Mundo Virtual", "mundo-virtual"),
            Pair("Musical", "musical"),
            Pair("Paródia", "parodia"),
            Pair("Policial", "policial"),
            Pair("Pós-Apocalíptico", "pos-apocaliptico"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo-ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen-ai", "shounen-ai"),
            Pair("Slice of life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoder", "superpoder"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Tokusatsu", "tokusatsu"),
            Pair("Tragédia", "tragedia"),
            Pair("Vampiros", "vampiros"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}
