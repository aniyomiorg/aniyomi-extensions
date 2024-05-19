package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class AnimeWorldIndiaFilters {

    private data class StringQuery(val name: String, val query: String)

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("Type", types)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList() = listOf(
        StringQuery("Any", "all"),
        StringQuery("TV", "tv"),
        StringQuery("Movie", "movie"),
    )

    private class StatusList(statuses: Array<String>) : AnimeFilter.Select<String>("Status", statuses)
    private val statusesName = getStatusesList().map {
        it.name
    }.toTypedArray()

    private fun getStatusesList() = listOf(
        StringQuery("Any", "all"),
        StringQuery("Currently Airing", "airing"),
        StringQuery("Finished Airing", "completed"),
    )

    private class StyleList(styles: Array<String>) : AnimeFilter.Select<String>("Style", styles)
    private val stylesName = getStyleList().map {
        it.name
    }.toTypedArray()

    private fun getStyleList() = listOf(
        StringQuery("Any", "all"),
        StringQuery("Anime", "anime"),
        StringQuery("Cartoon", "cartoon"),
    )

    private class YearList(years: Array<String>) : AnimeFilter.Select<String>("Year", years)
    private val yearsName = getYearList().map {
        it.name
    }.toTypedArray()

    private fun getYearList() = listOf(
        StringQuery("Any", "all"),
        StringQuery("2024", "2024"),
        StringQuery("2023", "2023"),
        StringQuery("2022", "2022"),
        StringQuery("2021", "2021"),
        StringQuery("2020", "2020"),
        StringQuery("2019", "2019"),
        StringQuery("2018", "2018"),
        StringQuery("2017", "2017"),
        StringQuery("2016", "2016"),
        StringQuery("2015", "2015"),
        StringQuery("2014", "2014"),
        StringQuery("2013", "2013"),
        StringQuery("2012", "2012"),
        StringQuery("2011", "2011"),
        StringQuery("2010", "2010"),
        StringQuery("2009", "2009"),
        StringQuery("2008", "2008"),
        StringQuery("2007", "2007"),
        StringQuery("2006", "2006"),
        StringQuery("2005", "2005"),
        StringQuery("2004", "2004"),
        StringQuery("2003", "2003"),
        StringQuery("2002", "2002"),
        StringQuery("2001", "2001"),
        StringQuery("2000", "2000"),
        StringQuery("1999", "1999"),
        StringQuery("1998", "1998"),
        StringQuery("1997", "1997"),
        StringQuery("1996", "1996"),
        StringQuery("1995", "1995"),
        StringQuery("1994", "1994"),
        StringQuery("1993", "1993"),
        StringQuery("1992", "1992"),
        StringQuery("1991", "1991"),
        StringQuery("1990", "1990"),
    )

    private class SortList(sorts: Array<String>) : AnimeFilter.Select<String>("Sort", sorts)
    private val sortsName = getSortList().map {
        it.name
    }.toTypedArray()

    private fun getSortList() = listOf(
        StringQuery("Default", "default"),
        StringQuery("Ascending", "title_a_z"),
        StringQuery("Descending", "title_z_a"),
        StringQuery("Updated", "update"),
        StringQuery("Published", "date"),
        StringQuery("Most Viewed", "viewed"),
        StringQuery("Favourite", "favorite"),
    )

    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genres", genres)
    private fun genresName() = listOf(
        Genre("Action"),
        Genre("Adult Cast"),
        Genre("Adventure"),
        Genre("Animation"),
        Genre("Comedy"),
        Genre("Detective"),
        Genre("Drama"),
        Genre("Ecchi"),
        Genre("Family"),
        Genre("Fantasy"),
        Genre("Isekai"),
        Genre("Kids"),
        Genre("Martial Arts"),
        Genre("Mecha"),
        Genre("Military"),
        Genre("Mystery"),
        Genre("Otaku Culture"),
        Genre("Reality"),
        Genre("Romance"),
        Genre("School"),
        Genre("Sci-Fi"),
        Genre("Seinen"),
        Genre("Shounen"),
        Genre("Slice of Life"),
        Genre("Sports"),
        Genre("Super Power"),
        Genre("SuperHero"),
        Genre("Supernatural"),
        Genre("TV Movie"),
    )

    val filters: AnimeFilterList get() = AnimeFilterList(
        TypeList(typesName),
        StatusList(statusesName),
        StyleList(stylesName),
        YearList(yearsName),
        SortList(sortsName),
        GenreList(genresName()),
    )

    fun getSearchParams(filters: AnimeFilterList): String {
        return "&" + filters.mapNotNull { filter ->
            when (filter) {
                is TypeList -> {
                    val type = getTypeList()[filter.state].query
                    "s_type=$type"
                }
                is StatusList -> {
                    val status = getStatusesList()[filter.state].query
                    "s_status=$status"
                }
                is StyleList -> {
                    val style = getStyleList()[filter.state].query
                    "s_sub_type=$style"
                }
                is YearList -> {
                    val year = getYearList()[filter.state].query
                    "s_year=$year"
                }
                is SortList -> {
                    val sort = getSortList()[filter.state].query
                    "s_orderby=$sort"
                }
                is GenreList -> {
                    "s_genre=" + filter.state.filter { it.state }
                        .joinToString("%2C") {
                            val genre = it.id.replace(" ", "-")
                            "$genre%2C"
                        }
                }
                else -> null
            }
        }.joinToString("&")
    }
}
