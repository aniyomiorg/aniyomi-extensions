package eu.kanade.tachiyomi.animeextension.pt.betteranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object BAFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray()
    ) {

        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return this.filterIsInstance<R>().joinToString("") {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class LanguageFilter : QueryPartFilter("Idioma", BAFiltersData.languages)
    class YearFilter : QueryPartFilter("Ano", BAFiltersData.years)

    class GenresFilter : CheckBoxFilterList(
        "Gêneros",
        BAFiltersData.genres.map { CheckBoxVal(it.first, false) }
    )

    val filterList = AnimeFilterList(
        LanguageFilter(),
        YearFilter(),
        GenresFilter()
    )

    data class FilterSearchParams(
        val language: String = "",
        val year: String = "",
        val genres: List<String> = emptyList<String>()
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val genres = listOf("") + filters.getFirst<GenresFilter>().state
            .mapNotNull { genre ->
                if (genre.state) {
                    BAFiltersData.genres.find { it.first == genre.name }!!.second
                } else { null }
            }.toList()

        return FilterSearchParams(
            filters.asQueryPart<LanguageFilter>(),
            filters.asQueryPart<YearFilter>(),
            genres
        )
    }

    private object BAFiltersData {
        val every = Pair("Qualquer um", "")

        val languages = arrayOf(
            every,
            Pair("Legendado", "legendado"),
            Pair("Dublado", "dublado")
        )

        val years = arrayOf(every) + (2022 downTo 1976).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val genres = arrayOf(
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
