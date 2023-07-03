package eu.kanade.tachiyomi.animeextension.en.kissanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KissAnimeFilters {
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

    class StatusFilter : QueryPartFilter("Status", KissAnimeFiltersData.STATUS)

    class GenreFilter : CheckBoxFilterList(
        "Genre",
        KissAnimeFiltersData.GENRE.map { CheckBoxVal(it.first, false) },
    )

    class SubPageFilter : QueryPartFilter("Sub-page", KissAnimeFiltersData.SUBPAGE)

    class ScheduleFilter : QueryPartFilter("Schedule", KissAnimeFiltersData.SCHEDULE)

    val FILTER_LIST get() = AnimeFilterList(
        StatusFilter(),
        GenreFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Ignores other filters & text search"),
        SubPageFilter(),
        ScheduleFilter(),
    )

    data class FilterSearchParams(
        val status: String = "",
        val genre: String = "",
        val subpage: String = "",
        val schedule: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val genre: String = filters.filterIsInstance<GenreFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    KissAnimeFiltersData.GENRE.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString(separator = "")

        return FilterSearchParams(
            filters.asQueryPart<StatusFilter>(),
            genre,
            filters.asQueryPart<SubPageFilter>(),
            filters.asQueryPart<ScheduleFilter>(),
        )
    }

    private object KissAnimeFiltersData {
        val STATUS = arrayOf(
            Pair("Any", ""),
            Pair("Ongoing", "upcoming"),
            Pair("Completed", "complete"),
        )

        val GENRE = arrayOf(
            Pair("Action", "Action_"),
            Pair("Adventure", "Adventure_"),
            Pair("Cars", "Cars_"),
            Pair("Cartoon", "Cartoon_"),
            Pair("Comedy", "Comedy_"),
            Pair("Dementia", "Dementia_"),
            Pair("Demons", "Demons_"),
            Pair("Drama", "Drama_"),
            Pair("Dub", "Dub_"),
            Pair("Ecchi", "Ecchi_"),
            Pair("Fantasy", "Fantasy_"),
            Pair("Game", "Game_"),
            Pair("Harem", "Harem_"),
            Pair("Historical", "Historical_"),
            Pair("Horror", "Horror_"),
            Pair("Josei", "Josei_"),
            Pair("Kids", "Kids_"),
            Pair("Magic", "Magic_"),
            Pair("Martial Arts", "Martial Arts_"),
            Pair("Mecha", "Mecha_"),
            Pair("Military", "Military_"),
            Pair("Movie", "Movie_"),
            Pair("Music", "Music_"),
            Pair("Mystery", "Mystery_"),
            Pair("ONA", "ONA_"),
            Pair("OVA", "OVA_"),
            Pair("Parody", "Parody_"),
            Pair("Police", "Police_"),
            Pair("Psychological", "Psychological_"),
            Pair("Romance", "Romance_"),
            Pair("Samurai", "Samurai_"),
            Pair("School", "School_"),
            Pair("Sci-Fi", "Sci-Fi_"),
            Pair("Seinen", "Seinen_"),
            Pair("Shoujo", "Shoujo_"),
            Pair("Shoujo Ai", "Shoujo Ai_"),
            Pair("Shounen", "Shounen_"),
            Pair("Shounen Ai", "Shounen Ai_"),
            Pair("Slice of Life", "Slice of Life_"),
            Pair("Space", "Space_"),
            Pair("Special", "Special_"),
            Pair("Sports", "Sports_"),
            Pair("Super Power", "Super Power_"),
            Pair("Supernatural", "Supernatural_"),
            Pair("Thriller", "Thriller_"),
            Pair("Vampire", "Vampire_"),
            Pair("Yuri", "Yuri_"),
        )

        val SUBPAGE = arrayOf(
            Pair("<select>", ""),
            Pair("List A-Z", "AnimeListOnline"),
            Pair("List Most Popular", "AnimeListOnline/MostPopular"),
            Pair("List Latest Update", "AnimeListOnline/LatestUpdate"),
            Pair("List Upcoming Anime", "UpcomingAnime"),
            Pair("List New Anime", "AnimeListOnline/Newest"),
        )

        val SCHEDULE = arrayOf(
            Pair("<select>", ""),
            Pair("Yesterday", "Yesterday"),
            Pair("Today", "Today"),
            Pair("Tomorrow", "Tomorrow"),
            Pair("Monday", "Monday"),
            Pair("Tuesday", "Tuesday"),
            Pair("Wednesday", "Wednesday"),
            Pair("Thursday", "Thursday"),
            Pair("Friday", "Friday"),
            Pair("Saturday", "Saturday"),
            Pair("Sunday", "Sunday"),
        )
    }
}
