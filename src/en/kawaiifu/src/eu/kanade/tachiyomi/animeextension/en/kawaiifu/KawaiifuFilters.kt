package eu.kanade.tachiyomi.animeextension.en.kawaiifu

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KawaiifuFilters {
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
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString("&tag-get[]=").let {
                if (it.isBlank()) {
                    ""
                } else {
                    "&tag-get[]=$it"
                }
            }
    }

    class CategoryFilter : QueryPartFilter("Category", KawaiifuFiltersData.CATEGORIES)
    class TagsFilter : CheckBoxFilterList(
        "Tags",
        KawaiifuFiltersData.TAGS.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        TagsFilter(),
        AnimeFilter.Separator(),
        CategoryFilter(),
    )

    data class FilterSearchParams(
        val category: String = "",
        val tags: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<CategoryFilter>(),
            filters.parseCheckbox<TagsFilter>(KawaiifuFiltersData.TAGS),
        )
    }

    private object KawaiifuFiltersData {
        val CATEGORIES = arrayOf(
            Pair("All", ""),
            Pair("Dub", "dub"),
            Pair("Ecchi Anime", "ecchi-anime"),
            Pair("Entertainment", "entertainment"),
            Pair("Fall 1990", "fall-1990"),
            Pair("Fall 1994", "fall-1994"),
            Pair("Fall 1995", "fall-1995"),
            Pair("Fall 1999", "fall-1999"),
            Pair("Fall 2000", "fall-2000"),
            Pair("Fall 2001", "fall-2001"),
            Pair("Fall 2002", "fall-2002"),
            Pair("Fall 2003", "fall-2003"),
            Pair("Fall 2004", "fall-2004"),
            Pair("Fall 2005", "fall-2005"),
            Pair("Fall 2006", "fall-2006"),
            Pair("Fall 2007", "fall-2007"),
            Pair("Fall 2008", "fall-2008"),
            Pair("Fall 2009", "fall-2009"),
            Pair("Fall 2010", "fall-2010"),
            Pair("Fall 2011", "fall-2011"),
            Pair("Fall 2012", "fall-2012"),
            Pair("Fall 2013", "fall-2013"),
            Pair("Fall 2014", "fall-2014"),
            Pair("Fall 2015", "fall-2015"),
            Pair("Fall 2016", "fall-2016"),
            Pair("Fall 2017", "fall-2017"),
            Pair("Fall 2018", "fall-2018"),
            Pair("Fall 2019", "fall-2019"),
            Pair("Fall 2020", "fall-2020"),
            Pair("Fall 2021", "fall-2021"),
            Pair("Fall 2022", "fall-2022"),
            Pair("Fall 2023", "fall-2023"),
            Pair("Most Watched", "most-watched"),
            Pair("Movies", "anime-movies"),
            Pair("Music", "music"),
            Pair("Others", "dub"),
            Pair("Dub", "others"),
            Pair("OVA/Specials", "ova-specials"),
            Pair("Season", "season"),
            Pair("Spring 1990", "spring-1990"),
            Pair("Spring 1993", "spring-1993"),
            Pair("Spring 1999", "spring-1999"),
            Pair("Spring 2000", "spring-2000"),
            Pair("Spring 2001", "spring-2001"),
            Pair("Spring 2002", "spring-2002"),
            Pair("Spring 2003", "spring-2003"),
            Pair("Spring 2004", "spring-2004"),
            Pair("Spring 2005", "spring-2005"),
            Pair("Spring 2006", "spring-2006"),
            Pair("Spring 2007", "spring-2007"),
            Pair("Spring 2008", "spring-2008"),
            Pair("Spring 2009", "spring-2009"),
            Pair("Spring 2010", "spring-2010"),
            Pair("Spring 2011", "spring-2011"),
            Pair("Spring 2012", "spring-2012"),
            Pair("Spring 2013", "spring-2013"),
            Pair("Spring 2014", "spring-2014"),
            Pair("Spring 2015", "spring-2015"),
            Pair("Spring 2016", "spring-2016"),
            Pair("Spring 2017", "spring-2017"),
            Pair("Spring 2018", "spring-2018"),
            Pair("Spring 2019", "spring-2019"),
            Pair("Spring 2020", "spring-2020"),
            Pair("Spring 2021", "spring-2021"),
            Pair("Spring 2022", "spring-2022"),
            Pair("Spring 2023", "spring-2023"),
            Pair("Summer 1990", "summer-1990"),
            Pair("Summer 1992", "summer-1992"),
            Pair("Summer 1999", "summer-1999"),
            Pair("Summer 2000", "summer-2000"),
            Pair("Summer 2001", "summer-2001"),
            Pair("Summer 2002", "summer-2002"),
            Pair("Summer 2003", "summer-2003"),
            Pair("Summer 2004", "summer-2004"),
            Pair("Summer 2005", "summer-2005"),
            Pair("Summer 2006", "summer-2006"),
            Pair("Summer 2007", "summer-2007"),
            Pair("Summer 2008", "summer-2008"),
            Pair("Summer 2009", "summer-2009"),
            Pair("Summer 2010", "summer-2010"),
            Pair("Summer 2011", "summer-2011"),
            Pair("Summer 2012", "summer-2012"),
            Pair("Summer 2013", "summer-2013"),
            Pair("Summer 2014", "summer-2014"),
            Pair("Summer 2015", "summer-2015"),
            Pair("Summer 2016", "summer-2016"),
            Pair("Summer 2017", "summer-2017"),
            Pair("Summer 2018", "summer-2018"),
            Pair("Summer 2019", "summer-2019"),
            Pair("Summer 2020", "summer-2020"),
            Pair("Summer 2021", "summer-2021"),
            Pair("Summer 2022", "summer-2022"),
            Pair("Summer 2023", "summer-2023"),
            Pair("Trailer", "trailer"),
            Pair("Tv series", "tv-series"),
            Pair("Upcoming", "upcoming"),
            Pair("Winter 1999", "winter-1999"),
            Pair("Winter 2000", "winter-2000"),
            Pair("Winter 2001", "winter-2001"),
            Pair("Winter 2002", "winter-2002"),
            Pair("Winter 2003", "winter-2003"),
            Pair("Winter 2004", "winter-2004"),
            Pair("Winter 2005", "winter-2005"),
            Pair("Winter 2006", "winter-2006"),
            Pair("Winter 2007", "winter-2007"),
            Pair("Winter 2008", "winter-2008"),
            Pair("Winter 2009", "winter-2009"),
            Pair("Winter 2010", "winter-2010"),
            Pair("Winter 2011", "winter-2011"),
            Pair("Winter 2012", "winter-2012"),
            Pair("Winter 2013", "winter-2013"),
            Pair("Winter 2014", "winter-2014"),
            Pair("Winter 2015", "winter-2015"),
            Pair("Winter 2016", "winter-2016"),
            Pair("Winter 2017", "winter-2017"),
            Pair("Winter 2018", "winter-2018"),
            Pair("Winter 2019", "winter-2019"),
            Pair("Winter 2020", "winter-2020"),
            Pair("Winter 2021", "winter-2021"),
            Pair("Winter 2022", "winter-2022"),
            Pair("Winter 2023", "winter-2023"),
        )

        val TAGS = arrayOf(
            Pair("18 +", "18"),
            Pair("4-koma", "4-koma"),
            Pair("Action", "action"),
            Pair("Adapted from Manga", "manga"),
            Pair("Adapted from Mobile Game", "mobile"),
            Pair("Adult", "adult"),
            Pair("Adventure", "adventure"),
            Pair("AKB48", "akb48"),
            Pair("Audio Drama", "audio-drama"),
            Pair("Avant Garde", "avant-garde"),
            Pair("Bluray", "bluray"),
            Pair("Boxing", "boxing"),
            Pair("Boys Love", "boys-love"),
            Pair("Card Battle", "card-battle"),
            Pair("Card Game", "card-game"),
            Pair("Code Geass", "code-geass"),
            Pair("Comedy", "comedy"),
            Pair("Crossdressing", "crossdressing"),
            Pair("Cute Girls Doing Cute Things", "cute-girls-doing-cute-things"),
            Pair("Dementia", "dementia"),
            Pair("Demon", "demon"),
            Pair("Demons", "demons"),
            Pair("Detective", "detective"),
            Pair("Donghua", "donghua"),
            Pair("Drama", "drama"),
            Pair("Drama Fantasy Mystery", "drama-fantasy-mystery"),
            Pair("Drawing", "drawing"),
            Pair("Dub", "dub"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Fighting", "fighting"),
            Pair("Game", "game"),
            Pair("Ghost", "ghost"),
            Pair("Ghoul is ghoul", "ghoul-is-ghoul"),
            Pair("Girls Love", "girls-love"),
            Pair("Gourmet", "gourmet"),
            Pair("Gyaru", "gyaru"),
            Pair("Harem", "harem"),
            Pair("Hentai", "hentai"),
            Pair("High Stakes Game", "high-stakes-game"),
            Pair("Historical", "historical"),
            Pair("History", "history"),
            Pair("Horror", "horror"),
            Pair("Idol", "idol"),
            Pair("Idols (Female)", "idols-female"),
            Pair("Idols (Male)", "idols-male"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Kancolle", "kancolle"),
            Pair("Kids", "kids"),
            Pair("Light Novel", "light-novel"),
            Pair("Magic", "magic"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Maid", "maid"),
            Pair("Manhwa", "manhwa"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("MV", "mv"),
            Pair("Mystery", "mystery"),
            Pair("Novel", "novel"),
            Pair("ONA", "ona"),
            Pair("Original", "original"),
            Pair("Ost", "ost"),
            Pair("Other", "other"),
            Pair("OVA", "ova"),
            Pair("Parody", "parody"),
            Pair("Performing Arts", "performing-arts"),
            Pair("Police", "PS4"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("School Life", "school-life"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Short", "short"),
            Pair("Short Anime", "short-anime"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slapstick Comedy", "slapstick-comedy"),
            Pair("Slice of life", "slice-of-life"),
            Pair("Smut", "smut"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Strategy Game", "strategy-game"),
            Pair("Super Power", "super-power"),
            Pair("Super Powers", "super-powers"),
            Pair("Supernatural", "supernatural"),
            Pair("Suspense", "suspense"),
            Pair("Team Sports", "team-sports"),
            Pair("Tennis", "tennis"),
            Pair("Thriller", "thriller"),
            Pair("Time Travel", "time-travel"),
            Pair("Tragedy", "tragedy"),
            Pair("Uncensored", "uncensored"),
            Pair("Vampire", "vampire"),
            Pair("Video Game", "video-game"),
            Pair("Visual Novel", "visual-novel"),
            Pair("Web Manga", "web-manga"),
            Pair("Webmaster Choice", "webmaster-choice"),
            Pair("Western", "western"),
            Pair("Work Life", "work-life"),
            Pair("Yaoi", "yaoi"),
            Pair("Youkai", "Youkai"),
            Pair("Yuri", "yuri"),
        )
    }
}
