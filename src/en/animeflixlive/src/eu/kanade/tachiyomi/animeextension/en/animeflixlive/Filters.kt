import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class UriPartFilter(
    name: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: String? = null,
) : AnimeFilter.Select<String>(
    name,
    vals.map { it.first }.toTypedArray(),
    vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
) {
    fun getValue(): String {
        return vals[state].second
    }
}

open class UriMultiSelectOption(name: String, val value: String) : AnimeFilter.CheckBox(name)

open class UriMultiSelectFilter(
    name: String,
    private val vals: Array<Pair<String, String>>,
) : AnimeFilter.Group<UriMultiSelectOption>(name, vals.map { UriMultiSelectOption(it.first, it.second) }) {
    fun getValues(): List<String> {
        return state.filter { it.state }.map { it.value }
    }
}

class SortFilter : UriPartFilter(
    "Sort",
    arrayOf(
        Pair("Recently Updated", "recently_updated"),
        Pair("Recently Added", "recently_added"),
        Pair("Release Date ↓", "release_date_down"),
        Pair("Release Date ↑", "release_date_up"),
        Pair("Name A-Z", "title_az"),
        Pair("Best Rating", "scores"),
        Pair("Most Watched", "most_watched"),
        Pair("Anime Length", "number_of_episodes"),
    ),
)

class TypeFilter : UriMultiSelectFilter(
    "Type",
    arrayOf(
        Pair("TV", "TV"),
        Pair("Movie", "MOVIE"),
        Pair("OVA", "OVA"),
        Pair("ONA", "ONA"),
        Pair("Special", "SPECIAL"),
    ),
)

class GenreFilter : UriMultiSelectFilter(
    "Genre",
    arrayOf(
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Comedy", "Comedy"),
        Pair("Drama", "Drama"),
        Pair("Ecchi", "Ecchi"),
        Pair("Fantasy", "Fantasy"),
        Pair("Horror", "Horror"),
        Pair("Mecha", "Mecha"),
        Pair("Mystery", "Mystery"),
        Pair("Psychological", "Psychological"),
        Pair("Romance", "Romance"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Sports", "Sports"),
        Pair("Supernatural", "Supernatural"),
        Pair("Thriller", "Thriller"),
    ),
)

class SubPageFilter : UriPartFilter(
    "Sub-page",
    arrayOf(
        Pair("<select>", ""),
        Pair("Movies", "movies"),
        Pair("Series", "series"),
    ),
)
