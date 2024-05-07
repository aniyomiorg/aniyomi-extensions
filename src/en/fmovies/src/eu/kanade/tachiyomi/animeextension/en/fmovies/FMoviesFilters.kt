package eu.kanade.tachiyomi.animeextension.en.fmovies

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object FMoviesFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = "&$name=${vals[state].second}"
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class TriStateFilterList(name: String, values: List<TriFilter>) : AnimeFilter.Group<AnimeFilter.TriState>(name, values)

    class TriFilter(name: String, val value: String) : AnimeFilter.TriState(name)

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString("&$name[]=").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&$name[]=$it"
                }
            }
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (this.getFirst<R>() as TriStateFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state != AnimeFilter.TriState.STATE_IGNORE) {
                    (if (checkbox.state == AnimeFilter.TriState.STATE_EXCLUDE) "-" else "") + options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString("&$name[]=").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&$name[]=$it"
                }
            }
    }

    class TypesFilter : CheckBoxFilterList(
        "Type",
        FMoviesFiltersData.TYPES.map { CheckBoxVal(it.first, false) },
    )

    class GenresFilter : TriStateFilterList(
        "Genre",
        FMoviesFiltersData.GENRES.map { TriFilter(it.first, it.second) },
    )

    class CountriesFilter : CheckBoxFilterList(
        "Country",
        FMoviesFiltersData.COUNTRIES.map { CheckBoxVal(it.first, false) },
    )

    class YearsFilter : CheckBoxFilterList(
        "Year",
        FMoviesFiltersData.YEARS.map { CheckBoxVal(it.first, false) },
    )

    class RatingsFilter : CheckBoxFilterList(
        "Rating",
        FMoviesFiltersData.RATINGS.map { CheckBoxVal(it.first, false) },
    )

    class QualitiesFilter : CheckBoxFilterList(
        "Quality",
        FMoviesFiltersData.QUALITIES.map { CheckBoxVal(it.first, false) },
    )

    class SortFilter : QueryPartFilter("Sort", FMoviesFiltersData.SORT)

    val FILTER_LIST get() = AnimeFilterList(
        TypesFilter(),
        GenresFilter(),
        CountriesFilter(),
        YearsFilter(),
        RatingsFilter(),
        QualitiesFilter(),
        SortFilter(),
    )

    data class FilterSearchParams(
        val filter: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<TypesFilter>(FMoviesFiltersData.TYPES, "type") +
                filters.parseTriFilter<GenresFilter>(FMoviesFiltersData.GENRES, "genre") +
                filters.parseCheckbox<CountriesFilter>(FMoviesFiltersData.COUNTRIES, "country") +
                filters.parseCheckbox<YearsFilter>(FMoviesFiltersData.YEARS, "year") +
                filters.parseCheckbox<RatingsFilter>(FMoviesFiltersData.RATINGS, "rating") +
                filters.parseCheckbox<QualitiesFilter>(FMoviesFiltersData.QUALITIES, "quality") +
                filters.asQueryPart<SortFilter>("sort"),
        )
    }

    private object FMoviesFiltersData {
        val TYPES = arrayOf(
            Pair("Movie", "movie"),
            Pair("TV-Shows", "tv"),
        )

        val GENRES = arrayOf(
            Pair("Action", "25"),
            Pair("Adult", "1068691"),
            Pair("Adventure", "17"),
            Pair("Animation", "10"),
            Pair("Biography", "215"),
            Pair("Comedy", "14"),
            Pair("Costume", "1693"),
            Pair("Crime", "26"),
            Pair("Documentary", "131"),
            Pair("Drama", "1"),
            Pair("Family", "43"),
            Pair("Fantasy", "31"),
            Pair("Film-Noir", "1068395"),
            Pair("Game-Show", "212"),
            Pair("History", "47"),
            Pair("Horror", "74"),
            Pair("Kungfu", "248"),
            Pair("Music", "199"),
            Pair("Musical", "1066604"),
            Pair("Mystery", "64"),
            Pair("News", "1066549"),
            Pair("Reality", "1123750"),
            Pair("Reality-TV", "4"),
            Pair("Romance", "23"),
            Pair("Sci-Fi", "15"),
            Pair("Short", "1066916"),
            Pair("Sport", "44"),
            Pair("Talk", "1124002"),
            Pair("Talk-Show", "1067786"),
            Pair("Thriller", "7"),
            Pair("TV Movie", "1123752"),
            Pair("TV Show", "139"),
            Pair("War", "58"),
            Pair("Western", "28"),
        )

        val COUNTRIES = arrayOf(
            Pair("Argentina", "181863"),
            Pair("Australia", "181851"),
            Pair("Austria", "181882"),
            Pair("Belgium", "181849"),
            Pair("Brazil", "181867"),
            Pair("Canada", "181861"),
            Pair("China", "108"),
            Pair("Czech Republic", "181859"),
            Pair("Denmark", "181855"),
            Pair("Finland", "181877"),
            Pair("France", "11"),
            Pair("Germany", "1025332"),
            Pair("Hong Kong", "2630"),
            Pair("Hungary", "181876"),
            Pair("India", "34"),
            Pair("Ireland", "181862"),
            Pair("Israel", "181887"),
            Pair("Italy", "181857"),
            Pair("Japan", "36"),
            Pair("Luxembourg", "181878"),
            Pair("Mexico", "181852"),
            Pair("Netherlands", "181848"),
            Pair("New Zealand", "181847"),
            Pair("Norway", "181901"),
            Pair("Philippines", "1025339"),
            Pair("Poland", "181880"),
            Pair("Romania", "181895"),
            Pair("Russia", "181860"),
            Pair("South Africa", "181850"),
            Pair("South Korea", "1025429"),
            Pair("Spain", "181871"),
            Pair("Sweden", "181883"),
            Pair("Switzerland", "181869"),
            Pair("Thailand", "94"),
            Pair("Turkey", "1025379"),
            Pair("United Kingdom", "8"),
            Pair("United States", "2"),
        )

        val YEARS = arrayOf(
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2000s", "2000s"),
            Pair("1990s", "1990s"),
            Pair("1980s", "1980s"),
            Pair("1970s", "1970s"),
            Pair("1960s", "1960s"),
            Pair("1950s", "1950s"),
            Pair("1940s", "1940s"),
            Pair("1930s", "1930s"),
            Pair("1920s", "1920s"),
            Pair("1910s", "1910s"),
        )

        val RATINGS = arrayOf(
            Pair("12", "12"),
            Pair("13+", "13+"),
            Pair("16+", "16+"),
            Pair("18", "18"),
            Pair("18+", "18+"),
            Pair("AO", "AO"),
            Pair("C", "C"),
            Pair("E", "E"),
            Pair("G", "G"),
            Pair("GP", "GP"),
            Pair("M", "M"),
            Pair("M/PG", "M/PG"),
            Pair("MA-13", "MA-13"),
            Pair("MA-17", "MA-17"),
            Pair("NC-17", "NC-17"),
            Pair("PG", "PG"),
            Pair("PG-13", "PG-13"),
            Pair("R", "R"),
            Pair("TV_MA", "TV_MA"),
            Pair("TV-13", "TV-13"),
            Pair("TV-14", "TV-14"),
            Pair("TV-G", "TV-G"),
            Pair("TV-MA", "TV-MA"),
            Pair("TV-PG", "TV-PG"),
            Pair("TV-Y", "TV-Y"),
            Pair("TV-Y7", "TV-Y7"),
            Pair("TV-Y7-FV", "TV-Y7-FV"),
            Pair("X", "X"),
        )

        val QUALITIES = arrayOf(
            Pair("HD", "HD"),
            Pair("HDRip", "HDRip"),
            Pair("SD", "SD"),
            Pair("TS", "TS"),
            Pair("CAM", "CAM"),
        )

        val SORT = arrayOf(
            Pair("Most relevance", "most_relevance"),
            Pair("Recently updated", "recently_updated"),
            Pair("Recently added", "recently_added"),
            Pair("Release date", "release_date"),
            Pair("Trending", "trending"),
            Pair("Name A-Z", "title_az"),
            Pair("Scores", "scores"),
            Pair("IMDb", "imdb"),
            Pair("Most watched", "most_watched"),
            Pair("Most favourited", "most_favourited"),
        )
    }
}
