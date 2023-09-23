package eu.kanade.tachiyomi.animeextension.de.animebase

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeBaseFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
    }

    class YearFilter : AnimeFilter.Text("Erscheinungsjahr")

    class LanguagesFilter : CheckBoxFilterList("Sprache", AnimeBaseFiltersData.LANGUAGES)
    class GenresFilter : CheckBoxFilterList("Genre", AnimeBaseFiltersData.GENRES)

    class ListFilter : QueryPartFilter("Liste der Konten", AnimeBaseFiltersData.LISTS)
    class LetterFilter : QueryPartFilter("Schreiben", AnimeBaseFiltersData.LETTERS)

    val FILTER_LIST get() = AnimeFilterList(
        YearFilter(),
        LanguagesFilter(),
        GenresFilter(),
        AnimeFilter.Separator(),
        // >imagine using deepL
        AnimeFilter.Header("Die untenstehenden Filter ignorieren die textsuche!"),
        ListFilter(),
        LetterFilter(),
    )

    data class FilterSearchParams(
        val year: String = "",
        val languages: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val list: String = "",
        val letter: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.getFirst<YearFilter>().state,
            filters.parseCheckbox<LanguagesFilter>(AnimeBaseFiltersData.LANGUAGES),
            filters.parseCheckbox<GenresFilter>(AnimeBaseFiltersData.GENRES),
            filters.asQueryPart<ListFilter>(),
            filters.asQueryPart<LetterFilter>(),
        )
    }

    private object AnimeBaseFiltersData {
        val LANGUAGES = arrayOf(
            Pair("German Sub", "0"), // Literally Jmir
            Pair("German Dub", "1"),
            Pair("English Sub", "2"), // Average bri'ish
            Pair("English Dub", "3"),
        )

        val GENRES = arrayOf(
            Pair("Abenteuer", "1"),
            Pair("Abenteuerkomödie", "261"),
            Pair("Action", "2"),
            Pair("Actiondrama", "3"),
            Pair("Actionkomödie", "4"),
            Pair("Adeliger", "258"),
            Pair("Airing", "59"),
            Pair("Alltagsdrama", "6"),
            Pair("Alltagsleben", "7"),
            Pair("Ältere Frau, jüngerer Mann", "210"),
            Pair("Älterer Mann, jüngere Frau", "222"),
            Pair("Alternative Welt", "53"),
            Pair("Altes Asien", "187"),
            Pair("Animation", "193"),
            Pair("Anime & Film", "209"),
            Pair("Anthologie", "260"),
            Pair("Auftragsmörder / Attentäter", "265"),
            Pair("Außerirdische", "204"),
            Pair("Badminton", "259"),
            Pair("Band", "121"),
            Pair("Baseball", "234"),
            Pair("Basketball", "239"),
            Pair("Bionische Kräfte", "57"),
            Pair("Boxen", "218"),
            Pair("Boys Love", "226"),
            Pair("Büroangestellter", "248"),
            Pair("CG-Anime", "81"),
            Pair("Charakterschwache Heldin", "102"),
            Pair("Charakterschwacher Held", "101"),
            Pair("Charakterstarke Heldin", "100"),
            Pair("Charakterstarker Held", "88"),
            Pair("Cyberpunk", "60"),
            Pair("Cyborg", "109"),
            Pair("Dämon", "58"),
            Pair("Delinquent", "114"),
            Pair("Denk- und Glücksspiele", "227"),
            Pair("Detektiv", "91"),
            Pair("Dialogwitz", "93"),
            Pair("Dieb", "245"),
            Pair("Diva", "112"),
            Pair("Donghua", "257"),
            Pair("Drache", "263"),
            Pair("Drama", "8"),
            Pair("Dunkle Fantasy", "90"),
            Pair("Ecchi", "9"),
            Pair("Elf", "89"),
            Pair("Endzeit", "61"),
            Pair("Epische Fantasy", "95"),
            Pair("Episodisch", "92"),
            Pair("Erotik", "186"),
            Pair("Erwachsen", "70"),
            Pair("Erwachsenwerden", "125"),
            Pair("Essenszubereitung", "206"),
            Pair("Familie", "63"),
            Pair("Fantasy", "11"),
            Pair("Fee", "264"),
            Pair("Fighting-Shounen", "12"),
            Pair("Football", "241"),
            Pair("Frühe Neuzeit", "113"),
            Pair("Fußball", "220"),
            Pair("Gaming – Kartenspiele", "250"),
            Pair("Ganbatte", "13"),
            Pair("Gedächtnisverlust", "115"),
            Pair("Gegenwart", "46"),
            Pair("Geist", "75"),
            Pair("Geistergeschichten", "14"),
            Pair("Gender Bender", "216"),
            Pair("Genie", "116"),
            Pair("Girls Love", "201"),
            Pair("Grundschule", "103"),
            Pair("Harem", "15"),
            Pair("Hentai", "16"),
            Pair("Hexe", "97"),
            Pair("Himmlische Wesen", "105"),
            Pair("Historisch", "49"),
            Pair("Horror", "17"),
            Pair("Host-Club", "247"),
            Pair("Idol", "122"),
            Pair("In einem Raumschiff", "208"),
            Pair("Independent Anime", "251"),
            Pair("Industrialisierung", "230"),
            Pair("Isekai", "120"),
            Pair("Kami", "98"),
            Pair("Kampfkunst", "246"),
            Pair("Kampfsport", "79"),
            Pair("Kemonomimi", "106"),
            Pair("Kinder", "41"),
            Pair("Kindergarten", "243"),
            Pair("Klubs", "189"),
            Pair("Kodomo", "40"),
            Pair("Komödie", "18"),
            Pair("Kopfgeldjäger", "211"),
            Pair("Krieg", "68"),
            Pair("Krimi", "19"),
            Pair("Liebesdrama", "20"),
            Pair("Mafia", "127"),
            Pair("Magical Girl", "21"),
            Pair("Magie", "52"),
            Pair("Maid", "244"),
            Pair("Malerei", "231"),
            Pair("Manga & Doujinshi", "217"),
            Pair("Mannschaftssport", "262"),
            Pair("Martial Arts", "64"),
            Pair("Mecha", "22"),
            Pair("Mediziner", "238"),
            Pair("Mediziner", "254"),
            Pair("Meiji-Ära", "242"),
            Pair("Militär", "62"),
            Pair("Mittelalter", "76"),
            Pair("Mittelschule", "190"),
            Pair("Moe", "43"),
            Pair("Monster", "54"),
            Pair("Musik", "69"),
            Pair("Mystery", "23"),
            Pair("Ninja", "55"),
            Pair("Nonsense-Komödie", "24"),
            Pair("Oberschule", "83"),
            Pair("Otaku", "215"),
            Pair("Parodie", "94"),
            Pair("Pirat", "252"),
            Pair("Polizist", "84"),
            Pair("PSI-Kräfte", "78"),
            Pair("Psychodrama", "25"),
            Pair("Real Robots", "212"),
            Pair("Rennsport", "207"),
            Pair("Ritter", "50"),
            Pair("Roboter ", "73"),
            Pair("Roboter & Android", "110"),
            Pair("Romantische Komödie", "26"),
            Pair("Romanze", "27"),
            Pair("Samurai", "47"),
            Pair("Satire", "232"),
            Pair("Schule", "119"),
            Pair("Schusswaffen", "82"),
            Pair("Schwerter & Co", "51"),
            Pair("Schwimmen", "223"),
            Pair("Scifi", "28"),
            Pair("Seinen", "39"),
            Pair("Sentimentales Drama", "29"),
            Pair("Shounen", "37"),
            Pair("Slapstick", "56"),
            Pair("Slice of Life", "5"),
            Pair("Solosänger", "219"),
            Pair("Space Opera", "253"),
            Pair("Splatter", "36"),
            Pair("Sport", "30"),
            Pair("Stoische Heldin", "123"),
            Pair("Stoischer Held", "85"),
            Pair("Super Robots", "203"),
            Pair("Super-Power", "71"),
            Pair("Superhelden", "256"),
            Pair("Supernatural", "225"),
            Pair("Tanzen", "249"),
            Pair("Tennis", "233"),
            Pair("Theater", "224"),
            Pair("Thriller", "31"),
            Pair("Tiermensch", "111"),
            Pair("Tomboy", "104"),
            Pair("Tragödie", "86"),
            Pair("Tsundere", "107"),
            Pair("Überlebenskampf", "117"),
            Pair("Übermäßige Gewaltdarstellung", "34"),
            Pair("Unbestimmt", "205"),
            Pair("Universität", "214"),
            Pair("Vampir", "35"),
            Pair("Verworrene Handlung", "126"),
            Pair("Virtuelle Welt", "108"),
            Pair("Volleyball", "191"),
            Pair("Volljährig", "67"),
            Pair("Wassersport", "266"),
            Pair("Weiblich", "45"),
            Pair("Weltkriege", "128"),
            Pair("Weltraum", "74"),
            Pair("Widerwillige Heldin", "124"),
            Pair("Widerwilliger Held", "188"),
            Pair("Yandere", "213"),
            Pair("Yaoi", "32"),
            Pair("Youkai", "99"),
            Pair("Yuri", "33"),
            Pair("Zeichentrick", "77"),
            Pair("Zeichentrick", "255"),
            Pair("Zeitgenössische Fantasy", "80"),
            Pair("Zeitsprung", "240"),
            Pair("Zombie", "87"),
        )

        val LISTS = arrayOf(
            Pair("Keine", ""),
            Pair("Anime", "animelist"),
            Pair("Film", "filmlist"),
            Pair("Hentai", "hentailist"),
            Pair("Sonstiges", "misclist"),
        )

        val LETTERS = arrayOf(Pair("Jede", "")) + ('A'..'Z').map {
            Pair(it.toString(), "/$it")
        }.toTypedArray()
    }
}
