package eu.kanade.tachiyomi.animeextension.pt.animesgames

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimesGamesFilters {

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

    class AudioFilter : QueryPartFilter("Audio", AnimesGamesFiltersData.AUDIOS)
    class LetterFilter : QueryPartFilter("Primeira letra", AnimesGamesFiltersData.LETTERS)
    class OrderFilter : QueryPartFilter("Ordenar por", AnimesGamesFiltersData.ORDERS)

    class GenresFilter : TriStateFilterList(
        "Gêneros",
        AnimesGamesFiltersData.GENRES.map { TriFilterVal(it.first) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        AudioFilter(),
        LetterFilter(),
        OrderFilter(),
        AnimeFilter.Separator(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val audio: String = "0",
        val letter: String = "0",
        val orderBy: String = "name",
        val genres: List<String> = emptyList(),
        val deleted_genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        val (added, deleted) = filters.parseTriFilter<GenresFilter>(AnimesGamesFiltersData.GENRES)

        return FilterSearchParams(
            filters.asQueryPart<AudioFilter>(),
            filters.asQueryPart<LetterFilter>(),
            filters.asQueryPart<OrderFilter>(),
            added,
            deleted,
        )
    }

    private object AnimesGamesFiltersData {
        val AUDIOS = arrayOf(
            Pair("Todos", "0"),
            Pair("Legendado", "legendado"),
            Pair("Dublado", "dublado"),
        )

        val LETTERS = arrayOf(Pair("Selecione", "0")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val ORDERS = arrayOf(
            Pair("Nome", "name"),
            Pair("Nota", "new"),
        )

        val GENRES = arrayOf(
            Pair("ASMR", "65"),
            Pair("Adaptação de Manga", "49"),
            Pair("Animação", "11"),
            Pair("Artes Marciais", "8"),
            Pair("Aventura", "5"),
            Pair("Ação", "7"),
            Pair("Bishounen", "45"),
            Pair("Boys Love", "67"),
            Pair("Comédia Romântica", "44"),
            Pair("Comédia", "9"),
            Pair("Cotidiano", "56"),
            Pair("Demônios", "35"),
            Pair("Drama", "20"),
            Pair("Ecchi", "31"),
            Pair("Escolar", "38"),
            Pair("Esporte", "21"),
            Pair("Fantasia", "12"),
            Pair("Fatia de Vida", "66"),
            Pair("Ficção Científica", "23"),
            Pair("Game", "58"),
            Pair("Harém", "36"),
            Pair("Histórico", "33"),
            Pair("Infantil", "62"),
            Pair("Isekai", "59"),
            Pair("Jogos", "14"),
            Pair("Magia", "13"),
            Pair("Mecha", "42"),
            Pair("Militar", "26"),
            Pair("Mistério", "24"),
            Pair("Mitologia", "72"),
            Pair("Musica", "70"),
            Pair("Musical", "34"),
            Pair("Paródia", "63"),
            Pair("Policial", "30"),
            Pair("Psicológico", "39"),
            Pair("Romance", "15"),
            Pair("Ryuri", "41"),
            Pair("Samurai", "32"),
            Pair("School", "55"),
            Pair("Sci-fi", "48"),
            Pair("Seinen", "27"),
            Pair("Shoujo", "17"),
            Pair("Shoujo-ai", "47"),
            Pair("Shounen Ai", "57"),
            Pair("Shounen", "4"),
            Pair("Sitcom", "61"),
            Pair("Slice Of Life", "19"),
            Pair("Sobrenatural", "18"),
            Pair("Super Poder", "6"),
            Pair("Suspense", "25"),
            Pair("Terror", "22"),
            Pair("Thriller", "43"),
            Pair("Vampiros", "28"),
            Pair("Vida escolar", "16"),
            Pair("Yaoi", "64"),
            Pair("Yuri", "40"),
        )
    }
}
