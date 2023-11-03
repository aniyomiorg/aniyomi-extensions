package eu.kanade.tachiyomi.animeextension.pt.anidong

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AniDongFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (getFirst<R>() as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .toList()
    }

    class StatusFilter : QueryPartFilter("Status", AniDongFiltersData.STATUS_LIST)
    class FormatFilter : QueryPartFilter("Formato", AniDongFiltersData.FORMAT_LIST)

    class GenresFilter : CheckBoxFilterList("Gêneros", AniDongFiltersData.GENRES_LIST)

    val FILTER_LIST get() = AnimeFilterList(
        StatusFilter(),
        FormatFilter(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val status: String = "",
        val format: String = "",
        val genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<FormatFilter>(),
            filters.parseCheckbox<GenresFilter>(AniDongFiltersData.GENRES_LIST),
        )
    }

    private object AniDongFiltersData {
        private val SELECT = Pair("<Selecione>", "")

        val STATUS_LIST = arrayOf(
            SELECT,
            Pair("Lançamento", "Lançamento"),
            Pair("Completo", "Completo"),
        )

        val FORMAT_LIST = arrayOf(
            SELECT,
            Pair("Donghua", "Anime"),
            Pair("Filme", "Filme"),
        )

        val GENRES_LIST = arrayOf(
            Pair("Artes Marciais", "9"),
            Pair("Aventura", "6"),
            Pair("Ação", "2"),
            Pair("Boys Love", "43"),
            Pair("Comédia", "15"),
            Pair("Corrida", "94"),
            Pair("Cultivo", "12"),
            Pair("Demônios", "18"),
            Pair("Detetive", "24"),
            Pair("Drama", "16"),
            Pair("Escolar", "77"),
            Pair("Espaço", "54"),
            Pair("Esporte", "95"),
            Pair("Fantasia", "7"),
            Pair("Guerra", "26"),
            Pair("Harém", "17"),
            Pair("Histórico", "8"),
            Pair("Horror", "44"),
            Pair("Isekai", "72"),
            Pair("Jogo", "25"),
            Pair("Mecha", "40"),
            Pair("Militar", "21"),
            Pair("Mistério", "3"),
            Pair("Mitolgia", "96"),
            Pair("Mitologia", "19"),
            Pair("O Melhor Donghua", "91"),
            Pair("Polícia", "57"),
            Pair("Política", "63"),
            Pair("Psicológico", "33"),
            Pair("Reencarnação", "30"),
            Pair("Romance", "11"),
            Pair("Sci-Fi", "39"),
            Pair("Slice of Life", "84"),
            Pair("Sobrenatural", "4"),
            Pair("Super Poder", "67"),
            Pair("Suspense", "32"),
            Pair("Tragédia", "58"),
            Pair("Vampiro", "82"),
        )
    }
}
