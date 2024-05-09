package eu.kanade.tachiyomi.animeextension.id.minioppai

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.CheckBoxFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.QueryPartFilter

object MiniOppaiFilters {
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

    class OrderFilter : QueryPartFilter("Order", MiniOppaiFiltersData.ORDER_LIST)

    class GenresFilter : CheckBoxFilterList("Genres", MiniOppaiFiltersData.GENRES_LIST)
    class CountriesFilter : CheckBoxFilterList("Countries", MiniOppaiFiltersData.COUNTRIES_LIST)
    class QualitiesFilter : CheckBoxFilterList("Qualities", MiniOppaiFiltersData.QUALITIES_LIST)
    class YearsFilter : CheckBoxFilterList("Years", MiniOppaiFiltersData.YEARS_LIST)
    class StatusFilter : CheckBoxFilterList("Status", MiniOppaiFiltersData.STATUS_LIST)

    val FILTER_LIST get() = AnimeFilterList(
        OrderFilter(),
        GenresFilter(),
        CountriesFilter(),
        QualitiesFilter(),
        YearsFilter(),
        StatusFilter(),
    )

    data class FilterSearchParams(
        val order: String = "",
        val genres: String = "",
        val countries: String = "",
        val qualities: String = "",
        val year: String = "",
        val status: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<OrderFilter>(),
            filters.parseCheckbox<GenresFilter>(MiniOppaiFiltersData.GENRES_LIST, "genre"),
            filters.parseCheckbox<CountriesFilter>(MiniOppaiFiltersData.COUNTRIES_LIST, "country"),
            filters.parseCheckbox<QualitiesFilter>(MiniOppaiFiltersData.QUALITIES_LIST, "quality"),
            filters.parseCheckbox<YearsFilter>(MiniOppaiFiltersData.YEARS_LIST, "years"),
            filters.parseCheckbox<StatusFilter>(MiniOppaiFiltersData.STATUS_LIST, "status"),
        )
    }

    private object MiniOppaiFiltersData {
        val ORDER_LIST = arrayOf(
            Pair("Release date", "added"),
            Pair("IMDb", "imdb"),
            Pair("Latest", "latest"),
            Pair("Most viewed", "popular"),
            Pair("Name", "title"),
        )

        val GENRES_LIST = arrayOf(
            Pair("3D Hentai", "3d-hentai"),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Armpit", "armpit"),
            Pair("Ashikoki", "ashikoki"),
            Pair("BDSM", "bdsm"),
            Pair("Big Oppai", "big-oppai"),
            Pair("Blowjob", "blowjob"),
            Pair("Bondage", "bondage"),
            Pair("Censored", "censored"),
            Pair("Cheating", "cheating"),
            Pair("Chikan", "chikan"),
            Pair("Comedy", "comedy"),
            Pair("Cosplay", "cosplay"),
            Pair("Creampie", "creampie"),
            Pair("Crime", "crime"),
            Pair("Dark Skin", "dark-skin"),
            Pair("Demons", "demons"),
            Pair("Doctor", "doctor"),
            Pair("Domination", "domination"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Elf", "elf"),
            Pair("Eroge", "eroge"),
            Pair("Exhibitionist", "exhibitionist"),
            Pair("Facial", "facial"),
            Pair("Fantasy", "fantasy"),
            Pair("Fellatio", "fellatio"),
            Pair("Female Monster", "female-monster"),
            Pair("Femdom", "femdom"),
            Pair("Fetish", "fetish"),
            Pair("Footjob", "footjob"),
            Pair("Forced", "forced"),
            Pair("Futanari", "futanari"),
            Pair("Gal", "gal"),
            Pair("Gangbang", "gangbang"),
            Pair("Group", "group"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Housewife", "housewife"),
            Pair("Humiliation", "humiliation"),
            Pair("Idol", "idol"),
            Pair("Incest", "incest"),
            Pair("Intercrural", "intercrural"),
            Pair("Josei", "josei"),
            Pair("Kemonomimi", "kemonomimi"),
            Pair("Kimono", "kimono"),
            Pair("Lactation", "lactation"),
            Pair("Lingerie", "lingerie"),
            Pair("MILF", "milf"),
            Pair("Magic", "magic"),
            Pair("Maid", "maid"),
            Pair("Male Monster", "male-monster"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Masturbation", "masturbation"),
            Pair("Mecha", "mecha"),
            Pair("Megane", "megane"),
            Pair("Mind Control", "mind-control"),
            Pair("Mizugi", "mizugi"),
            Pair("Monster", "monster"),
            Pair("Mystery", "mystery"),
            Pair("Neko", "neko"),
            Pair("Netorare", "netorare"),
            Pair("Nurse", "nurse"),
            Pair("Office", "office"),
            Pair("Onee-san", "onee-san"),
            Pair("Onsen", "onsen"),
            Pair("Oppai", "oppai"),
            Pair("Oral", "oral"),
            Pair("Osananajimi", "osananajimi"),
            Pair("Outdoor", "outdoor"),
            Pair("Paizuri", "paizuri"),
            Pair("Pantyhose", "pantyhose"),
            Pair("Pantyhouse", "pantyhouse"),
            Pair("Parody", "parody"),
            Pair("Pregnant", "pregnant"),
            Pair("Prostitution", "prostitution"),
            Pair("Rape", "rape"),
            Pair("Romance", "romance"),
            Pair("School", "school"),
            Pair("Schoolgirl", "schoolgirl"),
            Pair("Seinen", "seinen"),
            Pair("Sex Toys", "sex-toys"),
            Pair("Shibari", "shibari"),
            Pair("Shota", "shota"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Stocking", "stocking"),
            Pair("Succubus", "succubus"),
            Pair("Supernatural", "supernatural"),
            Pair("Swimsuit", "swimsuit"),
            Pair("Teacher", "teacher"),
            Pair("Tennis", "tennis"),
            Pair("Tentacles", "tentacles"),
            Pair("Threesome", "threesome"),
            Pair("Tsundere", "tsundere"),
            Pair("Uncensored", "uncensored"),
            Pair("stock", "stock"),
        )

        val COUNTRIES_LIST = arrayOf(
            Pair("JP", "jp"),
            Pair("TH", "th"),
        )

        val QUALITIES_LIST = arrayOf(
            Pair("BD", "bd"),
            Pair("Censored", "censored"),
            Pair("HD", "hd"),
            Pair("TV", "tv"),
            Pair("Uncensored", "uncensored"),
            Pair("Web", "web"),
        )

        val YEARS_LIST = (2024 downTo 2001).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val STATUS_LIST = arrayOf(
            Pair("Completed", "completed"),
            Pair("Dropped", "dropped"),
            Pair("Ongoing", "ongoing"),
        )
    }
}
