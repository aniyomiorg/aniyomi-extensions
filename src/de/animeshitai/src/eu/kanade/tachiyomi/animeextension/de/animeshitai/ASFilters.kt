package eu.kanade.tachiyomi.animeextension.de.animeshitai

import eu.kanade.tachiyomi.animeextension.de.animeshitai.model.ASAnime
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object ASFilters {
    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class TriStateFilterList(name: String, values: List<TriState>) : AnimeFilter.Group<AnimeFilter.TriState>(name, values)
    private class TriStateVal(name: String) : AnimeFilter.TriState(name)

    class SortFilter : AnimeFilter.Sort(
        "Sortieren",
        ASFilterData.sortables.map { it.first }.toTypedArray(),
        Selection(0, true)
    )

    class FormatFilter : CheckBoxFilterList(
        "Format",
        ASFilterData.formats.map { CheckBoxVal(it.first, true) }
    )

    class LanguageFilter : CheckBoxFilterList(
        "Sprache",
        ASFilterData.languages.map { CheckBoxVal(it.first, true) }
    )

    class GenreFilter : TriStateFilterList(
        "Genres",
        ASFilterData.genres.map { TriStateVal(it) }
    )

    class YearsFilter : CheckBoxFilterList(
        "Jahre",
        ASFilterData.years.map { CheckBoxVal(it.toString()) }
    )

    class ABCFilter : CheckBoxFilterList(
        "ABC",
        ASFilterData.abc.map { CheckBoxVal(it.toString()) }
    )

    val filterList = AnimeFilterList(
        SortFilter(),
        FormatFilter(),
        LanguageFilter(),
        GenreFilter(),
        YearsFilter(),
        ABCFilter(),
    )

    data class FilterSearchParams(
        var orderBy: String = "az",
        var orderAscending: Boolean = true,
        val includedFormats: ArrayList<String> = ArrayList(),
        val includedLangs: ArrayList<String> = ArrayList(),
        val includedGenres: ArrayList<String> = ArrayList(),
        val blackListedGenres: ArrayList<String> = ArrayList(),
        val includedYears: ArrayList<String> = ArrayList(),
        val includedLetters: ArrayList<String> = ArrayList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        val params = FilterSearchParams()
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.state != null) {
                        val query = ASFilterData.sortables[filter.state!!.index].second
                        params.orderAscending = filter.state!!.ascending
                        params.orderBy = query
                    }
                }
                is FormatFilter -> {
                    filter.state.forEach { format ->
                        if (format.state) {
                            val query = ASFilterData.formats.find { it.first == format.name }!!.second
                            params.includedFormats.add(query)
                        }
                    }
                }
                is LanguageFilter -> {
                    filter.state.forEach { lang ->
                        if (lang.state) {
                            val query = ASFilterData.languages.find { it.first == lang.name }!!.second
                            params.includedLangs.add(query)
                        }
                    }
                }
                is GenreFilter -> {
                    filter.state.forEach { genre ->
                        if (genre.isIncluded()) {
                            params.includedGenres.add(genre.name)
                        } else if (genre.isExcluded()) {
                            params.blackListedGenres.add(genre.name)
                        }
                    }
                }
                is YearsFilter -> {
                    filter.state.forEach { year ->
                        if (year.state)
                            params.includedYears.add(year.name)
                    }
                }
                is ABCFilter -> {
                    filter.state.forEach { letter ->
                        if (letter.state)
                            params.includedLetters.add(letter.name)
                    }
                }
                else -> {}
            }
        }
        return params
    }

    fun MutableList<ASAnime>.applyFilterParams(params: FilterSearchParams) {
        // Remove all entries with blacklisted genres
        this.removeAll { anime -> params.blackListedGenres.any { it in anime.genre ?: "" } }

        // Sort animes
        when (params.orderBy) {
            // Sort animes alphabetically
            "az" -> {
                if (!params.orderAscending)
                    this.reverse()
            }
            // Sort animes by year
            "year" -> {
                if (params.orderAscending)
                    this.sortBy { it.year }
                else
                    this.sortByDescending { it.year }
            }
        }
    }
}

private object ASFilterData {
    //          (1990..(Year.now().value)).reversed()
    val years = (1990..(Calendar.getInstance().get(Calendar.YEAR))).reversed()

    val abc = 'A'..'Z'

    val sortables = arrayOf(
        Pair("Alphabetisch", "az"),
        Pair("Jahr", "year"),
    )

    val formats = arrayOf(
        Pair("\uD83D\uDCFA Serien", "Serien"),
        Pair("\uD83C\uDF10 OVAs", "OVAs"),
        Pair("\uD83C\uDFAC Filme", "Filme")
    )

    val languages = arrayOf(
        Pair("\uD83C\uDDE9\uD83C\uDDEA Ger Dub", "Ger Dub"),
        Pair("\uD83C\uDDEF\uD83C\uDDF5 Ger Sub", "Ger Sub"),
        Pair("\uD83C\uDDEC\uD83C\uDDE7 Eng Dub & Ger Sub", "Eng Dub")
    )

    val genres = listOf(
        "Abenteuer",
        "Action",
        "Alltagsleben",
        "Alternative Welt",
        "Drama",
        "Ecchi",
        "Erotik",
        "Fantasy",
        "Fighting-Shounen",
        "Ganbatte",
        "Geistergeschichten",
        "Harem",
        "Historisch",
        "Horror",
        "Komödie",
        "Krimi",
        "Liebesdrama",
        "Magical Girl",
        "Magie",
        "Mecha",
        "Mystery",
        "Nonsense-Komödie",
        "Psychodrama",
        "Romantische Komödie",
        "Romanze",
        "Schule",
        "Sci-Fi",
        "Sentimentales Drama",
        "Shoujou",
        "Shounen",
        "Sport",
        "Superpower",
        "Thriller",
        "Übermäßige Gewaltdarstellung",
        "Unbestimmt",
        "Yaoi",
        "Yuri"
    )
}
