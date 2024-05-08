package eu.kanade.tachiyomi.animeextension.fr.voircartoon

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object VoirCartoonFilters {
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

    internal class TypeFilter : QueryPartFilter("Type", VoirCartoonFiltersData.TYPES)
    internal class GenreFilter : QueryPartFilter("Genre", VoirCartoonFiltersData.GENRES)
    internal class YearFilter : QueryPartFilter("Year", VoirCartoonFiltersData.YEARS)
    internal class StatusFilter : QueryPartFilter("Status", VoirCartoonFiltersData.STATUS)
    internal class AgeFilter : QueryPartFilter("Age", VoirCartoonFiltersData.AGES)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filters are going to be ignored if using search text!"),
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        AgeFilter(),
    )

    data class FilterSearchParams(
        val type: String = "",
        val genre: String = "",
        val year: String = "",
        val status: String = "",
        val age: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<GenreFilter>(),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<AgeFilter>(),
        )
    }

    private object VoirCartoonFiltersData {
        private val ANY = Pair("Select", "")

        val TYPES = arrayOf(
            ANY,
            Pair("Tv shows", "tvshows"),
            Pair("Movies", "movies"),
        )

        val GENRES = arrayOf(
            ANY,
            Pair("Action & Adventure", "action-adventure"),
            Pair("Action", "action"),
            Pair("Animation", "animation"),
            Pair("Aventure", "aventure"),
            Pair("Comédie", "comedie"),
            Pair("Crime", "crime"),
            Pair("Documentaire", "documentaire"),
            Pair("Drame", "drame"),
            Pair("Familial", "familial"),
            Pair("Fantastique", "fantastique"),
            Pair("Guerre", "guerre"),
            Pair("Histoire", "histoire"),
            Pair("Horreur", "horreur"),
            Pair("Kids", "kids"),
            Pair("Musique", "musique"),
            Pair("Mystère", "mystere"),
            Pair("Romance", "romance"),
            Pair("Science-Fiction & Fantastique", "science-fiction-fantastique"),
            Pair("Science-Fiction", "science-fiction"),
            Pair("Sport", "sport"),
            Pair("Thriller", "thriller"),
            Pair("Téléfilm", "telefilm"),
            Pair("War & Politics", "war-politics"),
            Pair("Western", "western"),
        )

        val YEARS = arrayOf(
            ANY,
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
            Pair("1974", "1974"),
            Pair("1973", "1973"),
            Pair("1972", "1972"),
            Pair("1971", "1971"),
            Pair("1970", "1970"),
            Pair("1969", "1969"),
            Pair("1968", "1968"),
            Pair("1967", "1967"),
            Pair("1965", "1965"),
            Pair("1964", "1964"),
            Pair("1963", "1963"),
            Pair("1962", "1962"),
            Pair("1961", "1961"),
            Pair("1960", "1960"),
            Pair("1959", "1959"),
            Pair("1958", "1958"),
            Pair("1957", "1957"),
            Pair("1956", "1956"),
            Pair("1955", "1955"),
            Pair("1953", "1953"),
            Pair("1951", "1951"),
            Pair("1950", "1950"),
            Pair("1949", "1949"),
            Pair("1948", "1948"),
            Pair("1947", "1947"),
            Pair("1946", "1946"),
            Pair("1944", "1944"),
            Pair("1942", "1942"),
            Pair("1941", "1941"),
            Pair("1940", "1940"),
            Pair("1939", "1939"),
            Pair("1937", "1937"),
            Pair("1930", "1930"),
        )

        val STATUS = arrayOf(
            ANY,
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
        )

        val AGES = arrayOf(
            ANY,
            Pair("14 (14+)", "14"),
            Pair("G (Tous ages)", "g"),
            Pair("MA (18+)", "ma"),
            Pair("PG (10+)", "pg"),
            Pair("PG-13 (13+)", "pg-13"),
            Pair("U", "u"),
            Pair("Y (2 à 6)", "y"),
            Pair("Y7 (7+)", "y7"),
            Pair("Y7-FV (9+)", "y7-fv"),
        )
    }
}
