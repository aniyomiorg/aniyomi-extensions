package eu.kanade.tachiyomi.animeextension.pt.openanimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object OpenAnimesFilters {

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

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return getFirst<R>().let {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class InitialLetterFilter : QueryPartFilter("Primeira letra", OpenAnimesFiltersData.INITIAL_LETTER)

    class SortFilter : AnimeFilter.Sort(
        "Ordenar",
        OpenAnimesFiltersData.ORDERS.map { it.first }.toTypedArray(),
        Selection(0, false),
    )

    class AudioFilter : QueryPartFilter("Língua/Áudio", OpenAnimesFiltersData.AUDIOS)

    class GenresFilter : CheckBoxFilterList(
        "Gêneros",
        OpenAnimesFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST = AnimeFilterList(
        InitialLetterFilter(),
        SortFilter(),
        AudioFilter(),
        AnimeFilter.Separator(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val sortBy: String = "popu-des",
        val audio: String = "0",
        val initialLetter: String = "0",
        val genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val order = filters.getFirst<SortFilter>().state?.let {
            val order = OpenAnimesFiltersData.ORDERS[it.index].second
            when {
                it.ascending -> "$order-asc"
                else -> "$order-des"
            }
        } ?: "popu-des"

        val genres = filters.getFirst<GenresFilter>().state
            .mapNotNull { genre ->
                if (genre.state) {
                    OpenAnimesFiltersData.GENRES.find { it.first == genre.name }!!.second
                } else { null }
            }.toList()

        return FilterSearchParams(
            order,
            filters.asQueryPart<AudioFilter>(),
            filters.asQueryPart<InitialLetterFilter>(),
            genres,
        )
    }

    private object OpenAnimesFiltersData {
        val ORDERS = arrayOf(
            Pair("Populares", "popu"),
            Pair("Alfabética", "alfa"),
            Pair("Lançamento", "lancamento"),
        )

        val INITIAL_LETTER = arrayOf(Pair("Selecione", "0")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val AUDIOS = arrayOf(
            Pair("Todos", "0"),
            Pair("Legendado", "legendado"),
            Pair("Dublado", "dublado"),
        )

        val GENRES = arrayOf(
            Pair("Ação", "7"),
            Pair("Adaptação de Manga", "49"),
            Pair("Animação", "11"),
            Pair("Artes Marciais", "8"),
            Pair("ASMR", "65"),
            Pair("Aventura", "5"),
            Pair("Bishounen", "45"),
            Pair("Boys Love", "67"),
            Pair("Comédia", "9"),
            Pair("Comédia Romântica", "44"),
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
            Pair("Shounen", "4"),
            Pair("Shounen Ai", "57"),
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
