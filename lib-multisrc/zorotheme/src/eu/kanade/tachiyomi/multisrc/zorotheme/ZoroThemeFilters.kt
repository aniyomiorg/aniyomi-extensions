package eu.kanade.tachiyomi.multisrc.zorotheme

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object ZoroThemeFilters {
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

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return this.filterIsInstance<R>().joinToString("") {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class TypeFilter : QueryPartFilter("Type", ZoroThemeFiltersData.TYPES)
    class StatusFilter : QueryPartFilter("Status", ZoroThemeFiltersData.STATUS)
    class RatedFilter : QueryPartFilter("Rated", ZoroThemeFiltersData.RATED)
    class ScoreFilter : QueryPartFilter("Score", ZoroThemeFiltersData.SCORES)
    class SeasonFilter : QueryPartFilter("Season", ZoroThemeFiltersData.SEASONS)
    class LanguageFilter : QueryPartFilter("Language", ZoroThemeFiltersData.LANGUAGES)
    class SortFilter : QueryPartFilter("Sort by", ZoroThemeFiltersData.SORTS)

    class StartYearFilter : QueryPartFilter("Start year", ZoroThemeFiltersData.YEARS)
    class StartMonthFilter : QueryPartFilter("Start month", ZoroThemeFiltersData.MONTHS)
    class StartDayFilter : QueryPartFilter("Start day", ZoroThemeFiltersData.DAYS)

    class EndYearFilter : QueryPartFilter("End year", ZoroThemeFiltersData.YEARS)
    class EndMonthFilter : QueryPartFilter("End month", ZoroThemeFiltersData.MONTHS)
    class EndDayFilter : QueryPartFilter("End day", ZoroThemeFiltersData.DAYS)

    class GenresFilter : CheckBoxFilterList(
        "Genres",
        ZoroThemeFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        TypeFilter(),
        StatusFilter(),
        RatedFilter(),
        ScoreFilter(),
        SeasonFilter(),
        LanguageFilter(),
        SortFilter(),
        AnimeFilter.Separator(),

        StartYearFilter(),
        StartMonthFilter(),
        StartDayFilter(),
        EndYearFilter(),
        EndMonthFilter(),
        EndDayFilter(),
        AnimeFilter.Separator(),

        GenresFilter(),
    )

    data class FilterSearchParams(
        val type: String = "",
        val status: String = "",
        val rated: String = "",
        val score: String = "",
        val season: String = "",
        val language: String = "",
        val sort: String = "",
        val start_year: String = "",
        val start_month: String = "",
        val start_day: String = "",
        val end_year: String = "",
        val end_month: String = "",
        val end_day: String = "",
        val genres: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val genres: String = filters.filterIsInstance<GenresFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    ZoroThemeFiltersData.GENRES.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString(",")

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<RatedFilter>(),
            filters.asQueryPart<ScoreFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.asQueryPart<LanguageFilter>(),
            filters.asQueryPart<SortFilter>(),

            filters.asQueryPart<StartYearFilter>(),
            filters.asQueryPart<StartMonthFilter>(),
            filters.asQueryPart<StartDayFilter>(),
            filters.asQueryPart<EndYearFilter>(),
            filters.asQueryPart<EndMonthFilter>(),
            filters.asQueryPart<EndDayFilter>(),

            genres,
        )
    }

    private object ZoroThemeFiltersData {

        val ALL = Pair("All", "")

        val TYPES = arrayOf(
            ALL,
            Pair("Movie", "1"),
            Pair("TV", "2"),
            Pair("OVA", "3"),
            Pair("ONA", "4"),
            Pair("Special", "5"),
            Pair("Music", "6"),
        )

        val STATUS = arrayOf(
            ALL,
            Pair("Finished Airing", "1"),
            Pair("Currently Airing", "2"),
            Pair("Not yet aired", "3"),
        )

        val RATED = arrayOf(
            ALL,
            Pair("G", "1"),
            Pair("PG", "2"),
            Pair("PG-13", "3"),
            Pair("R", "4"),
            Pair("R+", "5"),
            Pair("Rx", "6"),
        )

        val SCORES = arrayOf(
            ALL,
            Pair("(1) Appalling", "1"),
            Pair("(2) Horrible", "2"),
            Pair("(3) Very Bad", "3"),
            Pair("(4) Bad", "4"),
            Pair("(5) Average", "5"),
            Pair("(6) Fine", "6"),
            Pair("(7) Good", "7"),
            Pair("(8) Very Good", "8"),
            Pair("(9) Great", "9"),
            Pair("(10) Masterpiece", "10"),
        )

        val SEASONS = arrayOf(
            ALL,
            Pair("Spring", "1"),
            Pair("Summer", "2"),
            Pair("Fall", "3"),
            Pair("Winter", "4"),
        )

        val LANGUAGES = arrayOf(
            ALL,
            Pair("SUB", "1"),
            Pair("DUB", "2"),
            Pair("SUB & DUB", "3"),
        )

        val SORTS = arrayOf(
            Pair("Default", "default"),
            Pair("Recently Added", "recently_added"),
            Pair("Recently Updated", "recently_updated"),
            Pair("Score", "score"),
            Pair("Name A-Z", "name_az"),
            Pair("Released Date", "released_date"),
            Pair("Most Watched", "most_watched"),
        )

        val YEARS = arrayOf(ALL) + (1917..2024).map {
            Pair(it.toString(), it.toString())
        }.reversed().toTypedArray()

        val MONTHS = arrayOf(ALL) + (1..12).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val DAYS = arrayOf(ALL) + (1..31).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRES = arrayOf(
            Pair("Action", "1"),
            Pair("Adventure", "2"),
            Pair("Cars", "3"),
            Pair("Comedy", "4"),
            Pair("Dementia", "5"),
            Pair("Demons", "6"),
            Pair("Drama", "8"),
            Pair("Ecchi", "9"),
            Pair("Fantasy", "10"),
            Pair("Game", "11"),
            Pair("Harem", "35"),
            Pair("Historical", "13"),
            Pair("Horror", "14"),
            Pair("Isekai", "44"),
            Pair("Josei", "43"),
            Pair("Kids", "15"),
            Pair("Magic", "16"),
            Pair("Martial Arts", "17"),
            Pair("Mecha", "18"),
            Pair("Military", "38"),
            Pair("Music", "19"),
            Pair("Mystery", "7"),
            Pair("Parody", "20"),
            Pair("Police", "39"),
            Pair("Psychological", "40"),
            Pair("Romance", "22"),
            Pair("Samurai", "21"),
            Pair("School", "23"),
            Pair("Sci-Fi", "24"),
            Pair("Seinen", "42"),
            Pair("Shoujo", "25"),
            Pair("Shoujo Ai", "26"),
            Pair("Shounen", "27"),
            Pair("Shounen Ai", "28"),
            Pair("Slice of Life", "36"),
            Pair("Space", "29"),
            Pair("Sports", "30"),
            Pair("Super Power", "31"),
            Pair("Supernatural", "37"),
            Pair("Thriller", "41"),
            Pair("Vampire", "32"),
            Pair("Yaoi", "33"),
            Pair("Yuri", "34"),
        )
    }
}
