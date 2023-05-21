package eu.kanade.tachiyomi.animeextension.all.lmanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object LMAnimeFilters {

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
        return this.first { it is R }.let {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class GenreFilter : QueryPartFilter("Genre", LMAnimeFiltersData.GENRES)

    val FILTER_LIST = AnimeFilterList(
        AnimeFilter.Header(LMAnimeFiltersData.IGNORE_SEARCH_MSG),
        GenreFilter(),
    )

    fun getGenre(filters: AnimeFilterList) = filters.asQueryPart<GenreFilter>()

    private object LMAnimeFiltersData {
        const val IGNORE_SEARCH_MSG = "NOTE: Ignored if using text search."

        val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Angel", "angel"),
            Pair("cats", "cats"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Cultivation", "cultivation"),
            Pair("cure", "cure"),
            Pair("Demon", "demon"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("fight", "fight"),
            Pair("god", "god"),
            Pair("growth", "growth"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("inspirational", "inspirational"),
            Pair("isekei", "isekei"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Mystery", "mystery"),
            Pair("Mythology", "mythology"),
            Pair("Original", "original"),
            Pair("Poetry", "poetry"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("school", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Space", "space"),
            Pair("Spirit", "spirit"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Suspense", "suspense"),
            Pair("Thriller", "thriller"),
            Pair("War", "war"),
        )
    }
}
