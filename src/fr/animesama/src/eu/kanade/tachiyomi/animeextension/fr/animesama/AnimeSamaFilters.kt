package eu.kanade.tachiyomi.animeextension.fr.animesama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeSamaFilters {

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class TriStateFilterList(name: String, values: List<TriFilter>) : AnimeFilter.Group<AnimeFilter.TriState>(name, values)

    class TriFilter(name: String) : AnimeFilter.TriState(name)

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

    private inline fun <reified R> AnimeFilterList.parseTriFilter(
        options: Array<Pair<String, String>>,
    ): List<List<String>> {
        return (this.getFirst<R>() as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to filter.name }
            .groupBy { it.first }
            .let {
                val included = it.get(AnimeFilter.TriState.STATE_INCLUDE)?.map { options.find { o -> o.first == it.second }!!.second } ?: emptyList()
                val excluded = it.get(AnimeFilter.TriState.STATE_EXCLUDE)?.map { options.find { o -> o.first == it.second }!!.second } ?: emptyList()
                listOf(included, excluded)
            }
    }

    class TypesFilter : CheckBoxFilterList(
        "Type",
        AnimeSamaFiltersData.TYPES.map { CheckBoxVal(it.first, false) },
    )

    class LangFilter : CheckBoxFilterList(
        "Langage",
        AnimeSamaFiltersData.LANGUAGES.map { CheckBoxVal(it.first, false) },
    )

    class GenresFilter : TriStateFilterList(
        "Genre",
        AnimeSamaFiltersData.GENRES.map { TriFilter(it.first) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        TypesFilter(),
        LangFilter(),
        GenresFilter(),
    )

    data class SearchFilters(
        val types: List<String> = emptyList(),
        val language: List<String> = emptyList(),
        val include: List<String> = emptyList(),
        val exclude: List<String> = emptyList(),
    )

    fun getSearchFilters(filters: AnimeFilterList): SearchFilters {
        if (filters.isEmpty()) return SearchFilters()
        val (include, exclude) = filters.parseTriFilter<GenresFilter>(AnimeSamaFiltersData.GENRES)

        return SearchFilters(
            filters.parseCheckbox<TypesFilter>(AnimeSamaFiltersData.TYPES),
            filters.parseCheckbox<LangFilter>(AnimeSamaFiltersData.LANGUAGES),
            include,
            exclude,
        )
    }

    private object AnimeSamaFiltersData {
        val TYPES = arrayOf(
            Pair("Anime", "Anime"),
            Pair("Film", "Film"),
            Pair("Autres", "Autres"),
        )

        val LANGUAGES = arrayOf(
            Pair("VF", "VF"),
            Pair("VOSTFR", "VOSTFR"),
        )

        val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Aventure", "Aventure"),
            Pair("Combats", "Combats"),
            Pair("Comédie", "Comédie"),
            Pair("Drame", "Drame"),
            Pair("Ecchi", "Ecchi"),
            Pair("École", "School-Life"),
            Pair("Fantaisie", "Fantasy"),
            Pair("Horreur", "Horreur"),
            Pair("Isekai", "Isekai"),
            Pair("Josei", "Josei"),
            Pair("Mystère", "Mystère"),
            Pair("Psychologique", "Psychologique"),
            Pair("Quotidien", "Slice-of-Life"),
            Pair("Romance", "Romance"),
            Pair("Seinen", "Seinen"),
            Pair("Shônen", "Shônen"),
            Pair("Shôjo", "Shôjo"),
            Pair("Sports", "Sports"),
            Pair("Surnaturel", "Surnaturel"),
            Pair("Tournois", "Tournois"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
        )
    }
}
