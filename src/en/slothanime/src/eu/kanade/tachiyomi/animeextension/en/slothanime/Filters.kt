package eu.kanade.tachiyomi.animeextension.en.slothanime

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

open class UriMultiTriSelectOption(name: String, val value: String) : AnimeFilter.TriState(name)

open class UriMultiTriSelectFilter(
    name: String,
    private val vals: Array<Pair<String, String>>,
) : AnimeFilter.Group<UriMultiTriSelectOption>(name, vals.map { UriMultiTriSelectOption(it.first, it.second) }) {
    fun getIncluded(): List<String> {
        return state.filter { it.state == TriState.STATE_INCLUDE }.map { it.value }
    }

    fun getExcluded(): List<String> {
        return state.filter { it.state == TriState.STATE_EXCLUDE }.map { it.value }
    }
}

class GenreFilter : UriMultiTriSelectFilter(
    "Genre",
    arrayOf(
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Fantasy", "fantasy"),
        Pair("Martial Arts", "martial_arts"),
        Pair("Comedy", "comedy"),
        Pair("School", "school"),
        Pair("Slice of Life", "slice_of_life"),
        Pair("Military", "military"),
        Pair("Sci-Fi", "scifi"),
        Pair("Isekai", "isekai"),
        Pair("Kids", "kids"),
        Pair("Iyashikei", "iyashikei"),
        Pair("Horror", "horror"),
        Pair("Supernatural", "supernatural"),
        Pair("Avant Garde", "avant_garde"),
        Pair("Demons", "demons"),
        Pair("Gourmet", "gourmet"),
        Pair("Music", "music"),
        Pair("Drama", "drama"),
        Pair("Seinen", "seinen"),
        Pair("Ecchi", "ecchi"),
        Pair("Harem", "harem"),
        Pair("Romance", "romance"),
        Pair("Magic", "magic"),
        Pair("Mystery", "mystery"),
        Pair("Suspense", "suspense"),
        Pair("Parody", "parody"),
        Pair("Psychological", "psychological"),
        Pair("Super Power", "super_power"),
        Pair("Vampire", "vampire"),
        Pair("Shounen", "shounen"),
        Pair("Space", "space"),
        Pair("Mecha", "mecha"),
        Pair("Sports", "sports"),
        Pair("Shoujo", "shoujo"),
        Pair("Girls Love", "girls_love"),
        Pair("Josei", "josei"),
        Pair("Mahou Shoujo", "mahou_shoujo"),
        Pair("Thriller", "thriller"),
        Pair("Reverse Harem", "reverse_harem"),
        Pair("Boys Love", "boys_love"),
        Pair("Uncategorized", "uncategorized"),
    ),
)

class TypeFilter : UriMultiSelectFilter(
    "Type",
    arrayOf(
        Pair("ONA", "ona"),
        Pair("TV", "tv"),
        Pair("MOVIE", "movie"),
        Pair("SPECIAL", "special"),
        Pair("OVA", "ova"),
        Pair("MUSIC", "music"),
    ),
)

class StatusFilter : UriPartFilter(
    "Status",
    arrayOf(
        Pair("All", "2"),
        Pair("Completed", "1"),
        Pair("Releasing", "0"),
    ),
)

class SortFilter : UriPartFilter(
    "Sort",
    arrayOf(
        Pair("Most Watched", "viewed"),
        Pair("Scored", "scored"),
        Pair("Newest", "created_at"),
        Pair("Latest Update", "updated_at"),
    ),
)
