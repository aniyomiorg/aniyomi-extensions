package eu.kanade.tachiyomi.animeextension.all.animexin

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeXinFilters {

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
                    "$name[]=$it"
                }
            }
    }

    class GenresFilter : CheckBoxFilterList(
        "Genres",
        AnimeXinFiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    class SeasonsFilter : CheckBoxFilterList(
        "Seasons",
        AnimeXinFiltersData.SEASONS.map { CheckBoxVal(it.first, false) },
    )

    class StudiosFilter : CheckBoxFilterList(
        "Studios",
        AnimeXinFiltersData.STUDIOS.map { CheckBoxVal(it.first, false) },
    )

    class StatusFilter : QueryPartFilter("Status", AnimeXinFiltersData.STATUS)
    class TypeFilter : QueryPartFilter("Type", AnimeXinFiltersData.TYPE)
    class SubFilter : QueryPartFilter("Sub", AnimeXinFiltersData.SUB)
    class OrderFilter : QueryPartFilter("Order", AnimeXinFiltersData.ORDER)

    val FILTER_LIST = AnimeFilterList(
        GenresFilter(),
        SeasonsFilter(),
        StudiosFilter(),
        AnimeFilter.Separator(),
        StatusFilter(),
        TypeFilter(),
        SubFilter(),
        OrderFilter(),
    )

    data class FilterSearchParams(
        val genres: String = "",
        val SEASONS: String = "",
        val studios: String = "",
        val status: String = "",
        val type: String = "",
        val sub: String = "",
        val order: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(AnimeXinFiltersData.GENRES, "genre"),
            filters.parseCheckbox<SeasonsFilter>(AnimeXinFiltersData.SEASONS, "season"),
            filters.parseCheckbox<StudiosFilter>(AnimeXinFiltersData.STUDIOS, "studio"),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<SubFilter>(),
            filters.asQueryPart<OrderFilter>(),
        )
    }

    private object AnimeXinFiltersData {
        val ALL = Pair("All", "")

        val GENRES = arrayOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Demon", "demon"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Historical", "historical"),
            Pair("Isekai", "isekai"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mystery", "mystery"),
            Pair("Over Power", "over-power"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Romance", "romance"),
            Pair("School", "school"),
            Pair("Sci-fi", "sci-fi"),
            Pair("Supernatural", "supernatural"),
            Pair("War", "war"),
        )

        val SEASONS = arrayOf(
            Pair("4", "4"),
            Pair("OVA", "ova"),
            Pair("Season 1", "season-1"),
            Pair("Season 2", "season-2"),
            Pair("Season 3", "season-3"),
            Pair("Season 4", "season-4"),
            Pair("Season 5", "season-5"),
            Pair("Season 7", "season-7"),
            Pair("Season 8", "season-8"),
            Pair("season1", "season1"),
            Pair("Winter 2023", "winter-2023"),
        )

        val STUDIOS = arrayOf(
            Pair("2:10 Animatión", "210-animation"),
            Pair("ASK Animation Studio", "ask-animation-studio"),
            Pair("Axis Studios", "axis-studios"),
            Pair("Azure Sea Studios", "azure-sea-studios"),
            Pair("B.C May Pictures", "b-c-may-pictures"),
            Pair("B.CMAY PICTURES", "b-cmay-pictures"),
            Pair("BigFireBird Animation", "bigfirebird-animation"),
            Pair("Bili Bili", "bili-bili"),
            Pair("Bilibili", "bilibili"),
            Pair("Build Dream", "build-dream"),
            Pair("BYMENT", "byment"),
            Pair("CG Year", "cg-year"),
            Pair("CHOSEN", "chosen"),
            Pair("Cloud Art", "cloud-art"),
            Pair("Colored Pencil Animation", "colored-pencil-animation"),
            Pair("D.ROCK-ART", "d-rock-art"),
            Pair("Djinn Power", "djinn-power"),
            Pair("Green Monster Team", "green-monster-team"),
            Pair("Haoliners Animation", "haoliners-animation"),
            Pair("He Zhou Culture", "he-zhou-culture"),
            Pair("L²Studio", "l%c2%b2studio"),
            Pair("Lingsanwu Animation", "lingsanwu-animation"),
            Pair("Mili Pictures", "mili-pictures"),
            Pair("Nice Boat Animation", "nice-boat-animation"),
            Pair("Original Force", "original-force"),
            Pair("Pb Animation Co. Ltd.", "pb-animation-co-ltd"),
            Pair("Qing Xiang", "qing-xiang"),
            Pair("Ruo Hong Culture", "ruo-hong-culture"),
            Pair("Samsara Animation Studio", "samsara-animation-studio"),
            Pair("Shanghai Foch Film Culture Investment", "shanghai-foch-film-culture-investment"),
            Pair("Shanghai Motion Magic", "shanghai-motion-magic"),
            Pair("Shenman Entertainment", "shenman-entertainment"),
            Pair("Soyep", "soyep"),
            Pair("soyep.cn", "soyep-cn"),
            Pair("Sparkly Key Animation Studio", "sparkly-key-animation-studio"),
            Pair("Tencent Penguin Pictures.", "tencent-penguin-pictures"),
            Pair("Wan Wei Mao Donghua", "wan-wei-mao-donghua"),
            Pair("Wawayu Animation", "wawayu-animation"),
            Pair("Wonder Cat Animation", "wonder-cat-animation"),
            Pair("Xing Yi Kai Chen", "xing-yi-kai-chen"),
            Pair("Xuan Yuan", "xuan-yuan"),
            Pair("Year Young Culture", "year-young-culture"),
        )

        val STATUS = arrayOf(
            ALL,
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
            Pair("Upcoming", "upcoming"),
            Pair("Hiatus", "hiatus"),
        )

        val TYPE = arrayOf(
            ALL,
            Pair("TV Series", "tv"),
            Pair("OVA", "ova"),
            Pair("Movie", "movie"),
            Pair("Live Action", "live action"),
            Pair("Special", "special"),
            Pair("BD", "bd"),
            Pair("ONA", "ona"),
            Pair("Music", "music"),
        )

        val SUB = arrayOf(
            ALL,
            Pair("Sub", "sub"),
            Pair("Dub", "dub"),
            Pair("RAW", "raw"),
        )

        val ORDER = arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular"),
            Pair("Rating", "rating"),
        )
    }
}
