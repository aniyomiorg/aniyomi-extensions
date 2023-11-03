package eu.kanade.tachiyomi.animeextension.pt.donghuanosekai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object DonghuaNoSekaiFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, values: List<TriFilterVal>) : AnimeFilter.Group<TriState>(name, values)
    class TriFilterVal(name: String) : TriState(name)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(
        options: Array<Pair<String, String>>,
    ): List<List<String>> {
        return (first { it is R } as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to options.find { it.first == filter.name }!!.second }
            .groupBy { it.first } // group by state
            .let { dict ->
                val included = dict.get(TriState.STATE_INCLUDE)?.map { it.second }.orEmpty()
                val excluded = dict.get(TriState.STATE_EXCLUDE)?.map { it.second }.orEmpty()
                listOf(included, excluded)
            }
    }

    class LetterFilter : QueryPartFilter("Primeira letra", DonghuaNoSekaiFiltersData.LETTERS)
    class OrderFilter : QueryPartFilter("Ordenar por", DonghuaNoSekaiFiltersData.ORDERS)
    class StatusFilter : QueryPartFilter("Status", DonghuaNoSekaiFiltersData.STATUS)
    class AnimationFilter : QueryPartFilter("Tipo de animação", DonghuaNoSekaiFiltersData.ANIMATIONS)

    class GenresFilter : TriStateFilterList(
        "Gêneros",
        DonghuaNoSekaiFiltersData.GENRES.map { TriFilterVal(it.first) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        LetterFilter(),
        OrderFilter(),
        StatusFilter(),
        AnimationFilter(),
        AnimeFilter.Separator(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val letter: String = "0",
        val orderBy: String = "name",
        val status: String = "all",
        val animation: String = "all",
        val genres: List<String> = emptyList(),
        val deleted_genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        val (added, deleted) = filters.parseTriFilter<GenresFilter>(DonghuaNoSekaiFiltersData.GENRES)

        return FilterSearchParams(
            filters.asQueryPart<LetterFilter>(),
            filters.asQueryPart<OrderFilter>(),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<AnimationFilter>(),
            added,
            deleted,
        )
    }

    private object DonghuaNoSekaiFiltersData {
        val EVERY = Pair(" <Selecione> ", "all")

        val LETTERS = arrayOf(Pair("Selecione", "0")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val ORDERS = arrayOf(
            Pair("Nome", "name"),
            Pair("Data", "new"),
        )

        val ANIMATIONS = arrayOf(
            EVERY,
            Pair("2d", "2d"),
            Pair("3d", "3d"),
        )

        val STATUS = arrayOf(
            EVERY,
            Pair("Completo", "Completed"),
            Pair("Em Breve", "Upcoming"),
            Pair("Em Lançamento", "Ongoing"),
            Pair("Em Pausado", "Hiatus"),
        )

        val GENRES = arrayOf(
            Pair("Artes Marciais", "54"),
            Pair("Aventura", "4"),
            Pair("Ação", "2"),
            Pair("Boys Love", "208"),
            Pair("Carros", "408"),
            Pair("Comédia", "16"),
            Pair("Corrida", "406"),
            Pair("Crime", "392"),
            Pair("Cultivo", "5"),
            Pair("Demônios", "52"),
            Pair("Drama", "26"),
            Pair("Ecchi", "104"),
            Pair("Esporte", "407"),
            Pair("Fantasia", "6"),
            Pair("Ficção científica", "94"),
            Pair("Guerra", "22"),
            Pair("Harém Reverso", "364"),
            Pair("Harém", "111"),
            Pair("Histórico", "11"),
            Pair("Horror", "404"),
            Pair("Isekai", "105"),
            Pair("Jogo", "98"),
            Pair("Josei", "363"),
            Pair("Magia", "17"),
            Pair("Mecha", "93"),
            Pair("Militar", "87"),
            Pair("Mistério", "149"),
            Pair("Mitologia", "438"),
            Pair("Paródia", "443"),
            Pair("Politica", "71"),
            Pair("Polícia", "223"),
            Pair("Psicológico", "285"),
            Pair("Reencarnação", "249"),
            Pair("Romance", "12"),
            Pair("Seinen", "163"),
            Pair("Shoujo", "203"),
            Pair("Shounen Ai", "176"),
            Pair("Shounen", "33"),
            Pair("Slice of Life", "106"),
            Pair("Sobrenatural", "101"),
            Pair("Super Poder", "127"),
            Pair("Supernatural", "30"),
            Pair("Suspense", "283"),
            Pair("Terror", "229"),
            Pair("Thriller", "34"),
            Pair("Tragédia", "165"),
            Pair("Vampiro", "135"),
            Pair("Vida Escolar", "18"),
            Pair("Violência", "252"),
            Pair("Wuxia", "254"),
            Pair("Xuanhuan", "256"),
            Pair("Yaoi", "173"),
        )
    }
}
