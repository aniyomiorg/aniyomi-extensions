package eu.kanade.tachiyomi.animeextension.en.hstream

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.CheckBoxFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.QueryPartFilter

object HstreamFilters {
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

    class TagsFilter : CheckBoxFilterList("Tags", HstreamFiltersData.TAGS_LIST)
    class StudiosFilter : CheckBoxFilterList("Studios", HstreamFiltersData.STUDIOS_LIST)
    class OrderFilter : QueryPartFilter("Order by", HstreamFiltersData.ORDER_LIST)

    val FILTER_LIST get() = AnimeFilterList(
        TagsFilter(),
        StudiosFilter(),
        OrderFilter(),
    )

    data class FilterSearchParams(
        val tags: String = "",
        val studios: String = "",
        val order: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<TagsFilter>(HstreamFiltersData.TAGS_LIST, "tags"),
            filters.parseCheckbox<StudiosFilter>(HstreamFiltersData.STUDIOS_LIST, "studios"),
            filters.asQueryPart<OrderFilter>(),
        )
    }

    private object HstreamFiltersData {
        val TAGS_LIST = arrayOf(
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

        val STUDIOS_LIST = arrayOf(
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
            Pair("SELFISH", "selfish"),
            Pair("Showten", "showten"),
            Pair("Studio 1st", "studio-1st"),
            Pair("Studio Eromatick", "studio-eromatick"),
            Pair("Studio Fantasia", "studio-fantasia"),
            Pair("Suiseisha", "suiseisha"),
            Pair("Suzuki Mirano", "suzuki-mirano"),
            Pair("T-Rex", "t-rex"),
            Pair("Toranoana", "toranoana"),
            Pair("Union Cho", "union-cho"),
            Pair("White Bear", "white-bear"),
            Pair("ZIZ", "ziz"),
        )

        val ORDER_LIST = arrayOf(
            Pair("A-Z", "title"),
            Pair("Latest Added", "latest"),
            Pair("Z-A", "titledesc"),
        )
    }
}
