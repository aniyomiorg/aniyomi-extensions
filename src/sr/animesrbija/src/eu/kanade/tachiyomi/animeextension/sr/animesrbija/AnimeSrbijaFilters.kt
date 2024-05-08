package eu.kanade.tachiyomi.animeextension.sr.animesrbija

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeSrbijaFilters {
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
            .mapNotNull { checkbox ->
                when {
                    checkbox.state -> {
                        options.find { it.first == checkbox.name }!!.second
                    }
                    else -> null
                }
            }.joinToString("&$name=").let {
                when {
                    it.isBlank() -> ""
                    else -> "$name=$it"
                }
            }
    }

    class SortFilter : QueryPartFilter("Sortiraj po", AnimeSrbijaFiltersData.SORTBY)
    class GenresFilter : CheckBoxFilterList("Žanrove", AnimeSrbijaFiltersData.GENRES)
    class SeasonFilter : CheckBoxFilterList("Sezonu", AnimeSrbijaFiltersData.SEASONS)
    class TypeFilter : CheckBoxFilterList("Tip", AnimeSrbijaFiltersData.TYPES)
    class YearFilter : CheckBoxFilterList("Godinu", AnimeSrbijaFiltersData.YEARS)
    class StudioFilter : CheckBoxFilterList("Studio", AnimeSrbijaFiltersData.STUDIOS)
    class TranslatorFilter : CheckBoxFilterList("Prevodioca", AnimeSrbijaFiltersData.TRANSLATORS)
    class StatusFilter : CheckBoxFilterList("Status", AnimeSrbijaFiltersData.STATUS)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            SortFilter(),
            AnimeFilter.Separator(),
            GenresFilter(),
            SeasonFilter(),
            TypeFilter(),
            YearFilter(),
            StudioFilter(),
            TranslatorFilter(),
            StatusFilter(),
        )

    data class FilterSearchParams(
        val sortby: String = "",
        val genres: String = "",
        val seasons: String = "",
        val types: String = "",
        val years: String = "",
        val studios: String = "",
        val translators: String = "",
        val status: String = "",
    ) {
        val parsedCheckboxes by lazy {
            listOf(
                genres,
                seasons,
                types,
                years,
                studios,
                translators,
                status,
            )
        }
    }

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<SortFilter>(),
            filters.parseCheckbox<GenresFilter>(AnimeSrbijaFiltersData.GENRES, "genre"),
            filters.parseCheckbox<SeasonFilter>(AnimeSrbijaFiltersData.SEASONS, "season"),
            filters.parseCheckbox<TypeFilter>(AnimeSrbijaFiltersData.TYPES, "type"),
            filters.parseCheckbox<YearFilter>(AnimeSrbijaFiltersData.YEARS, "year"),
            filters.parseCheckbox<StudioFilter>(AnimeSrbijaFiltersData.STUDIOS, "studio"),
            filters.parseCheckbox<TranslatorFilter>(AnimeSrbijaFiltersData.TRANSLATORS, "translator"),
            filters.parseCheckbox<StatusFilter>(AnimeSrbijaFiltersData.STATUS, "status"),
        )
    }

    private object AnimeSrbijaFiltersData {
        val SORTBY = arrayOf(
            Pair("Najgledanije", "popular"),
            Pair("MAL Ocena", "rating"),
            Pair("Novo", "new"),
        )

        val GENRES = arrayOf(
            Pair("Akcija", "Akcija"),
            Pair("Avantura", "Avantura"),
            Pair("Boys Love", "Boys Love"),
            Pair("Komedija", "Komedija"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Girls Love", "Girls Love"),
            Pair("Harem", "Harem"),
            Pair("Istorijski", "Istorijski"),
            Pair("Horor", "Horor"),
            Pair("Isekai", "Isekai"),
            Pair("Josei", "Josei"),
            Pair("Borilačke veštine", "Borilačke veštine"),
            Pair("Mecha", "Mecha"),
            Pair("Vojska", "Vojska"),
            Pair("Muzika", "Muzika"),
            Pair("Misterija", "Misterija"),
            Pair("Psihološki", "Psihološki"),
            Pair("Romansa", "Romansa"),
            Pair("Škola", "Škola"),
            Pair("Naučna fantastika", "Naučna fantastika"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shounen", "Shounen"),
            Pair("Svakodnevnica", "Svakodnevnica"),
            Pair("Svemir", "Svemir"),
            Pair("Sport", "Sport"),
            Pair("Natprirodno", "Natprirodno"),
            Pair("Super Moći", "Super Moći"),
            Pair("Vampiri", "Vampiri"),
        )

        val SEASONS = arrayOf(
            Pair("Leto", "Leto"),
            Pair("Proleće", "Proleće"),
            Pair("Zima", "Zima"),
            Pair("Jesen", "Jesen"),
        )

        val TYPES = arrayOf(
            Pair("TV", "TV"),
            Pair("Film", "Film"),
            Pair("ONA", "ONA"),
            Pair("OVA", "OVA"),
            Pair("Specijal", "Specijal"),
        )

        val YEARS = (2024 downTo 1960).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val STUDIOS = arrayOf(
            Pair("8bit", "8bit"),
            Pair("A-1 Pictures", "A-1 Pictures"),
            Pair("A.C.G.T.", "A.C.G.T."),
            Pair("AHA Entertainment", "AHA Entertainment"),
            Pair("AIC Spirits", "AIC Spirits"),
            Pair("APPP", "APPP"),
            Pair("AXsiZ", "AXsiZ"),
            Pair("Ajia-Do", "Ajia-Do"),
            Pair("Akatsuki", "Akatsuki"),
            Pair("Animation Do", "Animation Do"),
            Pair("Arms", "Arms"),
            Pair("Artland", "Artland"),
            Pair("Asahi Production", "Asahi Production"),
            Pair("Ascension", "Ascension"),
            Pair("AtelierPontdarc", "AtelierPontdarc"),
            Pair("B.CMAY PICTURES", "B.CMAY PICTURES"),
            Pair("Bakken Record", "Bakken Record"),
            Pair("Bandai Namco Pictures", "Bandai Namco Pictures"),
            Pair("Bee Train", "Bee Train"),
            Pair("Bibury Animation CG", "Bibury Animation CG"),
            Pair("Bibury Animation Studios", "Bibury Animation Studios"),
            Pair("Blade", "Blade"),
            Pair("Bones", "Bones"),
            Pair("Brain&#039;s Base", "Brain&#039;s Base"),
            Pair("Brain's Base", "Brain's Base"),
            Pair("Bridge", "Bridge"),
            Pair("C2C", "C2C"),
            Pair("Children's Playground Entertainment", "Children's Playground Entertainment"),
            Pair("CloverWorks", "CloverWorks"),
            Pair("CoMix Wave Films", "CoMix Wave Films"),
            Pair("Connect", "Connect"),
            Pair("Creators in Pack", "Creators in Pack"),
            Pair("CygamesPictures", "CygamesPictures"),
            Pair("DLE", "DLE"),
            Pair("DR Movie", "DR Movie"),
            Pair("Daume", "Daume"),
            Pair("David Production", "David Production"),
            Pair("Diomedéa", "Diomedéa"),
            Pair("Doga Kobo", "Doga Kobo"),
            Pair("Drive", "Drive"),
            Pair("EMT Squared", "EMT Squared"),
            Pair("Encourage Films", "Encourage Films"),
            Pair("Ezόla", "Ezόla"),
            Pair("Fanworks", "Fanworks"),
            Pair("Felix Film", "Felix Film"),
            Pair("Flat Studio", "Flat Studio"),
            Pair("Frederator Studios", "Frederator Studios"),
            Pair("Fuji TV", "Fuji TV"),
            Pair("GEEK TOYS", "GEEK TOYS"),
            Pair("GEMBA", "GEMBA"),
            Pair("Gainax", "Gainax"),
            Pair("Gallop", "Gallop"),
            Pair("Geek Toys", "Geek Toys"),
            Pair("Geno Studio", "Geno Studio"),
            Pair("GoHands", "GoHands"),
            Pair("Gonzo", "Gonzo"),
            Pair("Graphinica", "Graphinica"),
            Pair("Group TAC", "Group TAC"),
            Pair("HORNETS", "HORNETS"),
            Pair("Hal Film Maker", "Hal Film Maker"),
            Pair("Hoods Drifters Studio", "Hoods Drifters Studio"),
            Pair("Hoods Entertainment", "Hoods Entertainment"),
            Pair("J.C.Staff", "J.C.Staff"),
            Pair("Jinnis Animation Studios", "Jinnis Animation Studios"),
            Pair("Kamikaze Douga", "Kamikaze Douga"),
            Pair("Kenji Studio", "Kenji Studio"),
            Pair("Khara", "Khara"),
            Pair("Kinema Citrus", "Kinema Citrus"),
            Pair("Kyoto Animation", "Kyoto Animation"),
            Pair("LIDENFILMS", "LIDENFILMS"),
            Pair("LandQ studios", "LandQ studios"),
            Pair("Lapin Track", "Lapin Track"),
            Pair("Larx Entertainment", "Larx Entertainment"),
            Pair("Lay-duce", "Lay-duce"),
            Pair("Lerche", "Lerche"),
            Pair("Liber", "Liber"),
            Pair("MAPPA", "MAPPA"),
            Pair("Madhouse", "Madhouse"),
            Pair("Maho Film", "Maho Film"),
            Pair("Manglobe", "Manglobe"),
            Pair("Marvy Jack", "Marvy Jack"),
            Pair("Millepensee", "Millepensee"),
            Pair("NAZ", "NAZ"),
            Pair("Nexus", "Nexus"),
            Pair("Nomad", "Nomad"),
            Pair("Nut", "Nut"),
            Pair("OLM", "OLM"),
            Pair("ORENDA", "ORENDA"),
            Pair("OZ", "OZ"),
            Pair("Okuruto Noboru", "Okuruto Noboru"),
            Pair("Orange", "Orange"),
            Pair("Ordet", "Ordet"),
            Pair("P.A. Works", "P.A. Works"),
            Pair("Parrot", "Parrot"),
            Pair("Passione", "Passione"),
            Pair("Pastel", "Pastel"),
            Pair("Pierrot Plus", "Pierrot Plus"),
            Pair("Pierrot", "Pierrot"),
            Pair("Pine Jam", "Pine Jam"),
            Pair("Platinum Vision", "Platinum Vision"),
            Pair("Polygon Pictures", "Polygon Pictures"),
            Pair("Production +h.", "Production +h."),
            Pair("Production GoodBook", "Production GoodBook"),
            Pair("Production I.G", "Production I.G"),
            Pair("Production IMS", "Production IMS"),
            Pair("Production Reed", "Production Reed"),
            Pair("Project No.9", "Project No.9"),
            Pair("Quad", "Quad"),
            Pair("Quebico", "Quebico"),
            Pair("Revoroot", "Revoroot"),
            Pair("SANZIGEN", "SANZIGEN"),
            Pair("SILVER LINK.", "SILVER LINK."),
            Pair("Saetta", "Saetta"),
            Pair("Satelight", "Satelight"),
            Pair("Science SARU", "Science SARU"),
            Pair("Seven Arcs Pictures", "Seven Arcs Pictures"),
            Pair("Seven Arcs", "Seven Arcs"),
            Pair("Shaft", "Shaft"),
            Pair("Shin-Ei Animation", "Shin-Ei Animation"),
            Pair("Shuka", "Shuka"),
            Pair("Signal.MD", "Signal.MD"),
            Pair("Sola Digital Arts", "Sola Digital Arts"),
            Pair("Studio 3Hz", "Studio 3Hz"),
            Pair("Studio 4°C", "Studio 4°C"),
            Pair("Studio Bind", "Studio Bind"),
            Pair("Studio Blanc", "Studio Blanc"),
            Pair("Studio Colorido", "Studio Colorido"),
            Pair("Studio Comet", "Studio Comet"),
            Pair("Studio Daisy", "Studio Daisy"),
            Pair("Studio Deen", "Studio Deen"),
            Pair("Studio Eromatick", "Studio Eromatick"),
            Pair("Studio Fantasia", "Studio Fantasia"),
            Pair("Studio Ghibli", "Studio Ghibli"),
            Pair("Studio Gokumi", "Studio Gokumi"),
            Pair("Studio Hibari", "Studio Hibari"),
            Pair("Studio Kafka", "Studio Kafka"),
            Pair("Studio Kai", "Studio Kai"),
            Pair("Studio Mir", "Studio Mir"),
            Pair("Studio Palette", "Studio Palette"),
            Pair("Studio PuYUKAI", "Studio PuYUKAI"),
            Pair("Studio Rikka", "Studio Rikka"),
            Pair("Studio VOLN", "Studio VOLN"),
            Pair("Studio elle", "Studio elle"),
            Pair("Sublimation", "Sublimation"),
            Pair("Sunrise", "Sunrise"),
            Pair("Sunwoo Entertainment", "Sunwoo Entertainment"),
            Pair("SynergySP", "SynergySP"),
            Pair("T-Rex", "T-Rex"),
            Pair("TMS Entertainment", "TMS Entertainment"),
            Pair("TNK", "TNK"),
            Pair("TROYCA", "TROYCA"),
            Pair("TYO Animations", "TYO Animations"),
            Pair("Tatsunoko Production", "Tatsunoko Production"),
            Pair("Tear Studio", "Tear Studio"),
            Pair("Telecom Animation Film", "Telecom Animation Film"),
            Pair("Tezuka Productions", "Tezuka Productions"),
            Pair("Thundray", "Thundray"),
            Pair("Toei Animation", "Toei Animation"),
            Pair("Triangle Staff", "Triangle Staff"),
            Pair("Trigger", "Trigger"),
            Pair("Typhoon Graphics", "Typhoon Graphics"),
            Pair("White Fox", "White Fox"),
            Pair("Wit Studio", "Wit Studio"),
            Pair("Wolfsbane", "Wolfsbane"),
            Pair("Xebec", "Xebec"),
            Pair("Yokohama Animation Lab", "Yokohama Animation Lab"),
            Pair("Yostar Pictures", "Yostar Pictures"),
            Pair("Yumeta Company", "Yumeta Company"),
            Pair("Zero-G Room", "Zero-G Room"),
            Pair("Zero-G", "Zero-G"),
            Pair("Zexcs", "Zexcs"),
            Pair("animate Film", "animate Film"),
            Pair("asread.", "asread."),
            Pair("domerica", "domerica"),
            Pair("feel.", "feel."),
            Pair("l-a-unch・BOX", "l-a-unch・BOX"),
            Pair("ufotable", "ufotable"),
        )

        val TRANSLATORS = arrayOf(
            Pair("6paths", "6paths"),
            Pair("AnimeOverdose", "AnimeOverdose"),
            Pair("AnimeSrbija", "AnimeSrbija"),
            Pair("BG-anime", "BG-anime"),
            Pair("EpicMan", "EpicMan"),
            Pair("Ich1ya", "Ich1ya"),
            Pair("Midor1ya", "Midor1ya"),
            Pair("Netflix", "Netflix"),
            Pair("Zmajevakugla.rs", "Zmajevakugla.rs"),
            Pair("Zmajče", "Zmajče"),
            Pair("kikirikisemenke", "kikirikisemenke"),
            Pair("lofy", "lofy"),
            Pair("meru", "meru"),
            Pair("rueno", "rueno"),
            Pair("trytofindme", "trytofindme"),
        )

        val STATUS = arrayOf(
            Pair("Emituje se", "Emituje se"),
            Pair("Uskoro", "Uskoro"),
            Pair("Završeno", "Završeno"),
        )
    }
}
