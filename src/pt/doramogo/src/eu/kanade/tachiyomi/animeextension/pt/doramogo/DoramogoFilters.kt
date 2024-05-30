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

    data class FilterSearchParams(
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
            Pair("ASMR", "65"),
        )
    }
}
