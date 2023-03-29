package eu.kanade.tachiyomi.animeextension.it.aniplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AniPlayFilters {

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
        return this.filterIsInstance<R>().joinToString("") {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class GenereFilter : CheckBoxFilterList(
        "Genere",
        AniPlayFiltersData.genere.map { CheckBoxVal(it.first, false) },
    )

    class TipologiaFilter : CheckBoxFilterList(
        "Tipologia anime",
        AniPlayFiltersData.tipologia.map { CheckBoxVal(it.first, false) },
    )

    class StatoFilter : CheckBoxFilterList(
        "Stato",
        AniPlayFiltersData.stato.map { CheckBoxVal(it.first, false) },
    )

    class OrigineFilter : CheckBoxFilterList(
        "Origine",
        AniPlayFiltersData.origine.map { CheckBoxVal(it.first, false) },
    )

    class StudioFilter : CheckBoxFilterList(
        "Studio",
        AniPlayFiltersData.studio.map { CheckBoxVal(it.first, false) },
    )

    class InizioFilter : QueryPartFilter("Inizio", AniPlayFiltersData.anni)
    class FineFilter : QueryPartFilter("Fine", AniPlayFiltersData.anni)
    class OrdinaFilter : QueryPartFilter("Ordina per", AniPlayFiltersData.ordina)

    class AnniFilter : QueryPartFilter("Anni", AniPlayFiltersData.anni)
    class StagioneFilter : QueryPartFilter("Stagione", AniPlayFiltersData.stagione)

    val filterList = AnimeFilterList(
        OrdinaFilter(),
        AnimeFilter.Separator(),

        GenereFilter(),
        TipologiaFilter(),
        StatoFilter(),
        OrigineFilter(),
        StudioFilter(),

        AnimeFilter.Separator(),
        AnimeFilter.Header("Anni"),
        InizioFilter(),
        FineFilter(),

        AnimeFilter.Separator(),
        AnimeFilter.Header("Anime stagionali"),
        AnimeFilter.Header("(ignora altri filtri tranne l'ordinamento per)"),
        AnniFilter(),
        StagioneFilter(),

    )

    data class FilterSearchParams(
        val ordina: String = "views,desc",
        val genere: String = "",
        val tipologia: String = "",
        val stato: String = "",
        val origine: String = "",
        val studio: String = "",
        val inizio: String = "",
        val fine: String = "",
        val anni: String = "",
        val stagione: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val genere: String = filters.filterIsInstance<GenereFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    AniPlayFiltersData.genere.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString(",")

        val tipologia: String = filters.filterIsInstance<TipologiaFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    AniPlayFiltersData.tipologia.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString(",")

        val stato: String = filters.filterIsInstance<StatoFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    AniPlayFiltersData.stato.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString(",")

        val origine: String = filters.filterIsInstance<OrigineFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    AniPlayFiltersData.origine.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString(",")

        val studio: String = filters.filterIsInstance<StudioFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    AniPlayFiltersData.studio.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString(",")

        return FilterSearchParams(
            filters.asQueryPart<OrdinaFilter>(),
            genere,
            tipologia,
            stato,
            origine,
            studio,
            filters.asQueryPart<InizioFilter>(),
            filters.asQueryPart<FineFilter>(),
            filters.asQueryPart<AnniFilter>(),
            filters.asQueryPart<StagioneFilter>(),
        )
    }

    private object AniPlayFiltersData {
        val all = Pair("All", "")

        val ordina = arrayOf(
            Pair("Popolarità decrescente", "views,desc"),
            Pair("Popolarità crescente", "views,asc"),
            Pair("Titolo decrescente", "title,desc"),
            Pair("Titolo crescente", "title,asc"),
            Pair("Numero episodi decrescente", "episodeNumber,desc"),
            Pair("Numero episodi crescente", "episodeNumber,asc"),
            Pair("Data di inizio decrescente", "startDate,desc"),
            Pair("Data di inizio crescente", "startDate,asc"),
            Pair("Data di fine decrescente", "endDate,desc"),
            Pair("Data di fine crescente", "endDate,asc"),
            Pair("Data di fine aggiunta", "createdDate,desc"),
            Pair("Data di fine aggiunta", "createdDate,asc"),
        )

        val genere = arrayOf(
            Pair("Arti marziali", "68"),
            Pair("Automobilismo", "90"),
            Pair("Avventura", "25"),
            Pair("Azione", "38"),
            Pair("Boys Love", "104"),
            Pair("Combattimento", "64"),
            Pair("Commedia", "37"),
            Pair("Cucina", "103"),
            Pair("Demenziale", "66"),
            Pair("Demoni", "53"),
            Pair("Drammatico", "31"),
            Pair("Ecchi", "47"),
            Pair("Fantasy", "26"),
            Pair("Giallo", "102"),
            Pair("Gioco", "55"),
            Pair("Guerra", "63"),
            Pair("Harem", "46"),
            Pair("Horror", "29"),
            Pair("Isekai", "76"),
            Pair("Josei", "101"),
            Pair("Magia", "41"),
            Pair("Mecha", "45"),
            Pair("Militare", "84"),
            Pair("Mistero", "27"),
            Pair("Musica", "94"),
            Pair("Parodia", "75"),
            Pair("Politica", "59"),
            Pair("Poliziesco", "33"),
            Pair("Psicologico", "28"),
            Pair("Reverse-harem", "80"),
            Pair("Romantico", "43"),
            Pair("Samurai", "95"),
            Pair("Sci-Fi", "69"),
            Pair("Scolastico", "32"),
            Pair("Seinen", "49"),
            Pair("Sentimentale", "36"),
            Pair("Shoujo", "60"),
            Pair("Shoujo Ai", "70"),
            Pair("Shounen", "48"),
            Pair("Shounen Ai", "99"),
            Pair("Slice of Life", "30"),
            Pair("Sovrannaturale", "34"),
            Pair("Spaziale", "87"),
            Pair("Splatter", "56"),
            Pair("Sport", "74"),
            Pair("Storico", "52"),
            Pair("Superpoteri", "96"),
            Pair("Thriller", "35"),
            Pair("Vampiri", "67"),
            Pair("Videogame", "85"),
            Pair("Yaoi", "51"),
            Pair("Yuri", "50"),
        )

        val tipologia = arrayOf(
            Pair("Movie", "2"),
            Pair("ONA", "4"),
            Pair("OVA", "3"),
            Pair("Serie", "1"),
            Pair("Special", "5"),
        )

        val stato = arrayOf(
            Pair("Annunciato", "4"),
            Pair("Completato", "1"),
            Pair("In corso", "2"),
            Pair("Non rilasciato", "5"),
            Pair("Sospeso", "3"),
        )

        val origine = arrayOf(
            Pair("Gioco di carte", "1"),
            Pair("Light novel", "2"),
            Pair("Mange", "3"),
            Pair("Movie", "4"),
            Pair("Musicale", "10"),
            Pair("Novel", "6"),
            Pair("Storia originale", "9"),
            Pair("Videogioco", "7"),
            Pair("Visual novel", "8"),
            Pair("Web Manga", "11"),
            Pair("Web Novel", "12"),
        )

        val studio = arrayOf(
            Pair("2:10 AM Animation", "435"),
            Pair("8bit", "183"),
            Pair("A-1 Picture", "167"),
            Pair("A-Real", "468"),
            Pair("A.C.G.T.", "258"),
            Pair("Acca Effe", "549"),
            Pair("Actas", "367"),
            Pair("AIC", "267"),
            Pair("AIC ASTA", "381"),
            Pair("AIC Build", "220"),
            Pair("AIC Classic", "306"),
            Pair("AIC Plus+", "198"),
            Pair("AIC Spirits", "356"),
            Pair("Ajia-Do", "212"),
            Pair("Akatsuki", "582"),
            Pair("Albacrow", "553"),
            Pair("ANIK", "362"),
            Pair("Anima&Co", "502"),
            Pair("Animation Planet", "500"),
            Pair("Animax", "318"),
            Pair("Anpro", "423"),
            Pair("APPP", "495"),
            Pair("AQUA ARIS", "533"),
            Pair("ARECT", "574"),
            Pair("Arms", "205"),
            Pair("Artland", "263"),
            Pair("Arvo Animation", "521"),
            Pair("Asahi Production", "394"),
            Pair("Ashi Production", "604"),
            Pair("ASK Animation Studio", "592"),
            Pair("Asread", "257"),
            Pair("Atelier Pontdarc", "595"),
            Pair("AtelierPontdarc", "572"),
            Pair("AXsiZ", "251"),
            Pair("B&T", "441"),
            Pair("B.CMAY PICTURES", "526"),
            Pair("Bakken Record", "555"),
            Pair("Bandai Namco Pictures", "324"),
            Pair("Barnum Studio", "436"),
            Pair("Bee Media", "567"),
            Pair("Bee Train", "287"),
            Pair("BeSTACK", "266"),
            Pair("Bibury Animation Studios", "369"),
            Pair("Big FireBird Animation", "557"),
            Pair("blade", "347"),
            Pair("Bones", "193"),
            Pair("Bouncy", "416"),
            Pair("Brain's Base", "189"),
            Pair("Bridge", "268"),
            Pair("Buemon", "518"),
            Pair("Bushiroad", "539"),
            Pair("C-Station", "253"),
            Pair("C2C", "398"),
            Pair("Chaos Project", "537"),
            Pair("Charaction", "552"),
            Pair("Children's Playground Entertainment", "509"),
            Pair("Chippai", "483"),
            Pair("CLAP", "588"),
            Pair("CloverWorks", "237"),
            Pair("Colored Pencil Animation", "570"),
            Pair("CoMix Wave Films", "265"),
            Pair("Connect", "430"),
            Pair("Craftar Studios", "377"),
            Pair("Creators in Pack", "269"),
            Pair("CyberConnect2", "563"),
            Pair("CygamesPictures", "511"),
            Pair("DandeLion Animation Studio", "333"),
            Pair("Daume", "316"),
            Pair("David Production", "254"),
            Pair("De Mas & Partners", "560"),
            Pair("Diomedea", "192"),
            Pair("DLE", "387"),
            Pair("DMM pictures", "538"),
            Pair("DMM.futureworks", "525"),
            Pair("Doga Kobo", "225"),
            Pair("domerica", "599"),
            Pair("DR Movie", "328"),
            Pair("Drive", "503"),
            Pair("drop", "357"),
            Pair("Dynamo Pictures", "507"),
            Pair("EKACHI EPILKA", "382"),
            Pair("Emon Animation Company", "380"),
            Pair("Emon, Blade", "348"),
            Pair("EMTÂ²", "274"),
            Pair("Encourage Films", "312"),
            Pair("ENGI", "522"),
            Pair("EXNOA", "575"),
            Pair("Ezo'la", "207"),
            Pair("Fanworks", "343"),
            Pair("feel.", "247"),
            Pair("Felix Film", "399"),
            Pair("Foch", "586"),
            Pair("Frederator Studios", "556"),
            Pair("Funimation", "313"),
            Pair("G&G Entertainment", "456"),
            Pair("G-angle", "497"),
            Pair("G.CMay Animation & Film", "365"),
            Pair("Gaina", "336"),
            Pair("Gainax", "216"),
            Pair("Gainax Kyoto", "501"),
            Pair("Gallop", "486"),
            Pair("Gambit", "573"),
            Pair("Gathering", "401"),
            Pair("GEEK TOYS", "412"),
            Pair("Genco", "418"),
            Pair("Geno Studio", "223"),
            Pair("GoHands", "213"),
            Pair("Gonzo", "181"),
            Pair("Graphinica", "255"),
            Pair("Gravity Well", "603"),
            Pair("GRIZZLY", "428"),
            Pair("Group TAC", "335"),
            Pair("Hal Film Maker", "323"),
            Pair("Haoliners Animation League", "262"),
            Pair("HMCH", "600"),
            Pair("Hobi Animation", "417"),
            Pair("Hoods Entertainment", "175"),
            Pair("HOTZIPANG", "505"),
            Pair("ILCA", "427"),
            Pair("IMAGICA Lab.", "459"),
            Pair("Imagin", "233"),
            Pair("Imagineer", "564"),
            Pair("Ishimori Entertainment", "485"),
            Pair("ixtl", "479"),
            Pair("J.C.Staff", "178"),
            Pair("Jinnis Animation Studios", "482"),
            Pair("Jumondo", "589"),
            Pair("Jumondou", "591"),
            Pair("Kachidoki Studio", "540"),
            Pair("Kamikaze Douga", "383"),
            Pair("Karaku", "349"),
            Pair("Kazami Gakuen Koushiki Douga-bu", "488"),
            Pair("Khara", "569"),
            Pair("Kigumi", "587"),
            Pair("Kinema Citrus", "185"),
            Pair("Kitty Films", "530"),
            Pair("KJJ Animation", "442"),
            Pair("Knack Productions", "451"),
            Pair("Kyoto Animation", "179"),
            Pair("Kyotoma", "379"),
            Pair("LandQ studios", "342"),
            Pair("Lapin Track", "583"),
            Pair("Larx Entertainment", "397"),
            Pair("Lay-duce", "239"),
            Pair("Lerche", "195"),
            Pair("Lesprit", "462"),
            Pair("LICO", "545"),
            Pair("LIDENFILMS", "311"),
            Pair("Lilix", "403"),
            Pair("M.S.C", "390"),
            Pair("Madhouse", "165"),
            Pair("Magic Bus", "484"),
            Pair("Maho Film", "375"),
            Pair("Manglobe", "199"),
            Pair("Manpuku Jinja", "331"),
            Pair("MAPPA", "182"),
            Pair("Marvy Jack", "597"),
            Pair("MASTER LIGHTS", "469"),
            Pair("Mili Pictures", "585"),
            Pair("Millepensee", "321"),
            Pair("monofilmo", "411"),
            Pair("Movic", "338"),
            Pair("Mushi Production", "506"),
            Pair("Namu Animation", "314"),
            Pair("NAZ", "206"),
            Pair("Nexus", "359"),
            Pair("Nippon Animation", "388"),
            Pair("Nippon Columbia", "226"),
            Pair("Nomad", "332"),
            Pair("Non menzionato", "392"),
            Pair("Nut", "235"),
            Pair("Oddjob", "374"),
            Pair("Office DCI", "425"),
            Pair("Okuruto Noboru", "548"),
            Pair("OLM", "320"),
            Pair("Orange", "256"),
            Pair("Ordet", "433"),
            Pair("Oz", "580"),
            Pair("P.A.Works", "169"),
            Pair("Palm Studio", "494"),
            Pair("Paper Plane Animation Studio", "571"),
            Pair("Passione", "202"),
            Pair("Pastel", "566"),
            Pair("Pb Animation", "598"),
            Pair("Pierrot Plus", "187"),
            Pair("Pine Jam", "218"),
            Pair("Planet", "447"),
            Pair("Platinum Vision", "249"),
            Pair("Plum", "368"),
            Pair("Polygon Pictures", "186"),
            Pair("PRA", "480"),
            Pair("Primastea", "420"),
            Pair("PrimeTime", "363"),
            Pair("Production +h.", "584"),
            Pair("production doA", "238"),
            Pair("Production I.G", "191"),
            Pair("Production IMS", "273"),
            Pair("Production Reed", "211"),
            Pair("Project No.9", "264"),
            Pair("Quad", "581"),
            Pair("Qualia Animation", "463"),
            Pair("Qubic Pictures", "559"),
            Pair("Radix", "371"),
            Pair("Rainbow", "499"),
            Pair("Revoroot", "431"),
            Pair("Rising Force", "487"),
            Pair("Rooster Teeth", "461"),
            Pair("Saetta", "272"),
            Pair("SANZIGEN", "340"),
            Pair("Satelight", "197"),
            Pair("Science SARU", "201"),
            Pair("Scooter Films", "590"),
            Pair("Sentai Filmworks", "408"),
            Pair("Seven", "229"),
            Pair("Seven Arcs", "351"),
            Pair("Seven Arcs Pictures", "352"),
            Pair("Seven Stone Entertainment", "422"),
            Pair("Shaft", "203"),
            Pair("Shanghai Foch Film Culture Investmen", "445"),
            Pair("Shin-Ei Animation", "215"),
            Pair("Shirogumi", "413"),
            Pair("Shuka", "177"),
            Pair("Signal.MD", "400"),
            Pair("Silver Link", "194"),
            Pair("Sola Digital Arts", "576"),
            Pair("Square-Enix", "337"),
            Pair("Staple Entertainment", "593"),
            Pair("Studio 3Hz", "252"),
            Pair("Studio 4Â°C", "344"),
            Pair("Studio A-CAT", "373"),
            Pair("Studio Animal", "271"),
            Pair("Studio Bind", "547"),
            Pair("Studio Blanc", "330"),
            Pair("Studio Chizu", "424"),
            Pair("Studio Colorido", "498"),
            Pair("Studio Comet", "329"),
            Pair("Studio Daisy", "578"),
            Pair("Studio Deen", "204"),
            Pair("Studio Elle", "520"),
            Pair("Studio Fantasia", "407"),
            Pair("Studio Flad", "327"),
            Pair("Studio Gallop", "334"),
            Pair("Studio Ghibli", "279"),
            Pair("Studio Gokumi", "250"),
            Pair("Studio Hibari", "492"),
            Pair("Studio Hokiboshi", "524"),
            Pair("Studio Kafka", "568"),
            Pair("Studio Kai", "558"),
            Pair("Studio LAN", "434"),
            Pair("Studio Live", "542"),
            Pair("Studio M2", "577"),
            Pair("Studio Mir", "601"),
            Pair("studio MOTHER", "596"),
            Pair("Studio Pierrot", "168"),
            Pair("Studio Ponoc", "546"),
            Pair("Studio PuYUKAI", "244"),
            Pair("Studio Rikka", "460"),
            Pair("Studio Signpost", "510"),
            Pair("Studio Voln", "240"),
            Pair("Studio! Cucuri", "414"),
            Pair("Sublimation", "532"),
            Pair("Success", "602"),
            Pair("Suiseisha", "457"),
            Pair("Sunrise", "190"),
            Pair("Sunrise Beyond", "515"),
            Pair("SynergySP", "364"),
            Pair("TAKI Corporation", "475"),
            Pair("Tatsunoko Production", "227"),
            Pair("Team TillDawn", "535"),
            Pair("Team YokkyuFuman", "496"),
            Pair("Tear Studio", "561"),
            Pair("Telecom Animation Film", "228"),
            Pair("Tencent Animation & Comics", "467"),
            Pair("Tencent Penguin Pictures", "579"),
            Pair("Tezuka Productions", "241"),
            Pair("Think Corporation", "528"),
            Pair("Thundray", "471"),
            Pair("TMS Entertainment", "214"),
            Pair("TNK", "208"),
            Pair("Toei Animation", "224"),
            Pair("Tokyo Kids", "366"),
            Pair("Tokyo Movie Shinsha", "410"),
            Pair("Tomason", "554"),
            Pair("Topcraft", "565"),
            Pair("Trans Arts", "432"),
            Pair("Triangle Staff", "231"),
            Pair("TriF Studio", "440"),
            Pair("Trigger", "200"),
            Pair("Trinet Entertainment", "421"),
            Pair("TROYCA", "230"),
            Pair("Tsuchida Productions", "562"),
            Pair("Twilight Studio", "481"),
            Pair("TYO Animations", "236"),
            Pair("Typhoon Graphics", "465"),
            Pair("ufotable", "166"),
            Pair("Urban Production", "376"),
            Pair("UWAN Pictures", "385"),
            Pair("VAP", "319"),
            Pair("Vega Entertainment", "361"),
            Pair("Visual Flight", "550"),
            Pair("W-Toon Studio", "402"),
            Pair("Wao world", "284"),
            Pair("Wawayu Animation", "358"),
            Pair("White Fox", "161"),
            Pair("Wit Studio", "176"),
            Pair("WolfsBane", "523"),
            Pair("Xebec", "196"),
            Pair("Yaoyorozu", "396"),
            Pair("Yokohama Animation Lab", "426"),
            Pair("Yostar Pictures", "551"),
            Pair("Yumeta Company", "222"),
            Pair("Zero-G", "242"),
            Pair("Zexcs", "259"),
        )

        val stagione = arrayOf(
            all,
            Pair("Inverno", "winter"),
            Pair("Primavera", "spring"),
            Pair("Estate", "summer"),
            Pair("Autunno", "fall"),
        )

        val anni = arrayOf(all) + (1984..2023).map {
            Pair(it.toString(), it.toString())
        }.reversed().toTypedArray()
    }
}
