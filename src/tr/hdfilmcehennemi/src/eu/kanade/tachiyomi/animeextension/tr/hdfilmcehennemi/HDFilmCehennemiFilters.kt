package eu.kanade.tachiyomi.animeextension.tr.hdfilmcehennemi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object HDFilmCehennemiFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): String {
        return (first { it is R } as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .joinToString(",")
    }

    class TypeFilter : QueryPartFilter("Türü", HDFilmCehennemiFiltersData.TYPES)

    class GenresFilter : CheckBoxFilterList("Türler", HDFilmCehennemiFiltersData.GENRES)
    class YearsFilter : CheckBoxFilterList("Yıllar", HDFilmCehennemiFiltersData.YEARS)
    class IMDBScoreFilter : CheckBoxFilterList("IMDb Puanı", HDFilmCehennemiFiltersData.SCORES)

    class SortFilter : AnimeFilter.Sort(
        "Sıralama Türü",
        HDFilmCehennemiFiltersData.ORDERS.map { it.first }.toTypedArray(),
        Selection(0, false),
    )

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),

        TypeFilter(),
        SortFilter(),
        AnimeFilter.Separator(),

        IMDBScoreFilter(),
        GenresFilter(),
        YearsFilter(),
    )

    data class FilterSearchParams(
        val type: String = "1",
        val order: String = "posts.imdb desc",
        val imdbScore: String = "",
        val genres: String = "",
        val years: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val sortFilter = filters.firstOrNull { it is SortFilter } as? SortFilter
        val orderBy = sortFilter?.state?.run {
            val order = HDFilmCehennemiFiltersData.ORDERS[index].second
            val orderWay = if (ascending) "asc" else "desc"
            "$order $orderWay"
        } ?: "posts.imdb desc"

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            orderBy,
            filters.parseCheckbox<IMDBScoreFilter>(HDFilmCehennemiFiltersData.SCORES),
            filters.parseCheckbox<GenresFilter>(HDFilmCehennemiFiltersData.GENRES),
            filters.parseCheckbox<YearsFilter>(HDFilmCehennemiFiltersData.YEARS),
        )
    }

    private object HDFilmCehennemiFiltersData {
        val TYPES = arrayOf(
            Pair("Filmler", "1"),
            Pair("Diziler", "2"),
        )

        val GENRES = arrayOf(
            Pair("Adult", "40"),
            Pair("Aile", "8"),
            Pair("Aksiyon", "1"),
            Pair("Animasyon", "3"),
            Pair("Belgesel", "6"),
            Pair("Bilim Kurgu", "24"),
            Pair("Biyografi", "26"),
            Pair("Dram", "7"),
            Pair("Fantastik", "9"),
            Pair("Film-Noir", "39"),
            Pair("Game-Show", "34"),
            Pair("Gerilim", "16"),
            Pair("Gizem", "13"),
            Pair("Komedi", "4"),
            Pair("Korku", "11"),
            Pair("Macera", "2"),
            Pair("Müzik", "12"),
            Pair("Müzik", "27"),
            Pair("Polisiye", "32"),
            Pair("Reality", "37"),
            Pair("Reality-TV", "33"),
            Pair("Romantik", "14"),
            Pair("Savaş", "17"),
            Pair("Short", "35"),
            Pair("Spor", "28"),
            Pair("Suç", "5"),
            Pair("Tarih", "10"),
            Pair("Western", "18"),
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
            Pair("2015-2010 arası", "2010-2015"),
            Pair("2010-2000 arası", "2000-2010"),
            Pair("2000 öncesi", "1901-2000"),
        )

        val SCORES = arrayOf(
            Pair("9", "9-10"),
            Pair("8", "8-9"),
            Pair("7", "7-8"),
            Pair("6", "6-7"),
            Pair("5 ve altı", "0-6"),
        )

        val ORDERS = arrayOf(
            Pair("IMDb Puanına", "posts.imdb"),
            Pair("Site Puanı", "avg"),
            Pair("Yıla", "posts.year"),
            Pair("İzlenme", "views"),
        )
    }
}
