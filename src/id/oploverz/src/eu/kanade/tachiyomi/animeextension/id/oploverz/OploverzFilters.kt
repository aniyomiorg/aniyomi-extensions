package eu.kanade.tachiyomi.animeextension.id.oploverz

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object OploverzFilters {
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

    class SeasonFilter : CheckBoxFilterList(
        "Season",
        FiltersData.SEASON.map { CheckBoxVal(it.first, false) },
    )

    class StudioFilter : CheckBoxFilterList(
        "Studio",
        FiltersData.STUDIO.map { CheckBoxVal(it.first, false) },
    )

    class TypeFilter : QueryPartFilter("Type", FiltersData.TYPE)

    class StatusFilter : QueryPartFilter("Status", FiltersData.STATUS)

    class OrderFilter : QueryPartFilter("Sort By", FiltersData.ORDER)

    val FILTER_LIST
        get() = AnimeFilterList(
            GenreFilter(),
            SeasonFilter(),
            StudioFilter(),
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
                filters.parseCheckbox<SeasonFilter>(FiltersData.SEASON, "season") +
                filters.parseCheckbox<StudioFilter>(FiltersData.STUDIO, "studio") +
                filters.asQueryPart<TypeFilter>("type") +
                filters.asQueryPart<StatusFilter>("status") +
                filters.asQueryPart<OrderFilter>("order"),
        )
    }

    private object FiltersData {
        val ORDER = arrayOf(
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular"),
            Pair("Rating", "rating"),
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
            Pair("Adventure", "adventure"),
            Pair("Award Winning", "award-winning"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Live Action", "live-action"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Parody", "parody"),
            Pair("Psychological", "psychological"),
            Pair("Racing", "racing"),
            Pair("Reverse Harem", "reverse-harem"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Siekai", "siekai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sports", "sports"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Survival", "survival"),
            Pair("Suspense", "suspense"),
            Pair("Time Travel", "time-travel"),
            Pair("Vampire", "vampire"),
        )

        val SEASON = arrayOf(
            Pair("Fall 1999", "fall-1999"),
            Pair("Fall 2002", "fall-2002"),
            Pair("Fall 2004", "fall-2004"),
            Pair("Fall 2006", "fall-2006"),
            Pair("Fall 2009", "fall-2009"),
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
            Pair("Spring 1998", "spring-1998"),
            Pair("Spring 2004", "spring-2004"),
            Pair("Spring 2009", "spring-2009"),
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
            Pair("Spring 2024", "spring-2024"),
            Pair("Summer 1996", "summer-1996"),
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
            Pair("Winter 2000", "winter-2000"),
            Pair("Winter 2001", "winter-2001"),
            Pair("Winter 2007", "winter-2007"),
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
            Pair("Winter 2024", "winter-2024"),
        )

        val STUDIO = arrayOf(
            Pair("8b", "8b"),
            Pair("8bit", "8bit"),
            Pair("A-1 Pictures", "a-1-pictures"),
            Pair("A.C.G.T.", "a-c-g-t"),
            Pair("AIC PLUS+", "aic-plus"),
            Pair("Ajia-Do", "ajia-do"),
            Pair("Animation Do", "animation-do"),
            Pair("Aniplex", "aniplex"),
            Pair("Aniplex of America", "aniplex-of-america"),
            Pair("Arms", "arms"),
            Pair("Asread", "asread"),
            Pair("AtelierPontdarc", "atelierpontdarc"),
            Pair("Bandai Namco Pictures", "bandai-namco-pictures"),
            Pair("Bones", "bones"),
            Pair("Brain's Base", "brains-base"),
            Pair("Bridge", "bridge"),
            Pair("BUG FILMS", "bug-films"),
            Pair("C2C", "c2c"),
            Pair("Children's Playground Entertainment", "childrens-playground-entertainment"),
            Pair("CloverWorks", "cloverworks"),
            Pair("Connect", "connect"),
            Pair("David Production", "david-production"),
            Pair("Diomedea", "diomedea"),
            Pair("Doga Kobo", "doga-kobo"),
            Pair("DR Movie", "dr-movie"),
            Pair("Drive", "drive"),
            Pair("E&H Production", "eh-production"),
            Pair("Encourage Films", "encourage-films"),
            Pair("Feel", "feel"),
            Pair("Gallop", "gallop"),
            Pair("GEEK TOYS", "geek-toys"),
            Pair("Geno Studio", "geno-studio"),
            Pair("GoHands", "gohands"),
            Pair("Gonzo", "gonzo"),
            Pair("Graphinica", "graphinica"),
            Pair("Hoods Drifters Studio", "hoods-drifters-studio"),
            Pair("Hoods Entertainment", "hoods-entertainment"),
            Pair("HOTLINE", "hotline"),
            Pair("J.C.Staff", "j-c-staff"),
            Pair("Kinema Citrus", "kinema-citrus"),
            Pair("Kyoto Animation", "kyoto-animation"),
            Pair("Lerche", "lerche"),
            Pair("LIDENFILMS", "lidenfilms"),
            Pair("M.S.C", "m-s-c"),
            Pair("Madhouse", "madhouse"),
            Pair("Manglobe", "manglobe"),
            Pair("MAPPA", "mappa"),
            Pair("Media Factory", "media-factory"),
            Pair("Millepensee", "millepensee"),
            Pair("NAZ", "naz"),
            Pair("Nexus", "nexus"),
            Pair("Nitroplus", "nitroplus"),
            Pair("Okuruto Noboru", "okuruto-noboru"),
            Pair("Orange", "orange"),
            Pair("P.A. Works", "p-a-works"),
            Pair("Pastel", "pastel"),
            Pair("Pierrot Plus", "pierrot-plus"),
            Pair("Polygon Pictures", "polygon-pictures"),
            Pair("Pony Canyon", "pony-canyon"),
            Pair("Production I.G", "production-i-g"),
            Pair("Production IMS", "production-ims"),
            Pair("Production Reed", "production-reed"),
            Pair("SANZIGEN", "sanzigen"),
            Pair("Satelight", "satelight"),
            Pair("Sentai Filmworks", "sentai-filmworks"),
            Pair("Seven Arcs", "seven-arcs"),
            Pair("Shaft", "shaft"),
            Pair("Shin-Ei Animation", "shin-ei-animation"),
            Pair("Shuka", "shuka"),
            Pair("Signal.MD", "signal-md"),
            Pair("SILVER LINK.", "silver-link"),
            Pair("Studio 3Hz", "studio-3hz"),
            Pair("Studio Bind", "studio-bind"),
            Pair("Studio Comet", "studio-comet"),
            Pair("Studio Deen", "studio-deen"),
            Pair("Studio Kai", "studio-kai"),
            Pair("studio MOTHER", "studio-mother"),
            Pair("Studio Pierrot", "studio-pierrot"),
            Pair("Studio VOLN", "studio-voln"),
            Pair("Sunrise", "sunrise"),
            Pair("SynergySP", "synergysp"),
            Pair("Tatsunoko Production", "tatsunoko-production"),
            Pair("Telecom Animation Film", "telecom-animation-film"),
            Pair("Tezuka Productions", "tezuka-productions"),
            Pair("TMS Entertainment", "tms-entertainment"),
            Pair("Toei Animation", "toei-animation"),
            Pair("Trigger", "trigger"),
            Pair("TROYCA", "troyca"),
            Pair("TYO Animations", "tyo-animations"),
            Pair("ufotable", "ufotable"),
            Pair("White Fox", "white-fox"),
            Pair("Wit Studio", "wit-studio"),
            Pair("Yumeta Company", "yumeta-company"),
        )
    }
}
