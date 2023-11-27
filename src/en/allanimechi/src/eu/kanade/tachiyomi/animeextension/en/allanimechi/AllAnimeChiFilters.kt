package eu.kanade.tachiyomi.animeextension.en.allanimechi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AllAnimeChiFilters {

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
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }
    }

    class OriginFilter : QueryPartFilter("Origin", AllAnimeChiFiltersData.ORIGIN)
    class SeasonFilter : QueryPartFilter("Season", AllAnimeChiFiltersData.SEASONS)
    class ReleaseYearFilter : QueryPartFilter("Released at", AllAnimeChiFiltersData.YEARS)
    class EpisodeCountFilter : QueryPartFilter("Episode Count", AllAnimeChiFiltersData.EPISODE_COUNT)
    class TypeFilter : QueryPartFilter("Type", AllAnimeChiFiltersData.TYPES)

    class GenresFilter : CheckBoxFilterList(
        "Genres",
        AllAnimeChiFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        OriginFilter(),
        SeasonFilter(),
        ReleaseYearFilter(),
        AnimeFilter.Separator(),
        TypeFilter(),
        EpisodeCountFilter(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val origin: String = "",
        val season: String = "",
        val releaseYear: String = "",
        val type: String = "",
        val episodeCount: String = "",
        val genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<OriginFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.asQueryPart<ReleaseYearFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<EpisodeCountFilter>(),
            filters.parseCheckbox<GenresFilter>(AllAnimeChiFiltersData.GENRES),
        )
    }

    private object AllAnimeChiFiltersData {
        val ALL = Pair("All", "all")

        val ORIGIN = arrayOf(
            Pair("All", "ALL"),
            Pair("Japan", "JP"),
            Pair("China", "CN"),
            Pair("Korea", "KR"),
        )

        val SEASONS = arrayOf(
            ALL,
            Pair("Winter", "Winter"),
            Pair("Spring", "Spring"),
            Pair("Summer", "Summer"),
            Pair("Fall", "Fall"),
        )

        val YEARS = arrayOf(
            ALL,
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
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1999", "1999"),
            Pair("1998", "1998"),
            Pair("1997", "1997"),
            Pair("1996", "1996"),
            Pair("1995", "1995"),
            Pair("1994", "1994"),
            Pair("1993", "1993"),
            Pair("1992", "1992"),
            Pair("1991", "1991"),
            Pair("1990", "1990"),
            Pair("1989", "1989"),
            Pair("1988", "1988"),
            Pair("1987", "1987"),
            Pair("1986", "1986"),
            Pair("1985", "1985"),
            Pair("1984", "1984"),
            Pair("1983", "1983"),
            Pair("1982", "1982"),
            Pair("1981", "1981"),
            Pair("1980", "1980"),
            Pair("1979", "1979"),
            Pair("1978", "1978"),
            Pair("1977", "1977"),
            Pair("1976", "1976"),
            Pair("1975", "1975"),
        )

        val TYPES = arrayOf(
            ALL,
            Pair("TV", "TV"),
            Pair("OVA", "OVA"),
            Pair("Movie", "Movie"),
            Pair("Special", "Special"),
            Pair("ONA", "ONA"),
            Pair("Unknown", "Unknown"),
        )

        val GENRES = arrayOf(
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Isekai", "Isekai"),
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Cars", "Cars"),
            Pair("Comedy", "Comedy"),
            Pair("Dementia", "Dementia"),
            Pair("Demons", "Demons"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Game", "Game"),
            Pair("Harem", "Harem"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Magic", "Magic"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Parody", "Parody"),
            Pair("Police", "Police"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Thriller", "Thriller"),
            Pair("Unknown", "Unknown"),
            Pair("Vampire", "Vampire"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
        )

        val EPISODE_COUNT = arrayOf(
            ALL,
            Pair("1-5", "1-5"),
            Pair("6-10", "6-10"),
            Pair("11-15", "11-15"),
            Pair("16-25", "16-25"),
            Pair("26-70", "26-70"),
            Pair("71-255", "71-255"),
            Pair("256+", "256-"),
        )
    }
}
