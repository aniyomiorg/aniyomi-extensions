package eu.kanade.tachiyomi.animeextension.all.hikari

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import okhttp3.HttpUrl
import java.util.Calendar

interface UriFilter {
    fun addToUri(url: HttpUrl.Builder)
}

sealed class UriPartFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: String? = null,
) : AnimeFilter.Select<String>(
    name,
    vals.map { it.first }.toTypedArray(),
    vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        builder.addQueryParameter(param, vals[state].second)
    }
}

class UriMultiSelectOption(name: String, val value: String) : AnimeFilter.CheckBox(name)

sealed class UriMultiSelectFilter(
    name: String,
    private val param: String,
    private val vals: Array<Pair<String, String>>,
) : AnimeFilter.Group<UriMultiSelectOption>(name, vals.map { UriMultiSelectOption(it.first, it.second) }), UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val checked = state.filter { it.state }
        builder.addQueryParameter(param, checked.joinToString(",") { it.value })
    }
}

class TypeFilter : UriPartFilter(
    "Type",
    "type",
    arrayOf(
        Pair("All", ""),
        Pair("TV", "1"),
        Pair("Movie", "2"),
        Pair("OVA", "3"),
        Pair("ONA", "4"),
        Pair("Special", "5"),
    ),
)

class CountryFilter : UriPartFilter(
    "Country",
    "country",
    arrayOf(
        Pair("All", ""),
        Pair("Japanese", "1"),
        Pair("Chinese", "2"),
    ),
)

class StatusFilter : UriPartFilter(
    "Status",
    "stats",
    arrayOf(
        Pair("All", ""),
        Pair("Currently Airing", "1"),
        Pair("Finished Airing", "2"),
        Pair("Not yet Aired", "3"),
    ),
)

class RatingFilter : UriPartFilter(
    "Rating",
    "rate",
    arrayOf(
        Pair("All", ""),
        Pair("G", "1"),
        Pair("PG", "2"),
        Pair("PG-13", "3"),
        Pair("R-17+", "4"),
        Pair("R+", "5"),
        Pair("Rx", "6"),
    ),
)

class SourceFilter : UriPartFilter(
    "Source",
    "source",
    arrayOf(
        Pair("All", ""),
        Pair("LightNovel", "1"),
        Pair("Manga", "2"),
        Pair("Original", "3"),
    ),
)

class SeasonFilter : UriPartFilter(
    "Season",
    "season",
    arrayOf(
        Pair("All", ""),
        Pair("Spring", "1"),
        Pair("Summer", "2"),
        Pair("Fall", "3"),
        Pair("Winter", "4"),
    ),
)

class LanguageFilter : UriPartFilter(
    "Language",
    "language",
    arrayOf(
        Pair("All", ""),
        Pair("Raw", "1"),
        Pair("Sub", "2"),
        Pair("Dub", "3"),
        Pair("Turk", "4"),
    ),
)

class SortFilter : UriPartFilter(
    "Sort",
    "sort",
    arrayOf(
        Pair("Default", "default"),
        Pair("Recently Added", "recently_added"),
        Pair("Recently Updated", "recently_updated"),
        Pair("Score", "score"),
        Pair("Name A-Z", "name_az"),
        Pair("Released Date", "released_date"),
        Pair("Most Watched", "most_watched"),
    ),
)

class YearFilter(name: String, param: String) : UriPartFilter(
    name,
    param,
    YEARS,
) {
    companion object {
        private val NEXT_YEAR by lazy {
            Calendar.getInstance()[Calendar.YEAR] + 1
        }

        private val YEARS = Array(NEXT_YEAR - 1917) { year ->
            if (year == 0) {
                Pair("Any", "")
            } else {
                (NEXT_YEAR - year).toString().let { Pair(it, it) }
            }
        }
    }
}

class MonthFilter(name: String, param: String) : UriPartFilter(
    name,
    param,
    MONTHS,
) {
    companion object {
        private val MONTHS = Array(13) { months ->
            if (months == 0) {
                Pair("Any", "")
            } else {
                val monthStr = "%02d".format(months)
                Pair(monthStr, monthStr)
            }
        }
    }
}

class DayFilter(name: String, param: String) : UriPartFilter(
    name,
    param,
    DAYS,
) {
    companion object {
        private val DAYS = Array(32) { day ->
            if (day == 0) {
                Pair("Any", "")
            } else {
                val dayStr = "%02d".format(day)
                Pair(dayStr, dayStr)
            }
        }
    }
}

class AiringDateFilter(
    private val values: List<UriPartFilter> = PARTS,
) : AnimeFilter.Group<UriPartFilter>("Airing Date", values), UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        values.forEach {
            it.addToUri(builder)
        }
    }

    companion object {
        private val PARTS = listOf(
            YearFilter("Year", "aired_year"),
            MonthFilter("Month", "aired_month"),
            DayFilter("Day", "aired_day"),
        )
    }
}

class GenreFilter : UriMultiSelectFilter(
    "Genre",
    "genres",
    arrayOf(
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
        Pair("Isekai", "Isekai"),
        Pair("Josei", "Josei"),
        Pair("Kids", "Kids"),
        Pair("Magic", "Magic"),
        Pair("Martial Arts", "Martial Arts"),
        Pair("Mecha", "Mecha"),
        Pair("Military", "Military"),
        Pair("Music", "Music"),
        Pair("Mystery", "Mystery"),
        Pair("Parody", "Parody"),
        Pair("Police", "Police"),
        Pair("Psychological", "Psychological"),
        Pair("Romance", "Romance"),
        Pair("Samurai", "Samurai"),
        Pair("School", "School"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Seinen", "Seinen"),
        Pair("Shoujo", "Shoujo"),
        Pair("Shoujo Ai", "Shoujo Ai"),
        Pair("Shounen", "Shounen"),
        Pair("Shounen Ai", "Shounen Ai"),
        Pair("Slice of Life", "Slice of Life"),
        Pair("Space", "Space"),
        Pair("Sports", "Sports"),
        Pair("Super Power", "Super Power"),
        Pair("Supernatural", "Supernatural"),
        Pair("Thriller", "Thriller"),
        Pair("Vampire", "Vampire"),
    ),
)
