package eu.kanade.tachiyomi.animeextension.en.hstream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object HstreamFilters {

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

    open class TriStateFilterList(name: String, values: List<TriFilterVal>) : AnimeFilter.Group<TriState>(name, values)
    class TriFilterVal(name: String) : TriState(name)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (first { it is R } as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .toList()
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(
        options: Array<Pair<String, String>>,
    ): List<List<String>> {
        return (first { it is R } as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to options.find { it.first == filter.name }!!.second }
            .groupBy { it.first } // group by state
            .let { dict ->
                val included = dict.get(TriState.STATE_INCLUDE)?.map { it.second }.orEmpty()
                val excluded = dict.get(TriState.STATE_EXCLUDE)?.map { it.second }.orEmpty()
                listOf(included, excluded)
            }
    }

    class GenresFilter : TriStateFilterList("Genres", HstreamFiltersData.GENRES.map { TriFilterVal(it.first) })
    class StudiosFilter : CheckBoxFilterList("Studios", HstreamFiltersData.STUDIOS)

    class OrderFilter : QueryPartFilter("Order by", HstreamFiltersData.ORDERS)

    val FILTER_LIST get() = AnimeFilterList(
        OrderFilter(),
        GenresFilter(),
        StudiosFilter(),
    )

    data class FilterSearchParams(
        val genres: List<String> = emptyList(),
        val blacklisted: List<String> = emptyList(),
        val studios: List<String> = emptyList(),
        val order: String = "view-count",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val (added, blacklisted) = filters.parseTriFilter<GenresFilter>(HstreamFiltersData.GENRES)

        return FilterSearchParams(
            added,
            blacklisted,
            filters.parseCheckbox<StudiosFilter>(HstreamFiltersData.STUDIOS),
            filters.asQueryPart<OrderFilter>(),
        )
    }

    private object HstreamFiltersData {
        val GENRES = arrayOf(
            Pair("3D", "3d"),
            Pair("4K", "4k"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Bdsm", "bdsm"),
            Pair("Big Boobs", "big-boobs"),
            Pair("Blow Job", "blow-job"),
            Pair("Bondage", "bondage"),
            Pair("Boob Job", "boob-job"),
            Pair("Censored", "censored"),
            Pair("Comedy", "comedy"),
            Pair("Cosplay", "cosplay"),
            Pair("Creampie", "creampie"),
            Pair("Dark Skin", "dark-skin"),
            Pair("Elf", "elf"),
            Pair("Facial", "facial"),
            Pair("Fantasy", "fantasy"),
            Pair("Filmed", "filmed"),
            Pair("Foot Job", "foot-job"),
            Pair("Futanari", "futanari"),
            Pair("Gangbang", "gangbang"),
            Pair("Glasses", "glasses"),
            Pair("Hand Job", "hand-job"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Incest", "incest"),
            Pair("Inflation", "inflation"),
            Pair("Lactation", "lactation"),
            Pair("Loli", "loli"),
            Pair("Maid", "maid"),
            Pair("Masturbation", "masturbation"),
            Pair("Milf", "milf"),
            Pair("Mind Break", "mind-break"),
            Pair("Mind Control", "mind-control"),
            Pair("Monster", "monster"),
            Pair("Nekomimi", "nekomimi"),
            Pair("Ntr", "ntr"),
            Pair("Nurse", "nurse"),
            Pair("Orgy", "orgy"),
            Pair("Pov", "pov"),
            Pair("Pregnant", "pregnant"),
            Pair("Public Sex", "public-sex"),
            Pair("Rape", "rape"),
            Pair("Reverse Rape", "reverse-rape"),
            Pair("Rimjob", "rimjob"),
            Pair("Scat", "scat"),
            Pair("School Girl", "school-girl"),
            Pair("Shota", "shota"),
            Pair("Small Boobs", "small-boobs"),
            Pair("Succubus", "succubus"),
            Pair("Swim Suit", "swim-suit"),
            Pair("Teacher", "teacher"),
            Pair("Tentacle", "tentacle"),
            Pair("Threesome", "threesome"),
            Pair("Toys", "toys"),
            Pair("Trap", "trap"),
            Pair("Tsundere", "tsundere"),
            Pair("Ugly Bastard", "ugly-bastard"),
            Pair("Uncensored", "uncensored"),
            Pair("Vanilla", "vanilla"),
            Pair("Virgin", "virgin"),
            Pair("X-Ray", "x-ray"),
            Pair("Yuri", "yuri"),
        )

        val STUDIOS = arrayOf(
            Pair("BOMB! CUTE! BOMB!", "bomb-cute-bomb"),
            Pair("BreakBottle", "breakbottle"),
            Pair("ChiChinoya", "chichinoya"),
            Pair("ChuChu", "chuchu"),
            Pair("Circle Tribute", "circle-tribute"),
            Pair("Collaboration Works", "collaboration-works"),
            Pair("Digital Works", "digital-works"),
            Pair("Discovery", "discovery"),
            Pair("Edge", "edge"),
            Pair("Gold Bear", "gold-bear"),
            Pair("Green Bunny", "green-bunny"),
            Pair("Himajin Planning", "himajin-planning"),
            Pair("King Bee", "king-bee"),
            Pair("L.", "l"),
            Pair("Lune Pictures", "lune-pictures"),
            Pair("MS Pictures", "ms-pictures"),
            Pair("Majin", "majin"),
            Pair("Mary Jane", "mary-jane"),
            Pair("Mediabank", "mediabank"),
            Pair("Mousou Senka", "mousou-senka"),
            Pair("Natural High", "natural-high"),
            Pair("Nihikime no Dozeu", "nihikime-no-dozeu"),
            Pair("Nur", "nur"),
            Pair("Pashmina", "pashmina"),
            Pair("Peak Hunt", "peak-hunt"),
            Pair("Pink Pineapple", "pink-pineapple"),
            Pair("Pixy Soft", "pixy-soft"),
            Pair("Pixy", "pixy"),
            Pair("PoRO", "poro"),
            Pair("Queen Bee", "queen-bee"),
            Pair("Rabbit Gate", "rabbit-gate"),
            Pair("SELFISH", "selfish"),
            Pair("Seven", "seven"),
            Pair("Showten", "showten"),
            Pair("Studio 1st", "studio-1st"),
            Pair("Studio Eromatick", "studio-eromatick"),
            Pair("Studio Fantasia", "studio-fantasia"),
            Pair("Suiseisha", "suiseisha"),
            Pair("Suzuki Mirano", "suzuki-mirano"),
            Pair("T-Rex", "t-rex"),
            Pair("Toranoana", "toranoana"),
            Pair("Union Cho", "union-cho"),
            Pair("Valkyria", "valkyria"),
            Pair("White Bear", "white-bear"),
            Pair("ZIZ", "ziz"),
        )

        val ORDERS = arrayOf(
            Pair("View Count", "view-count"), // the only reason I'm not using a sort filter
            Pair("A-Z", "az"),
            Pair("Z-A", "za"),
            Pair("Recently Uploaded", "recently-uploaded"),
            Pair("Recently Released", "recently-released"),
            Pair("Oldest Uploads", "oldest-uploads"),
            Pair("Oldest Releases", "oldest-releases"),
        )
    }
}
