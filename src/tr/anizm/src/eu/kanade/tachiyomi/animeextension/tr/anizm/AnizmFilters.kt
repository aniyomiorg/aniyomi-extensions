package eu.kanade.tachiyomi.animeextension.tr.anizm

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnizmFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, val vals: Array<String>) :
        AnimeFilter.Group<TriState>(name, vals.map(::TriStateVal))

    private class TriStateVal(name: String) : TriState(name)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(): List<List<String>> {
        return (getFirst<R>() as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to filter.name }
            .groupBy { it.first } // group by state
            .let {
                val included = it.get(TriState.STATE_INCLUDE)?.map { it.second } ?: emptyList<String>()
                val excluded = it.get(TriState.STATE_EXCLUDE)?.map { it.second } ?: emptyList<String>()
                listOf(included, excluded)
            }
    }

    class InitialLetterFilter : QueryPartFilter("İlk harf", AnizmFiltersData.INITIAL_LETTER)

    class SortFilter : AnimeFilter.Sort(
        "Sıra",
        AnizmFiltersData.ORDERS.map { it.first }.toTypedArray(),
        Selection(0, true),
    )

    class StudiosFilter : TriStateFilterList("Stüdyos", AnizmFiltersData.STUDIOS)

    val FILTER_LIST get() = AnimeFilterList(
        InitialLetterFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        StudiosFilter(),
    )

    data class FilterSearchParams(
        val initialLetter: String = "",
        val sortBy: String = "A-Z",
        val orderAscending: Boolean = true,
        val blackListedStudios: List<String> = emptyList(),
        val includedStudios: List<String> = emptyList(),
        var animeName: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val (order, isAscending) = filters.getFirst<SortFilter>().state?.let {
            Pair(AnizmFiltersData.ORDERS[it.index].second, it.ascending)
        } ?: Pair("A-Z", true)

        val (includedStudios, excludedStudios) = filters.parseTriFilter<StudiosFilter>()

        return FilterSearchParams(
            initialLetter = filters.asQueryPart<InitialLetterFilter>(),
            sortBy = order,
            orderAscending = isAscending,
            blackListedStudios = excludedStudios,
            includedStudios = includedStudios,
        )
    }

    private fun mustRemove(anime: SearchItemDto, params: FilterSearchParams): Boolean {
        return when {
            params.animeName != "" && !anime.names.any { it.contains(params.animeName, true) } -> true
            params.initialLetter != "" && !anime.title.lowercase().startsWith(params.initialLetter) -> true
            params.blackListedStudios.size > 0 && params.blackListedStudios.any {
                anime.studios?.contains(it, true) == true
            } -> true
            params.includedStudios.size > 0 && params.includedStudios.any {
                anime.studios?.contains(it, true)?.not() == true
            } -> true
            else -> false
        }
    }

    private inline fun <T, R : Comparable<R>> Sequence<T>.sortedByIf(
        isAscending: Boolean,
        crossinline selector: (T) -> R,
    ): Sequence<T> {
        return when {
            isAscending -> sortedBy(selector)
            else -> sortedByDescending(selector)
        }
    }

    fun Sequence<SearchItemDto>.applyFilterParams(params: FilterSearchParams): Sequence<SearchItemDto> {
        return filterNot { mustRemove(it, params) }.let { results ->
            when (params.sortBy) {
                "A-Z" -> results.sortedByIf(params.orderAscending) { it.title.lowercase() }
                "year" -> results.sortedByIf(params.orderAscending) { it.year?.toIntOrNull() ?: 0 }
                "mal" -> results.sortedByIf(params.orderAscending) { it.malpoint ?: 0.0 }
                else -> results
            }
        }
    }

    private object AnizmFiltersData {
        val INITIAL_LETTER = arrayOf(Pair("Select", "")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val ORDERS = arrayOf(
            Pair("Alfabetik sıra", "A-Z"),
            Pair("Yapım Yılı", "year"),
            Pair("MAL Score", "mal"),
        )

        val STUDIOS = arrayOf(
            "2:10 AM Animation",
            "3xCube",
            "5 Inc.",
            "8bit",
            "A-1 Pictures",
            "A-Real",
            "A.C.G.T.",
            "AHA Entertainment",
            "AIC",
            "APPP",
            "AQUA ARIS",
            "ARECT",
            "ASK Animation Studio",
            "AXsiZ",
            "Acca effe",
            "Actas",
            "Adonero",
            "Agent 21",
            "Ajia-Do",
            "Akatsuki",
            "Albacrow",
            "Alfred Imageworks",
            "Anima",
            "Anima&Co.",
            "Animate Film",
            "Animation Do",
            "Anime Beans",
            "Anpro",
            "Ark",
            "Arms",
            "Artland",
            "Artmic",
            "Arvo Animation",
            "Asahi Production",
            "Ascension",
            "Ashi Production",
            "Asread",
            "Asread.",
            "AtelierPontdarc",
            "B.CMAY PICTURES",
            "BUG FILMS",
            "Bakken Record",
            "Bandai Namco Pictures",
            "Barnum Studio",
            "BeSTACK",
            "Bee Media",
            "Bee Train",
            "Bibury Animation CG",
            "Bibury Animation Studios",
            "BigFireBird Animation",
            "Blade",
            "Bones",
            "Brain's Base",
            "Bridge",
            "C-Station",
            "C2C",
            "CANDY BOX",
            "CG Year",
            "CGCG Studio",
            "CLAP",
            "Chaos Project",
            "Charaction",
            "Children's Playground Entertainment",
            "CloverWorks",
            "CoMix Wave Films",
            "Code",
            "Colored Pencil Animation",
            "Colored Pencil Animation Japan",
            "Connect",
            "Craftar Studios",
            "Creators in Pack",
            "Cyclone Graphics",
            "CygamesPictures",
            "DLE",
            "DMM.futureworks",
            "DR Movie",
            "DRAWIZ",
            "Da Huoniao Donghua",
            "Dai-Ichi Douga",
            "DandeLion Animation Studio",
            "Daume",
            "David Production",
            "Digital Frontier",
            "Digital Network Animation",
            "Diomedea",
            "DiomedÃ©a",
            "Disney Plus",
            "Doga Kobo",
            "Domerica",
            "Dongwoo A&E",
            "Drive",
            "Drop",
            "Dwango",
            "Dynamo Pictures",
            "E&G Films",
            "EKACHI EPILKA",
            "EMT Squared",
            "ENGI",
            "East Fish Studio",
            "Egg Firm",
            "Emon",
            "Encourage Films",
            "EzÏ�la",
            "FILMONY",
            "Fanworks",
            "Feel.",
            "Felix Film",
            "Fenz",
            "Fifth Avenue",
            "Filmlink International",
            "Flat Studio",
            "Front Line",
            "Fuji TV",
            "Fukushima Gaina",
            "G&G Entertainment",
            "G-angle",
            "GANSIS",
            "GEEK TOYS",
            "GEMBA",
            "GIFTanimation",
            "GRIZZLY",
            "Gaina",
            "Gainax",
            "Gallop",
            "Gathering",
            "Geek Toys",
            "Gekkou",
            "Geno Studio",
            "Giga Production",
            "Ginga Ya",
            "GoHands",
            "Gonzo",
            "Gosay Studio",
            "Graphinica",
            "Gravity Well",
            "Group TAC",
            "Grouper Productions",
            "HORNETS",
            "Hal Film Maker",
            "Haoliners Animation League",
            "Helo.inc",
            "Hoods Drifters Studio",
            "Hoods Entertainment",
            "Hotline",
            "I.Gzwei",
            "IDRAGONS Creative Studio",
            "ILCA",
            "IMAGICA Lab.",
            "Imagin",
            "Imagineer",
            "Indivision",
            "Irawias",
            "Ishikawa Pro",
            "Issen",
            "Ixtl",
            "J.C.Staff",
            "JCF",
            "Japan Vistec",
            "Jinnis Animation Studios",
            "Jumondo",
            "KOO-KI",
            "Kachidoki Studio",
            "Kamikaze Douga",
            "Kanaban Graphics",
            "Kaname Productions",
            "Kazami Gakuen Koushiki Douga-bu",
            "KeyEast",
            "Khara",
            "Kinema Citrus",
            "Kitty Film Mitaka Studio",
            "Kitty Films",
            "Kyoto Animation",
            "Kyotoma",
            "L-a-unchã�»BOX",
            "LAN Studio",
            "LEVELS",
            "LICO",
            "LIDENFILMS",
            "LIDENFILMS Kyoto Studio",
            "LIDENFILMS Osaka Studio",
            "LMD",
            "LandQ studios",
            "Lapin Track",
            "Larx Entertainment",
            "Lay-duce",
            "Lerche",
            "Lesprit",
            "Liber",
            "Life Work",
            "Light Chaser Animation Studios",
            "Lilix",
            "LÂ²Studio",
            "M.S.C",
            "MAPPA",
            "MASTER LIGHTS",
            "MMT Technology",
            "Madhouse",
            "Magia Doraglier",
            "Magic Bus",
            "Maho Film",
            "Manglobe",
            "Marine Entertainment",
            "Marvy Jack",
            "Marza Animation Planet",
            "Milky Cartoon",
            "Millepensee",
            "Mimoid",
            "Minami Machi Bugyousho",
            "Monofilmo",
            "MooGoo",
            "Mook Animation",
            "Mook DLE",
            "Motion Magic",
            "Mushi Production",
            "NAZ",
            "NHK",
            "Namu Animation",
            "Netflix",
            "Next Media Animation",
            "Nexus",
            "Nice Boat Animation",
            "Nihon Ad Systems",
            "Nippon Animation",
            "Nomad",
            "Nut",
            "OLM",
            "OLM Digital",
            "OLM Team Yoshioka",
            "OZ",
            "Office DCI",
            "Office No. 8",
            "Oh! Production",
            "Okuruto Noboru",
            "Opera House",
            "Orange",
            "Ordet",
            "Oxybot",
            "P.A. Works",
            "P.I.C.S.",
            "PRA",
            "Pancake",
            "Passione",
            "Pastel",
            "Pb Animation Co. Ltd.",
            "Pencil Lead Animate",
            "Phoenix Entertainment",
            "Picture Magic",
            "Pierrot",
            "Pierrot Plus",
            "Pine Jam",
            "Planet",
            "Platinum Vision",
            "Plum",
            "Polygon Pictures",
            "Primastea",
            "PrimeTime",
            "Production +h.",
            "Production GoodBook",
            "Production I.G",
            "Production IMS",
            "Production Reed",
            "Production doA",
            "Project No.9",
            "Purple Cow Studio Japan",
            "Quad",
            "Qualia Animation",
            "Qubic Pictures",
            "REALTHING",
            "Radix",
            "Red Dog Culture House",
            "Remic",
            "Revoroot",
            "Rikuentai",
            "Rising Force",
            "Robot Communications",
            "Rockwell Eyes",
            "Ruo Hong Culture",
            "SANZIGEN",
            "SILVER LINK.",
            "Saetta",
            "Saigo no Shudan",
            "Sakura Create",
            "Samsara Animation Studio",
            "Sanctuary",
            "Sanrio",
            "Satelight",
            "Science SARU",
            "Scooter Films",
            "Seven",
            "Seven Arcs",
            "Seven Stone Entertainment",
            "Shaft",
            "Shanghai Animation Film Studio",
            "Shanghai Foch Film",
            "Shenying Animation",
            "Shimogumi",
            "Shin-Ei Animation",
            "Shirogumi",
            "Shuka",
            "Signal.MD",
            "Silver",
            "Silver Link.",
            "Sola Digital Arts",
            "Soyep",
            "Space Neko Company",
            "Sparkly Key Animation Studio",
            "Square Enix Visual Works",
            "Staple Entertainment",
            "Steve N' Steven",
            "Stingray",
            "Studio 3Hz",
            "Studio 4Â°C",
            "Studio A-CAT",
            "Studio Animal",
            "Studio Bind",
            "Studio Blanc",
            "Studio Blanc.",
            "Studio Chizu",
            "Studio Colorido",
            "Studio Comet",
            "Studio Dadashow",
            "Studio Daisy",
            "Studio Deen",
            "Studio Fantasia",
            "Studio Flad",
            "Studio Flag",
            "Studio GOONEYS",
            "Studio Ghibli",
            "Studio Gokumi",
            "Studio Hibari",
            "Studio Hokiboshi",
            "Studio Jemi",
            "Studio Junio",
            "Studio Kafka",
            "Studio Kai",
            "Studio Kikan",
            "Studio LAN",
            "Studio Lings",
            "Studio Live",
            "Studio M2",
            "Studio MOTHER",
            "Studio March",
            "Studio Matrix",
            "Studio Moriken",
            "Studio Palette",
            "Studio Pierrot",
            "Studio Ponoc",
            "Studio PuYUKAI",
            "Studio Rikka",
            "Studio Signal",
            "Studio Signpost",
            "Studio VOLN",
            "Studio Z5",
            "Studio elle",
            "Studio! Cucuri",
            "Sublimation",
            "Success Corp.",
            "Sunrise",
            "Sunrise Beyond",
            "Super Normal Studio",
            "SynergySP",
            "TMS Entertainment",
            "TNK",
            "TROYCA",
            "TYO Animations",
            "Tama Production",
            "Tamura Shigeru Studio",
            "Tatsunoko Production",
            "Team Yamahitsuji",
            "Team YokkyuFuman",
            "TeamKG",
            "Tear Studio",
            "Telecom Animation Film",
            "Tencent Penguin Pictures",
            "Tengu Kobo",
            "Tezuka Productions",
            "The Answer Studio",
            "Thundray",
            "Toei Animation",
            "Toho Interactive Animation",
            "Tokyo Kids",
            "Tokyo Movie Shinsha",
            "Tomason",
            "Tomovies",
            "Topcraft",
            "Trans Arts",
            "Tri-Slash",
            "TriF Studio",
            "Triangle Staff",
            "Trigger",
            "Trinet Entertainment",
            "Tsuchida Productions",
            "Twilight Studio",
            "Typhoon Graphics",
            "UWAN Pictures",
            "Ufotable",
            "Vega Entertainment",
            "View Works",
            "W-Toon Studio",
            "WAO World",
            "Wawayu Animation",
            "White Fox",
            "Wit Studio",
            "Wolf Smoke Studio",
            "Wolfsbane",
            "XFLAG",
            "Xebec",
            "YHKT Entertainment",
            "Yaoyorozu",
            "Yokohama Animation Lab",
            "Yostar Pictures",
            "Yumeta Company",
            "Zero-G",
            "Zexcs",
        )
    }
}
