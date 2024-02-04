package eu.kanade.tachiyomi.animeextension.it.aniplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AniPlayFilters {
    open class SelectFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        val selected get() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.getSelected(): String {
        return (first { it is R } as SelectFilter).selected
    }

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first) })

    private class CheckBoxVal(name: String) : AnimeFilter.CheckBox(name, false)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): String {
        return (first { it is R } as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .joinToString(",")
    }

    internal class OrderFilter : SelectFilter("Ordina per", ORDER_LIST)

    internal class GenreFilter : CheckBoxFilterList("Generi", GENRE_LIST)
    internal class CountryFilter : CheckBoxFilterList("Paesi", COUNTRY_LIST)
    internal class TypeFilter : CheckBoxFilterList("Tipi", TYPE_LIST)
    internal class StudioFilter : CheckBoxFilterList("Studio", STUDIO_LIST)
    internal class StatusFilter : CheckBoxFilterList("Stato", STATUS_LIST)
    internal class LanguageFilter : CheckBoxFilterList("Lingua", LANGUAGE_LIST)

    internal val FILTER_LIST get() = AnimeFilterList(
        OrderFilter(),

        GenreFilter(),
        CountryFilter(),
        TypeFilter(),
        StudioFilter(),
        StatusFilter(),
        LanguageFilter(),
    )

    internal data class FilterSearchParams(
        val order: String = "1",
        val genres: String = "",
        val countries: String = "",
        val types: String = "",
        val studios: String = "",
        val status: String = "",
        val languages: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.getSelected<OrderFilter>(),
            filters.parseCheckbox<GenreFilter>(GENRE_LIST),
            filters.parseCheckbox<CountryFilter>(COUNTRY_LIST),
            filters.parseCheckbox<TypeFilter>(TYPE_LIST),
            filters.parseCheckbox<StudioFilter>(STUDIO_LIST),
            filters.parseCheckbox<StatusFilter>(STATUS_LIST),
            filters.parseCheckbox<LanguageFilter>(LANGUAGE_LIST),
        )
    }

    private val GENRE_LIST = arrayOf(
        Pair("Arti marziali", "35"),
        Pair("Automobilismo", "49"),
        Pair("Avventura", "3"),
        Pair("Azione", "7"),
        Pair("Boys Love", "52"),
        Pair("Combattimento", "27"),
        Pair("Commedia", "13"),
        Pair("Cucina", "38"),
        Pair("Demenziale", "32"),
        Pair("Demoni", "26"),
        Pair("Drammatico", "2"),
        Pair("Ecchi", "21"),
        Pair("Fantasy", "1"),
        Pair("Giallo", "34"),
        Pair("Gioco", "31"),
        Pair("Guerra", "39"),
        Pair("Harem", "30"),
        Pair("Horror", "14"),
        Pair("Isekai", "43"),
        Pair("Josei", "47"),
        Pair("Magia", "18"),
        Pair("Mecha", "25"),
        Pair("Militare", "23"),
        Pair("Mistero", "5"),
        Pair("Musica", "40"),
        Pair("Parodia", "42"),
        Pair("Politica", "24"),
        Pair("Poliziesco", "29"),
        Pair("Psicologico", "6"),
        Pair("Reverse-harem", "45"),
        Pair("Romantico", "8"),
        Pair("Samurai", "36"),
        Pair("Sci-Fi", "20"),
        Pair("Scolastico", "10"),
        Pair("Seinen", "28"),
        Pair("Sentimentale", "12"),
        Pair("Shoujo", "11"),
        Pair("Shoujo Ai", "37"),
        Pair("Shounen", "16"),
        Pair("Shounen Ai", "51"),
        Pair("Slice of Life", "19"),
        Pair("Sovrannaturale", "22"),
        Pair("Spaziale", "48"),
        Pair("Splatter", "15"),
        Pair("Sport", "41"),
        Pair("Storico", "17"),
        Pair("Superpoteri", "9"),
        Pair("Thriller", "4"),
        Pair("Vampiri", "33"),
        Pair("Videogame", "44"),
        Pair("Yaoi", "50"),
        Pair("Yuri", "46"),
    )

    private val COUNTRY_LIST = arrayOf(
        Pair("Corea del Sud", "KR"),
        Pair("Cina", "CN"),
        Pair("Hong Kong", "HK"),
        Pair("Filippine", "PH"),
        Pair("Giappone", "JP"),
        Pair("Taiwan", "TW"),
        Pair("Thailandia", "TH"),
    )

    private val TYPE_LIST = arrayOf(
        Pair("Serie", "1"),
        Pair("Movie", "2"),
        Pair("OVA", "3"),
        Pair("ONA", "4"),
        Pair("Special", "5"),
    )

    private val STUDIO_LIST = arrayOf(
        Pair("2:10 AM Animation", "190"),
        Pair("5 Inc.", "309"),
        Pair("8bit", "17"),
        Pair("A-1 Picture", "11"),
        Pair("Acca Effe", "180"),
        Pair("A.C.G.T.", "77"),
        Pair("Actas", "153"),
        Pair("AIC ASTA", "150"),
        Pair("AIC Build", "46"),
        Pair("AIC Classic", "99"),
        Pair("AIC Plus+", "26"),
        Pair("AIC Spirits", "128"),
        Pair("Ajia-Do", "39"),
        Pair("Akatsuki", "289"),
        Pair("Albacrow", "229"),
        Pair("Anima&Co", "161"),
        Pair("Animation Planet", "224"),
        Pair("Animax", "103"),
        Pair("Anpro", "178"),
        Pair("APPP", "220"),
        Pair("AQUA ARIS", "245"),
        Pair("A-Real", "211"),
        Pair("ARECT", "273"),
        Pair("Arms", "33"),
        Pair("Artland", "81"),
        Pair("Arvo Animation", "239"),
        Pair("Asahi Production", "160"),
        Pair("Ashi Production", "307"),
        Pair("ASK Animation Studio", "296"),
        Pair("Asread", "76"),
        Pair("Atelier Pontdarc", "298"),
        Pair("AtelierPontdarc", "271"),
        Pair("AXsiZ", "70"),
        Pair("Bakken Record", "195"),
        Pair("Bandai Namco Pictures", "108"),
        Pair("Barnum Studio", "191"),
        Pair("B.CMAY PICTURES", "135"),
        Pair("Bee Media", "262"),
        Pair("Bee Train", "98"),
        Pair("Bibury Animation Studios", "139"),
        Pair("Big FireBird Animation", "141"),
        Pair("blade", "212"),
        Pair("Bones", "22"),
        Pair("Bouncy", "174"),
        Pair("Brain's Base", "18"),
        Pair("Bridge", "88"),
        Pair("B&T", "193"),
        Pair("Buemon", "236"),
        Pair("BUG FILMS", "314"),
        Pair("Bushiroad", "249"),
        Pair("C2C", "126"),
        Pair("Chaos Project", "247"),
        Pair("Charaction", "250"),
        Pair("Children's Playground Entertainment", "184"),
        Pair("CLAP", "292"),
        Pair("CloverWorks", "51"),
        Pair("Colored Pencil Animation", "268"),
        Pair("CoMix Wave Films", "83"),
        Pair("Connect", "185"),
        Pair("Craftar Studios", "146"),
        Pair("Creators in Pack", "84"),
        Pair("C-Station", "72"),
        Pair("CyberConnect2", "217"),
        Pair("CygamesPictures", "233"),
        Pair("DandeLion Animation Studio", "116"),
        Pair("Daume", "102"),
        Pair("David Production", "73"),
        Pair("De Mas & Partners", "207"),
        Pair("Diomedea", "21"),
        Pair("DLE", "155"),
        Pair("DMM.futureworks", "241"),
        Pair("DMM pictures", "248"),
        Pair("Doga Kobo", "50"),
        Pair("domerica", "302"),
        Pair("Drive", "226"),
        Pair("DR Movie", "113"),
        Pair("drop", "130"),
        Pair("Dynamo Pictures", "231"),
        Pair("E&H Production", "333"),
        Pair("EKACHI EPILKA", "151"),
        Pair("Emon Animation Company", "149"),
        Pair("Emon, Blade", "123"),
        Pair("EMTÂ²", "90"),
        Pair("Encourage Films", "100"),
        Pair("ENGI", "158"),
        Pair("evg", "322"),
        Pair("EXNOA", "274"),
        Pair("Ezo'la", "35"),
        Pair("Fanworks", "121"),
        Pair("feel.", "37"),
        Pair("Felix Film", "163"),
        Pair("Frederator Studios", "147"),
        Pair("Fugaku", "326"),
        Pair("Funimation", "106"),
        Pair("Gainax", "43"),
        Pair("Gainax Kyoto", "225"),
        Pair("Gallop", "109"),
        Pair("Gambit", "272"),
        Pair("G-angle", "222"),
        Pair("Garden Culture", "324"),
    )

    private val STATUS_LIST = arrayOf(
        Pair("Completato", "1"),
        Pair("In corso", "2"),
        Pair("Sospeso", "3"),
        Pair("Annunciato", "4"),
        Pair("Non rilasciato", "5"),
    )

    private val LANGUAGE_LIST = arrayOf(
        Pair("Doppiato", "2"),
        Pair("RAW", "3"),
        Pair("Sottotitolato", "1"),
    )

    private val ORDER_LIST = arrayOf(
        Pair("Rilevanza", "1"),
        Pair("Modificato di recente", "2"),
        Pair("Aggiunto di recente", "3"),
        Pair("Data di rilascio", "4"),
        Pair("Nome", "5"),
        Pair("Voto", "6"),
        Pair("Visualizzazioni", "7"),
        Pair("Episodi", "8"),
        Pair("Casuale", "9"),
    )
}
