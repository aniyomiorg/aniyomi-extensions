import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KisteFilters {
    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (first { it is R } as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .joinToString("&") { "$name[]=$it" }
    }

    class GenresFilter : CheckBoxFilterList("Genres", KisteFiltersData.GENRES)
    class TypesFilter : CheckBoxFilterList("Types", KisteFiltersData.TYPES)
    class CountriesFilter : CheckBoxFilterList("Countries", KisteFiltersData.COUNTRIES)
    class YearsFilter : CheckBoxFilterList("Jaht", KisteFiltersData.YEARS)
    class QualitiesFilter : CheckBoxFilterList("Qualitat", KisteFiltersData.QUALITIES)

    val FILTER_LIST get() = AnimeFilterList(
        GenresFilter(),
        TypesFilter(),
        CountriesFilter(),
        YearsFilter(),
        QualitiesFilter(),
    )

    data class FilterSearchParams(
        val genres: String = "",
        val types: String = "",
        val countries: String = "",
        val years: String = "",
        val qualities: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(KisteFiltersData.GENRES, "genre"),
            filters.parseCheckbox<TypesFilter>(KisteFiltersData.TYPES, "type"),
            filters.parseCheckbox<CountriesFilter>(KisteFiltersData.COUNTRIES, "country"),
            filters.parseCheckbox<YearsFilter>(KisteFiltersData.YEARS, "release"),
            filters.parseCheckbox<QualitiesFilter>(KisteFiltersData.QUALITIES, "quality"),
        )
    }

    private object KisteFiltersData {
        val GENRES = arrayOf(
            Pair("Abenteuer", "43"),
            Pair("Action", "1"),
            Pair("Adventure", "2"),
            Pair("Amerikanisch", "53"),
            Pair("Animation", "14"),
            Pair("Anime", "19"),
            Pair("Biography", "36"),
            Pair("Britisch", "54"),
            Pair("Cartoon", "55"),
            Pair("Children", "22"),
            Pair("Chinesisch", "52"),
            Pair("Comedy", "5"),
            Pair("Crime", "10"),
            Pair("Deutsch", "49"),
            Pair("Documentary", "18"),
            Pair("Dokumentarfilm", "40"),
            Pair("Drama", "9"),
            Pair("Familie", "39"),
            Pair("Family", "23"),
            Pair("Fantasy", "3"),
            Pair("Film", "4"),
            Pair("Food", "16"),
            Pair("Game Show", "17"),
            Pair("Historie", "46"),
            Pair("History", "20"),
            Pair("Horror", "15"),
            Pair("Japanisch", "50"),
            Pair("Kids", "29"),
            Pair("Kinofilme", "57"),
            Pair("Komödie", "37"),
            Pair("Koreanisch", "51"),
            Pair("Kriegsfilm", "47"),
            Pair("Krimi", "38"),
            Pair("Liebesfilm", "44"),
            Pair("Martial Arts", "24"),
            Pair("Mini-Series", "31"),
            Pair("Musical", "28"),
            Pair("Musik", "48"),
            Pair("Mystery", "11"),
            Pair("News", "45"),
            Pair("Politics", "41"),
            Pair("Reality", "26"),
            Pair("Romance", "7"),
            Pair("Sci-Fi", "34"),
            Pair("Science Fiction", "6"),
            Pair("Serie", "12"),
            Pair("Short", "25"),
            Pair("Soap", "30"),
            Pair("Sport", "33"),
            Pair("Supernatural", "27"),
            Pair("Suspense", "13"),
            Pair("Talk", "42"),
            Pair("Thriller", "8"),
            Pair("Travel", "32"),
            Pair("War", "21"),
            Pair("Western", "35"),
        )

        val TYPES = arrayOf(
            Pair("Filme", "movie"),
            Pair("TV-Serien", "series"),
        )

        val COUNTRIES = arrayOf(
            Pair("Argentinien", "AR"),
            Pair("Austrailen", "AU"),
            Pair("Belgien", "BE"),
            Pair("Brasilien", "BR"),
            Pair("China", "CN"),
            Pair("Deutschland", "DE"),
            Pair("Dänemark", "DK"),
            Pair("Finnland", "FI"),
            Pair("Frankreich", "FR"),
            Pair("Großbritannien", "GB"),
            Pair("Hong-Kong", "HK"),
            Pair("Indien", "IN"),
            Pair("Irland", "IE"),
            Pair("Israel", "IL"),
            Pair("Italien", "IT"),
            Pair("Japan", "JP"),
            Pair("Kanada", "CA"),
            Pair("Mexiko", "MX"),
            Pair("Neuseeland", "NZ"),
            Pair("Niederlanden", "NL"),
            Pair("Norwergen", "NO"),
            Pair("Philippinen", "PH"),
            Pair("Poland", "PL"),
            Pair("Rumänien", "RO"),
            Pair("Russland", "RU"),
            Pair("Schweden", "SE"),
            Pair("Schweiz", "CH"),
            Pair("Spanien", "ES"),
            Pair("Südafrika", "ZA"),
            Pair("Südkorea", "KR"),
            Pair("Thailand", "TH"),
            Pair("Tschechien", "CZ"),
            Pair("Türkei", "TR"),
            Pair("USA", "US"),
            Pair("Ungarn", "HU"),
            Pair("Österreich", "AT"),
        )

        val YEARS = arrayOf(
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2000s", "2000s"),
            Pair("1990s", "1990s"),
            Pair("1980s", "1980s"),
            Pair("1970s", "1970s"),
            Pair("1960s", "1960s"),
            Pair("1950s", "1950s"),
            Pair("1940s", "1940s"),
            Pair("1930s", "1930s"),
            Pair("1920s", "1920s"),
            Pair("1910s", "1910s"),
            Pair("1900s", "1900s"),
        )

        val QUALITIES = arrayOf(
            Pair("CAM", "CAM"),
            Pair("HD", "HD"),
            Pair("HDRip", "HDRip"),
            Pair("SD", "SD"),
            Pair("TS", "TS"),
        )
    }
}
