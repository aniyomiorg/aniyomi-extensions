package eu.kanade.tachiyomi.animeextension.hi.animesaga

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeSAGAFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, pairs: Array<Pair<String, String>>) :
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
    ): String {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .joinToString(",")
    }

    class TypeFilter : QueryPartFilter("Type", AnimeSAGAFiltersData.TYPE_LIST)

    class GenreFilter : CheckBoxFilterList("Genres", AnimeSAGAFiltersData.GENRE_LIST)

    class YearStart : QueryPartFilter("Release date start", AnimeSAGAFiltersData.YEAR_START)

    class YearEnd : QueryPartFilter("Release date end", AnimeSAGAFiltersData.YEAR_END)

    class SortFilter : QueryPartFilter("Sorting", AnimeSAGAFiltersData.SORTING_LIST)

    class RatingStartFilter : AnimeFilter.Text("Rating lower bound")

    class RatingEndFilter : AnimeFilter.Text("Rating upper bound")

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        TypeFilter(),
        GenreFilter(),
        YearStart(),
        YearEnd(),
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Ratings must be a float between 5.0 and 10.0"),
        RatingStartFilter(),
        RatingEndFilter(),
    )

    data class FilterSearchParams(
        val type: String = "",
        val genre: String = "",
        val yearStart: String = "",
        val yearEnd: String = "",
        val sorting: String = "",
        val ratingStart: String = "",
        val ratingEnd: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.parseCheckbox<GenreFilter>(AnimeSAGAFiltersData.GENRE_LIST),
            filters.asQueryPart<YearStart>(),
            filters.asQueryPart<YearEnd>(),
            filters.asQueryPart<SortFilter>(),
            filters.filterIsInstance<RatingStartFilter>().first().state,
            filters.filterIsInstance<RatingEndFilter>().first().state,
        )
    }

    private object AnimeSAGAFiltersData {
        val TYPE_LIST = arrayOf(
            Pair("TV Shows", "series"),
            Pair("Movies", "movies"),
        )

        // $("div.form-category > label.form-check").map((i,el) => `Pair("${$(el).find("span").first().text().trim()}", "${$(el).find("input").first().attr('value').trim()}")`).get().join(',\n')
        // on /series
        val GENRE_LIST = arrayOf(
            Pair("Action", "1"),
            Pair("Adventure", "2"),
            Pair("Animation", "3"),
            Pair("Comedy", "4"),
            Pair("Crime", "5"),
            Pair("Documentary", "6"),
            Pair("Drama", "7"),
            Pair("Family", "8"),
            Pair("Fantasy", "9"),
            Pair("History", "10"),
            Pair("Horror", "11"),
            Pair("Music", "12"),
            Pair("Mystery", "13"),
            Pair("Romance", "14"),
            Pair("Science Fiction", "15"),
            Pair("TV Movie", "16"),
            Pair("Thriller", "17"),
            Pair("War", "18"),
            Pair("Western", "19"),
        )

        val YEAR_START = (2023 downTo 1960).reversed().map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val YEAR_END = (2023 downTo 1960).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        // $("div.form-category > label.form-check").map((i,el) => `Pair("${$(el).find("span").first().text().trim()}", "${$(el).find("input").first().attr('value').trim()}")`).get().join(',\n')
        // on /series
        val SORTING_LIST = arrayOf(
            Pair("<select>", ""),
            Pair("Newest", "newest"),
            Pair("Most popular", "popular"),
            Pair("Release date", "released"),
            Pair("Imdb rating", "imdb"),
        )
    }
}
