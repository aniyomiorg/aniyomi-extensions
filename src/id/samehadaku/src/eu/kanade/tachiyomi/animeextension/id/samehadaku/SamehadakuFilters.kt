package eu.kanade.tachiyomi.animeextension.id.samehadaku

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object SamehadakuFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart(name: String) = "&$name=${vals[state].second}"
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private class CheckBoxVal(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart(name)
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString("&$name[]=").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&$name[]=$it"
                }
            }
    }

    class GenreFilter : CheckBoxFilterList(
        "Genre",
        FiltersData.GENRE.map { CheckBoxVal(it.first, false) },
    )

    class TypeFilter : QueryPartFilter("Type", FiltersData.TYPE)

    class StatusFilter : QueryPartFilter("Status", FiltersData.STATUS)

    class OrderFilter : QueryPartFilter("Sort By", FiltersData.ORDER)

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("Ignored With Text Search!!"),
            GenreFilter(),
            TypeFilter(),
            StatusFilter(),
            OrderFilter(),
        )

    data class FilterSearchParams(
        val filter: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        return FilterSearchParams(
            filters.parseCheckbox<GenreFilter>(FiltersData.GENRE, "genre") +
                filters.asQueryPart<TypeFilter>("type") +
                filters.asQueryPart<StatusFilter>("status") +
                filters.asQueryPart<OrderFilter>("order"),
        )
    }

    private object FiltersData {
        val ORDER = arrayOf(
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular"),
        )

        val STATUS = arrayOf(
            Pair("All", ""),
            Pair("Currently Airing", "Currently Airing"),
            Pair("Finished Airing", "Finished Airing"),
        )

        val TYPE = arrayOf(
            Pair("All", ""),
            Pair("TV", "TV"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "Special"),
            Pair("Movie", "Movie"),
        )

        val GENRE = arrayOf(
            Pair("Action", "action"),
            Pair("Adult Cast", "adult-cast"),
            Pair("Adventure", "adventure"),
            Pair("Boys Love", "boys-love"),
            Pair("Childcare", "childcare"),
            Pair("Comedy", "comedy"),
            Pair("Delinquents", "delinquents"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Detective", "detective"),
            Pair("Drama", "drama"),
            Pair("ecch", "ecch"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Gag Humor", "gag-humor"),
            Pair("Game", "game"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("High Stakes Game", "high-stakes-game"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Iyashikei", "iyashikei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Medical", "medical"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Mythology", "mythology"),
            Pair("Organized Crime", "organized-crime"),
            Pair("Otaku Culture", "otaku-culture"),
            Pair("Parody", "parody"),
            Pair("Performing Arts", "performing-arts"),
            Pair("pilihan", "pilihan"),
            Pair("Police", "police"),
            Pair("poling", "poling"),
            Pair("Psychological", "psychological"),
            Pair("Rame", "recomendation"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Romance", "romance"),
            Pair("Romantic Subtext", "romantic-subtext"),
            Pair("Romantic1", "romantic1"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Showbiz", "showbiz"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Spring 2023", "spring-2023"),
            Pair("Strategy Game", "strategy-game"),
            Pair("Subtext", "subtext"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Survival", "survival"),
            Pair("Suspense", "suspense"),
            Pair("Team Sports", "team-sports"),
            Pair("Thriller", "thriller"),
            Pair("Time Travel", "time-travel"),
            Pair("Vampire", "vampire"),
            Pair("Video Game", "video-game"),
            Pair("vidiogame", "vidiogame"),
            Pair("Visual Arts", "visual-arts"),
            Pair("Work Life", "work-life"),
            Pair("Workplace", "workplace"),
        )
    }
}
