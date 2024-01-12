package eu.kanade.tachiyomi.animeextension.en.animetake

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeTakeFilters {
    open class CheckBoxFilterList(name: String, pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) :
        AnimeFilter.CheckBox(name, state)

    inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .joinToString("&") { "$name[]=$it" }
    }

    internal class LetterFilter : CheckBoxFilterList("Letter", AnimeTakeFiltersData.LETTER)
    internal class GenresFilter : CheckBoxFilterList("Genre", AnimeTakeFiltersData.GENRE)
    internal class ScoreFilter : CheckBoxFilterList("Score", AnimeTakeFiltersData.SCORE)
    internal class YearFilter : CheckBoxFilterList("Year", AnimeTakeFiltersData.YEAR)
    internal class RatingFilter : CheckBoxFilterList("Rating", AnimeTakeFiltersData.RATING)

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("Note: Ignores search"),
            LetterFilter(),
            GenresFilter(),
            ScoreFilter(),
            YearFilter(),
            RatingFilter(),
        )

    internal data class FilterSearchParams(
        val letters: String = "",
        val genres: String = "",
        val score: String = "",
        val years: String = "",
        val ratings: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<LetterFilter>(AnimeTakeFiltersData.LETTER, "letters"),
            filters.parseCheckbox<GenresFilter>(AnimeTakeFiltersData.GENRE, "genres"),
            filters.parseCheckbox<ScoreFilter>(AnimeTakeFiltersData.SCORE, "score"),
            filters.parseCheckbox<YearFilter>(AnimeTakeFiltersData.YEAR, "years"),
            filters.parseCheckbox<RatingFilter>(AnimeTakeFiltersData.RATING, "ratings"),
        )
    }

    private object AnimeTakeFiltersData {
        val LETTER = ('A'..'Z').map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRE = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Chinese", "Chinese"),
            Pair("Comedy", "Comedy"),
            Pair("Detective", "Detective"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Gourmet", "Gourmet"),
            Pair("Harem", "Harem"),
            Pair("High Stakes Game", "High+Stakes+Game"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Iyashikei", "Iyashikei"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Magic", "Magic"),
            Pair("Martial Arts", "Martial+Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Mythology", "Mythology"),
            Pair("Parody", "Parody"),
            Pair("Psychological", "Psychological"),
            Pair("Racing", "Racing"),
            Pair("Reincarnation", "Reincarnation"),
            Pair("Romance", "Romance"),
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shoujo Ai", "Shoujo+Ai"),
            Pair("Shounen", "Shounen"),
            Pair("Shounen Ai", "Shounen+Ai"),
            Pair("Slice of Life", "Slice+of+Life"),
            Pair("Space", "Space"),
            Pair("Sports", "Sports"),
            Pair("Strategy Game", "Strategy+Game"),
            Pair("Super Power", "Super+Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Survival", "Survival"),
            Pair("Suspense", "Suspense"),
            Pair("Team Sports", "Team+Sports"),
            Pair("Time Travel", "Time+Travel"),
            Pair("Vampire", "Vampire"),
            Pair("Video Game", "Video+Game"),
        )

        val SCORE = arrayOf(
            Pair("Masterpiece (9+)", "masterpiece"),
            Pair("Fantastic (8+)", "fantastic"),
            Pair("Very Good+(7+)", "verygood"),
            Pair("Fine (6+)", "fine"),
            Pair("Average (5+)", "average"),
            Pair("Bad (4+)", "bad"),
            Pair("Very Bad (3+)", "verybad"),
            Pair("Unwatchable (2+)", "unwatchable"),
        )

        val YEAR = arrayOf(
            Pair("2024", "2024"),
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
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1990-1999", "1990"),
            Pair("1980-1989", "1980"),
            Pair("1970-1979", "1970"),
        )

        val RATING = arrayOf(
            Pair("G - All Ages", "allages"),
            Pair("PG 13 - Teens 13 and Older", "pg13"),
            Pair("R - 17+, Violence & Profanity", "r17"),
            Pair("R+ - Profanity & Mild Nudity", "rplus"),
        )
    }
}
