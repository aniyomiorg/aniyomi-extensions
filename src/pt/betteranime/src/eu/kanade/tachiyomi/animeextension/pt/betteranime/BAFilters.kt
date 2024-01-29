package eu.kanade.tachiyomi.animeextension.pt.betteranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object BAFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {

        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (first { it is R } as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .toList()
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    class LanguageFilter : QueryPartFilter("Idioma", BAFiltersData.LANGUAGES)
    class YearFilter : QueryPartFilter("Ano", BAFiltersData.YEARS)

    class GenresFilter : CheckBoxFilterList(
        "Gêneros",
        BAFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        LanguageFilter(),
        YearFilter(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val language: String = "",
        val year: String = "",
        val genres: List<String> = emptyList<String>(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<LanguageFilter>(),
            filters.asQueryPart<YearFilter>(),
            filters.parseCheckbox<GenresFilter>(BAFiltersData.GENRES),
        )
    }

    private object BAFiltersData {
        val EVERY = Pair("Qualquer um", "")

        val LANGUAGES = arrayOf(
            EVERY,
            Pair("Legendado", "legendado"),
            Pair("Dublado", "dublado"),
        )

        val YEARS = arrayOf(EVERY) + (2024 downTo 1976).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRES = arrayOf(
            Pair("Ação", "acao"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Aventura", "aventura"),
            Pair("Comédia", "comedia"),
            Pair("Cotidiano", "cotidiano"),
            Pair("Demência", "demencia"),
            Pair("Demônios", "demonios"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Espacial", "espacial"),
            Pair("Esportes", "esportes"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Game", "game"),
            Pair("Harém", "harem"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Musical", "musical"),
            Pair("Paródia", "parodia"),
            Pair("Policial", "policial"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo-Ai", "shoujo-ai"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen-Ai", "shounen-ai"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Super Poderes", "super-poderes"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Tragédia", "tragedia"),
            Pair("Vampiros", "vampiros"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}
