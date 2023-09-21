package eu.kanade.tachiyomi.animeextension.all.netflixmirror

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

abstract class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : AnimeFilter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
) {
    val selected get() = options[state].second.takeUnless { it.isEmpty() }
}

class PageFilter : SelectFilter(
    "Page",
    listOf(
        Pair("Home", ""),
        Pair("Movies", "/movies"),
        Pair("Series", "/series"),
    ),
)

fun getFilters() = AnimeFilterList(
    PageFilter(),
    AnimeFilter.Separator("Doesn't work with text search."),
)
