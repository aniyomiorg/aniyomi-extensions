package eu.kanade.tachiyomi.animeextension.pt.doramogo

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object DoramogoFilters {

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

    class AudioFilter : QueryPartFilter("Audio", DoramogoFiltersData.AUDIOS)

    class GenreFilter : QueryPartFilter("Gênero", DoramogoFiltersData.GENRES)

    val FILTER_LIST
        get() = AnimeFilterList(
            AudioFilter(),
            AnimeFilter.Separator(),
            GenreFilter(),
        )

    class FilterSearchParams(
        val audio: String = "",
        val genre: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<AudioFilter>(),
            filters.asQueryPart<GenreFilter>(),
        )
    }

    private object DoramogoFiltersData {
        val AUDIOS = arrayOf(
            Pair("Todos", ""),
            Pair("Legendado", "legendados"),
            Pair("Dublado", "dublados"),
        )

        val GENRES = arrayOf(
            Pair("Todos", ""),
            Pair("Ação", "6"),
            Pair("Action & Adventure", "47"),
            Pair("Aventura", "50"),
            Pair("Business", "70"),
            Pair("Chinês", "20"),
            Pair("Comédia", "4"),
            Pair("Crime", " 18"),
            Pair("Documentário", "67"),
            Pair("Drama", " 19"),
            Pair("Escolar", "0"),
            Pair("Esporte", "8"),
            Pair("Família", "7"),
            Pair("Fantasia", "62"),
            Pair("Friendship", "31"),
            Pair("Histórico", " 53"),
            Pair("Horror", "91"),
            Pair("Japan", " 31"),
            Pair("Juventude", " 24"),
            Pair("Law", "9"),
            Pair("LGBTQ", " 35"),
            Pair("Life", "42"),
            Pair("Médico", "67"),
            Pair("Melodrama", " 32"),
            Pair("Militar", "8"),
            Pair("Mistério", "7 "),
            Pair("Música", "14"),
            Pair("Política", "84"),
            Pair("Psicológico", "7"),
            Pair("Reality", "7"),
            Pair("Romance", "5"),
            Pair("Sci-Fi & Fantasy", "47"),
            Pair("Sci-Fi", "23"),
            Pair("Soap", "83"),
            Pair("Supernatural", "10"),
            Pair("Thriller", "35"),
            Pair("War & Politics", "67"),
        )
    }
}
