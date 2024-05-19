package eu.kanade.tachiyomi.animeextension.en.gogoanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object GogoAnimeFilters {
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
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .joinToString("&") { "$name[]=$it" }
    }

    class GenreSearchFilter : CheckBoxFilterList("Genre", GogoAnimeFiltersData.GENRE_SEARCH_LIST)
    class CountrySearchFilter : CheckBoxFilterList("Country", GogoAnimeFiltersData.COUNTRY_SEARCH_LIST)
    class SeasonSearchFilter : CheckBoxFilterList("Season", GogoAnimeFiltersData.SEASON_SEARCH_LIST)
    class YearSearchFilter : CheckBoxFilterList("Year", GogoAnimeFiltersData.YEAR_SEARCH_LIST)
    class LanguageSearchFilter : CheckBoxFilterList("Language", GogoAnimeFiltersData.LANGUAGE_SEARCH_LIST)
    class TypeSearchFilter : CheckBoxFilterList("Type", GogoAnimeFiltersData.TYPE_SEARCH_LIST)
    class StatusSearchFilter : CheckBoxFilterList("Status", GogoAnimeFiltersData.STATUS_SEARCH_LIST)
    class SortSearchFilter : QueryPartFilter("Sort by", GogoAnimeFiltersData.SORT_SEARCH_LIST)

    class GenreFilter : QueryPartFilter("Genre", GogoAnimeFiltersData.GENRE_LIST)
    class RecentFilter : QueryPartFilter("Recent episodes", GogoAnimeFiltersData.RECENT_LIST)
    class SeasonFilter : QueryPartFilter("Season", GogoAnimeFiltersData.SEASON_LIST)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("Advanced search"),
        GenreSearchFilter(),
        CountrySearchFilter(),
        SeasonSearchFilter(),
        YearSearchFilter(),
        LanguageSearchFilter(),
        TypeSearchFilter(),
        StatusSearchFilter(),
        SortSearchFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Select sub-page"),
        AnimeFilter.Header("Note: Ignores search & other filters"),
        GenreFilter(),
        RecentFilter(),
        SeasonFilter(),
    )

    data class FilterSearchParams(
        val filter: String = "",
        val genre: String = "",
        val recent: String = "",
        val season: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val filter = buildList {
            add(filters.parseCheckbox<GenreSearchFilter>(GogoAnimeFiltersData.GENRE_SEARCH_LIST, "genre"))
            add(filters.parseCheckbox<CountrySearchFilter>(GogoAnimeFiltersData.GENRE_SEARCH_LIST, "country"))
            add(filters.parseCheckbox<SeasonSearchFilter>(GogoAnimeFiltersData.SEASON_SEARCH_LIST, "season"))
            add(filters.parseCheckbox<YearSearchFilter>(GogoAnimeFiltersData.YEAR_SEARCH_LIST, "year"))
            add(filters.parseCheckbox<LanguageSearchFilter>(GogoAnimeFiltersData.LANGUAGE_SEARCH_LIST, "language"))
            add(filters.parseCheckbox<TypeSearchFilter>(GogoAnimeFiltersData.TYPE_SEARCH_LIST, "type"))
            add(filters.parseCheckbox<StatusSearchFilter>(GogoAnimeFiltersData.STATUS_SEARCH_LIST, "status"))
            add("sort=${filters.asQueryPart<SortSearchFilter>()}")
        }.filter(String::isNotBlank).joinToString("&")

        return FilterSearchParams(
            filter,
            filters.asQueryPart<GenreFilter>(),
            filters.asQueryPart<RecentFilter>(),
            filters.asQueryPart<SeasonFilter>(),
        )
    }

    private object GogoAnimeFiltersData {
        // copy($("div.cls_genre ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val GENRE_SEARCH_LIST = arrayOf(
            Pair("Action", "action"),
            Pair("Adult Cast", "adult-cast"),
            Pair("Adventure", "adventure"),
            Pair("Anthropomorphic", "anthropomorphic"),
            Pair("Avant Garde", "avant-garde"),
            Pair("Boys Love", "shounen-ai"),
            Pair("Cars", "cars"),
            Pair("CGDCT", "cgdct"),
            Pair("Childcare", "childcare"),
            Pair("Comedy", "comedy"),
            Pair("Comic", "comic"),
            Pair("Crime", "crime"),
            Pair("Crossdressing", "crossdressing"),
            Pair("Delinquents", "delinquents"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Detective", "detective"),
            Pair("Drama", "drama"),
            Pair("Dub", "dub"),
            Pair("Ecchi", "ecchi"),
            Pair("Erotica", "erotica"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Gag Humor", "gag-humor"),
            Pair("Game", "game"),
            Pair("Gender Bender", "gender-bender"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Hentai", "hentai"),
            Pair("High Stakes Game", "high-stakes-game"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Iyashikei", "iyashikei"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Magical Sex Shift", "magical-sex-shift"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Medical", "medical"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Mythology", "mythology"),
            Pair("Organized Crime", "organized-crime"),
            Pair("Parody", "parody"),
            Pair("Performing Arts", "performing-arts"),
            Pair("Pets", "pets"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Racing", "racing"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Romance", "romance"),
            Pair("Romantic Subtext", "romantic-subtext"),
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
            Pair("Strategy Game", "strategy-game"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Survival", "survival"),
            Pair("Suspense", "suspense"),
            Pair("Team Sports", "team-sports"),
            Pair("Thriller", "thriller"),
            Pair("Time Travel", "time-travel"),
            Pair("Vampire", "vampire"),
            Pair("Visual Arts", "visual-arts"),
            Pair("Work Life", "work-life"),
            Pair("Workplace", "workplace"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )

        // copy($("div.cls_genre ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val COUNTRY_SEARCH_LIST = arrayOf(
            Pair("China", "5"),
            Pair("Japan", "2"),
        )

        // copy($("div.cls_season ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val SEASON_SEARCH_LIST = arrayOf(
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
        )

        // copy($("div.cls_year ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val YEAR_SEARCH_LIST = arrayOf(
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1999", "1999"),
        )

        // copy($("div.cls_lang ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val LANGUAGE_SEARCH_LIST = arrayOf(
            Pair("Sub & Dub", "subdub"),
            Pair("Sub", "sub"),
            Pair("Dub", "dub"),
        )

        // copy($("div.cls_type ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val TYPE_SEARCH_LIST = arrayOf(
            Pair("Movie", "3"),
            Pair("TV", "1"),
            Pair("OVA", "26"),
            Pair("ONA", "30"),
            Pair("Special", "2"),
            Pair("Music", "32"),
        )

        // copy($("div.cls_status ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val STATUS_SEARCH_LIST = arrayOf(
            Pair("Not Yet Aired", "Upcoming"),
            Pair("Ongoing", "Ongoing"),
            Pair("Completed", "Completed"),
        )

        // copy($("div.cls_sort ul.dropdown-menu li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("input").first().attr('value')}")`).get().join(',\n'))
        // on /filter.html
        val SORT_SEARCH_LIST = arrayOf(
            Pair("Name A-Z", "title_az"),
            Pair("Recently updated", "recently_updated"),
            Pair("Recently added", "recently_added"),
            Pair("Release date", "release_date"),
        )

        // copy($("div.dropdown-menu > a.dropdown-item").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).attr('href').trim().slice(18)}")`).get().join(',\n'))
        // on /
        val GENRE_LIST = arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Adult Cast", "adult-cast"),
            Pair("Adventure", "adventure"),
            Pair("Anthropomorphic", "anthropomorphic"),
            Pair("Avant Garde", "avant-garde"),
            Pair("Boys Love", "shounen-ai"),
            Pair("Cars", "cars"),
            Pair("CGDCT", "cgdct"),
            Pair("Childcare", "childcare"),
            Pair("Comedy", "comedy"),
            Pair("Comic", "comic"),
            Pair("Crime", "crime"),
            Pair("Crossdressing", "crossdressing"),
            Pair("Delinquents", "delinquents"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Detective", "detective"),
            Pair("Drama", "drama"),
            Pair("Dub", "dub"),
            Pair("Ecchi", "ecchi"),
            Pair("Erotica", "erotica"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Gag Humor", "gag-humor"),
            Pair("Game", "game"),
            Pair("Gender Bender", "gender-bender"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Hentai", "hentai"),
            Pair("High Stakes Game", "high-stakes-game"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Iyashikei", "iyashikei"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Magical Sex Shift", "magical-sex-shift"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Medical", "medical"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Mythology", "mythology"),
            Pair("Organized Crime", "organized-crime"),
            Pair("Parody", "parody"),
            Pair("Performing Arts", "performing-arts"),
            Pair("Pets", "pets"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Racing", "racing"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Romance", "romance"),
            Pair("Romantic Subtext", "romantic-subtext"),
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
            Pair("Strategy Game", "strategy-game"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Survival", "survival"),
            Pair("Suspense", "suspense"),
            Pair("Team Sports", "team-sports"),
            Pair("Thriller", "thriller"),
            Pair("Time Travel", "time-travel"),
            Pair("Vampire", "vampire"),
            Pair("Visual Arts", "visual-arts"),
            Pair("Work Life", "work-life"),
            Pair("Workplace", "workplace"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )

        val RECENT_LIST = arrayOf(
            Pair("<select>", ""),
            Pair("Recent Release", "1"),
            Pair("Recent Dub", "2"),
            Pair("Recent Chinese", "3"),
        )

        val SEASON_LIST = arrayOf(
            Pair("<select>", ""),
            Pair("Latest season", "new-season.html"),
            Pair("Spring 2024", "sub-category/spring-2024-anime"),
            Pair("Winter 2024", "sub-category/winter-2024-anime"),
            Pair("Fall 2023", "sub-category/fall-2023-anime"),
            Pair("Summer 2023", "sub-category/summer-2023-anime"),
            Pair("Spring 2023", "sub-category/spring-2023-anime"),
            Pair("Winter 2023", "sub-category/winter-2023-anime"),
            Pair("Fall 2022", "sub-category/fall-2022-anime"),
            Pair("Summer 2022", "sub-category/summer-2022-anime"),
            Pair("Spring 2022", "sub-category/spring-2022-anime"),
            Pair("Winter 2022", "sub-category/winter-2022-anime"),
            Pair("Fall 2021", "sub-category/fall-2021-anime"),
            Pair("Summer 2021", "sub-category/summer-2021-anime"),
            Pair("Spring 2021", "sub-category/spring-2021-anime"),
            Pair("Winter 2021", "sub-category/winter-2021-anime"),
            Pair("Fall 2020", "sub-category/fall-2020-anime"),
            Pair("Summer 2020", "sub-category/summer-2020-anime"),
            Pair("Spring 2020", "sub-category/spring-2020-anime"),
            Pair("Winter 2020", "sub-category/winter-2020-anime"),
            Pair("Fall 2019", "sub-category/fall-2019-anime"),
            Pair("Summer 2019", "sub-category/summer-2019-anime"),
            Pair("Spring 2019", "sub-category/spring-2019-anime"),
            Pair("Winter 2019", "sub-category/winter-2019-anime"),
            Pair("Fall 2018", "sub-category/fall-2018-anime"),
            Pair("Summer 2018", "sub-category/summer-2018-anime"),
            Pair("Spring 2018", "sub-category/spring-2018-anime"),
            Pair("Winter 2018", "sub-category/winter-2018-anime"),
            Pair("Fall 2017", "sub-category/fall-2017-anime"),
            Pair("Summer 2017", "sub-category/summer-2017-anime"),
            Pair("Spring 2017", "sub-category/spring-2017-anime"),
            Pair("Winter 2017", "sub-category/winter-2017-anime"),
            Pair("Fall 2016", "sub-category/fall-2016-anime"),
            Pair("Summer 2016", "sub-category/summer-2016-anime"),
            Pair("Spring 2016", "sub-category/spring-2016-anime"),
            Pair("Winter 2016", "sub-category/winter-2016-anime"),
            Pair("Fall 2015", "sub-category/fall-2015-anime"),
            Pair("Summer 2015", "sub-category/summer-2015-anime"),
            Pair("Spring 2015", "sub-category/spring-2015-anime"),
            Pair("Winter 2015", "sub-category/winter-2015-anime"),
            Pair("Fall 2014", "sub-category/fall-2014-anime"),
            Pair("Summer 2014", "sub-category/summer-2014-anime"),
            Pair("Spring 2014", "sub-category/spring-2014-anime"),
            Pair("Winter 2014", "sub-category/winter-2014-anime"),
        )
    }
}
