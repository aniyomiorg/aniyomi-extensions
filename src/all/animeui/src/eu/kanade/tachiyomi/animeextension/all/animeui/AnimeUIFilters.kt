package eu.kanade.tachiyomi.animeextension.all.animeui

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeUIFilters {
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

    inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): String {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .joinToString(",")
    }

    class GenresFilter : CheckBoxFilterList(
        "Genres",
        AnimeUIFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    class YearFilter : CheckBoxFilterList(
        "Year",
        AnimeUIFiltersData.YEARS.map { CheckBoxVal(it.first, false) },
    )

    class TypesFilter : CheckBoxFilterList(
        "Types",
        AnimeUIFiltersData.TYPES.map { CheckBoxVal(it.first, false) },
    )

    class StatusFilter : CheckBoxFilterList(
        "Status",
        AnimeUIFiltersData.STATUS.map { CheckBoxVal(it.first, false) },
    )

    class CategoryFilter : QueryPartFilter("Category", AnimeUIFiltersData.CATEGORY)

    val FILTER_LIST get() = AnimeFilterList(
        GenresFilter(),
        YearFilter(),
        TypesFilter(),
        StatusFilter(),
        CategoryFilter(),
    )

    data class FilterSearchParams(
        val genres: String = "",
        val year: String = "",
        val types: String = "",
        val status: String = "",
        val category: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(AnimeUIFiltersData.GENRES),
            filters.parseCheckbox<YearFilter>(AnimeUIFiltersData.YEARS),
            filters.parseCheckbox<TypesFilter>(AnimeUIFiltersData.TYPES),
            filters.parseCheckbox<StatusFilter>(AnimeUIFiltersData.STATUS),
            filters.asQueryPart<CategoryFilter>(),
        )
    }

    private object AnimeUIFiltersData {
        val GENRES = arrayOf(
            Pair("Action", "1"),
            Pair("Adventure", "2"),
            Pair("Comedy", "3"),
            Pair("Cyberpunk", "14"),
            Pair("Demons", "16"),
            Pair("Drama", "4"),
            Pair("Ecchi", "15"),
            Pair("Fantasy", "6"),
            Pair("Harem", "17"),
            Pair("Hentai", "21"),
            Pair("Historical", "20"),
            Pair("Horror", "9"),
            Pair("Isekai", "22"),
            Pair("Josei", "18"),
            Pair("Magic", "7"),
            Pair("Martial Arts", "19"),
            Pair("Mecha", "24"),
            Pair("Military", "23"),
            Pair("Music", "25"),
            Pair("Mystery", "10"),
            Pair("Police", "26"),
            Pair("Post-Apocalyptic", "27"),
            Pair("Psychological", "11"),
            Pair("Romance", "12"),
            Pair("School", "28"),
            Pair("Sci-Fi", "13"),
            Pair("Seinen", "29"),
            Pair("Shoujo", "30"),
            Pair("Shounen", "31"),
            Pair("Slice of Life", "5"),
            Pair("Space", "32"),
            Pair("Sports", "33"),
            Pair("Super Power", "34"),
            Pair("Supernatural", "8"),
            Pair("Thriller", "39"),
            Pair("Tragedy", "35"),
            Pair("Vampire", "36"),
            Pair("Yaoi", "38"),
            Pair("Yuri", "37"),
        )

        val YEARS = arrayOf(
            Pair("1988", "1988"),
            Pair("1997", "1997"),
            Pair("1998", "1998"),
            Pair("2001", "2001"),
            Pair("2004", "2004"),
            Pair("2005", "2005"),
            Pair("2006", "2006"),
            Pair("2007", "2007"),
            Pair("2008", "2008"),
            Pair("2009", "2009"),
            Pair("2010", "2010"),
            Pair("2011", "2011"),
            Pair("2012", "2012"),
            Pair("2013", "2013"),
            Pair("2014", "2014"),
            Pair("2015", "2015"),
            Pair("2016", "2016"),
            Pair("2017", "2017"),
            Pair("2018", "2018"),
            Pair("2019", "2019"),
            Pair("2020", "2020"),
            Pair("2021", "2021"),
            Pair("2022", "2022"),
            Pair("2023", "2023"),
            Pair("2024", "2024"),
        )

        val TYPES = arrayOf(
            Pair("TV", "1"),
            Pair("ONA", "2"),
            Pair("OVA", "3"),
            Pair("BD", "4"),
            Pair("Special", "5"),
            Pair("Movie", "6"),
        )

        val STATUS = arrayOf(
            Pair("Currently Airing", "2"),
            Pair("Finished Airing", "3"),
            Pair("Not Yet Released", "1"),
        )

        val CATEGORY = arrayOf(
            Pair("ALL", ""),
            Pair("#", "0-9"),
            Pair("A", "A"),
            Pair("B", "B"),
            Pair("C", "C"),
            Pair("D", "D"),
            Pair("E", "E"),
            Pair("F", "F"),
            Pair("G", "G"),
            Pair("H", "H"),
            Pair("I", "I"),
            Pair("J", "J"),
            Pair("K", "K"),
            Pair("L", "L"),
            Pair("M", "M"),
            Pair("N", "N"),
            Pair("O", "O"),
            Pair("P", "P"),
            Pair("Q", "Q"),
            Pair("R", "R"),
            Pair("S", "S"),
            Pair("T", "T"),
            Pair("U", "U"),
            Pair("V", "V"),
            Pair("X", "X"),
            Pair("Y", "Y"),
            Pair("Z", "Z"),
        )
    }
}
