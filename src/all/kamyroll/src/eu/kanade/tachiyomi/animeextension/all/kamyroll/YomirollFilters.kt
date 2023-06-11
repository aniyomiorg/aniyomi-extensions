package eu.kanade.tachiyomi.animeextension.all.kamyroll

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object YomirollFilters {

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
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .mapNotNull { checkbox ->
                if (checkbox.state) {
                    options.find { it.first == checkbox.name }!!.second
                } else {
                    null
                }
            }.joinToString("")
    }

    class TypeFilter : QueryPartFilter("Type", CrunchyFiltersData.SEARCH_TYPE)
    class CategoryFilter : QueryPartFilter("Category", CrunchyFiltersData.CATEGORIES)
    class SortFilter : QueryPartFilter("Sort By", CrunchyFiltersData.SORT_TYPE)
    class MediaFilter : QueryPartFilter("Media", CrunchyFiltersData.MEDIA_TYPE)

    class LanguageFilter : CheckBoxFilterList(
        "Language",
        CrunchyFiltersData.LANGUAGE.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("Search Filter (ignored if browsing)"),
        TypeFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Browse Filters (ignored if searching)"),
        CategoryFilter(),
        SortFilter(),
        MediaFilter(),
        LanguageFilter(),
    )

    data class FilterSearchParams(
        val type: String = "",
        val category: String = "",
        val sort: String = "",
        val language: String = "",
        val media: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<CategoryFilter>(),
            filters.asQueryPart<SortFilter>(),
            filters.parseCheckbox<LanguageFilter>(CrunchyFiltersData.LANGUAGE),
            filters.asQueryPart<MediaFilter>(),
        )
    }

    private object CrunchyFiltersData {
        val SEARCH_TYPE = arrayOf(
            Pair("Top Results", "top_results"),
            Pair("Series", "series"),
            Pair("Movies", "movie_listing"),
        )

        val CATEGORIES = arrayOf(
            Pair("-", ""),
            Pair("Action", "&categories=action"),
            Pair("Action, Adventure", "&categories=action,adventure"),
            Pair("Action, Comedy", "&categories=action,comedy"),
            Pair("Action, Drama", "&categories=action,drama"),
            Pair("Action, Fantasy", "&categories=action,fantasy"),
            Pair("Action, Historical", "&categories=action,historical"),
            Pair("Action, Post-Apocalyptic", "&categories=action,post-apocalyptic"),
            Pair("Action, Sci-Fi", "&categories=action,sci-fi"),
            Pair("Action, Supernatural", "&categories=action,supernatural"),
            Pair("Action, Thriller", "&categories=action,thriller"),
            Pair("Adventure", "&categories=adventure"),
            Pair("Adventure, Fantasy", "&categories=adventure,fantasy"),
            Pair("Adventure, Isekai", "&categories=adventure,isekai"),
            Pair("Adventure, Romance", "&categories=adventure,romance"),
            Pair("Adventure, Sci-Fi", "&categories=adventure,sci-fi"),
            Pair("Adventure, Supernatural", "&categories=adventure,supernatural"),
            Pair("Comedy", "&categories=comedy"),
            Pair("Comedy, Drama", "&categories=comedy,drama"),
            Pair("Comedy, Fantasy", "&categories=comedy,fantasy"),
            Pair("Comedy, Historical", "&categories=comedy,historical"),
            Pair("Comedy, Music", "&categories=comedy,music"),
            Pair("Comedy, Romance", "&categories=comedy,romance"),
            Pair("Comedy, Sci-Fi", "&categories=comedy,sci-fi"),
            Pair("Comedy, Slice of life", "&categories=comedy,slice+of+life"),
            Pair("Comedy, Supernatural", "&categories=comedy,supernatural"),
            Pair("Drama", "&categories=drama"),
            Pair("Drama, Adventure", "&categories=drama,adventure"),
            Pair("Drama, Fantasy", "&categories=drama,fantasy"),
            Pair("Drama, Historical", "&categories=drama,historical"),
            Pair("Drama, Mecha", "&categories=drama,mecha"),
            Pair("Drama, Mystery", "&categories=drama,mystery"),
            Pair("Drama, Romance", "&categories=drama,romance"),
            Pair("Drama, Sci-Fi", "&categories=drama,sci-fi"),
            Pair("Drama, Slice of life", "&categories=drama,slice+of+life"),
            Pair("Fantasy", "&categories=fantasy"),
            Pair("Fantasy, Historical", "&categories=fantasy,historical"),
            Pair("Fantasy, Isekai", "&categories=fantasy,isekai"),
            Pair("Fantasy, Mystery", "&categories=fantasy,mystery"),
            Pair("Fantasy, Romance", "&categories=fantasy,romance"),
            Pair("Fantasy, Supernatural", "&categories=fantasy,supernatural"),
            Pair("Music", "&categories=music"),
            Pair("Music, Drama", "&categories=music,drama"),
            Pair("Music, Idols", "&categories=music,idols"),
            Pair("Music, slice of life", "&categories=music,slice+of+life"),
            Pair("Romance", "&categories=romance"),
            Pair("Romance, Harem", "&categories=romance,harem"),
            Pair("Romance, Historical", "&categories=romance,historical"),
            Pair("Sci-Fi", "&categories=sci-fi"),
            Pair("Sci-Fi, Fantasy", "&categories=sci-fi,Fantasy"),
            Pair("Sci-Fi, Historical", "&categories=sci-fi,historical"),
            Pair("Sci-Fi, Mecha", "&categories=sci-fi,mecha"),
            Pair("Seinen", "&categories=seinen"),
            Pair("Seinen, Action", "&categories=seinen,action"),
            Pair("Seinen, Drama", "&categories=seinen,drama"),
            Pair("Seinen, Fantasy", "&categories=seinen,fantasy"),
            Pair("Seinen, Historical", "&categories=seinen,historical"),
            Pair("Seinen, Supernatural", "&categories=seinen,supernatural"),
            Pair("Shojo", "&categories=shojo"),
            Pair("Shojo, Fantasy", "&categories=shojo,Fantasy"),
            Pair("Shojo, Magical Girls", "&categories=shojo,magical-girls"),
            Pair("Shojo, Romance", "&categories=shojo,romance"),
            Pair("Shojo, Slice of life", "&categories=shojo,slice+of+life"),
            Pair("Shonen", "&categories=shonen"),
            Pair("Shonen, Action", "&categories=shonen,action"),
            Pair("Shonen, Adventure", "&categories=shonen,adventure"),
            Pair("Shonen, Comedy", "&categories=shonen,comedy"),
            Pair("Shonen, Drama", "&categories=shonen,drama"),
            Pair("Shonen, Fantasy", "&categories=shonen,fantasy"),
            Pair("Shonen, Mystery", "&categories=shonen,mystery"),
            Pair("Shonen, Post-Apocalyptic", "&categories=shonen,post-apocalyptic"),
            Pair("Shonen, Supernatural", "&categories=shonen,supernatural"),
            Pair("Slice of life", "&categories=slice+of+life"),
            Pair("Slice of life, Fantasy", "&categories=slice+of+life,fantasy"),
            Pair("Slice of life, Romance", "&categories=slice+of+life,romance"),
            Pair("Slice of life, Sci-Fi", "&categories=slice+of+life,sci-fi"),
            Pair("Sports", "&categories=sports"),
            Pair("Sports, Action", "&categories=sports,action"),
            Pair("Sports, Comedy", "&categories=sports,comedy"),
            Pair("Sports, Drama", "&categories=sports,drama"),
            Pair("Supernatural", "&categories=supernatural"),
            Pair("Supernatural, Drama", "&categories=supernatural,drama"),
            Pair("Supernatural, Historical", "&categories=supernatural,historical"),
            Pair("Supernatural, Mystery", "&categories=supernatural,mystery"),
            Pair("Supernatural, Slice of life", "&categories=supernatural,slice+of+life"),
            Pair("Thriller", "&categories=thriller"),
            Pair("Thriller, Drama", "&categories=thriller,drama"),
            Pair("Thriller, Fantasy", "&categories=thriller,fantasy"),
            Pair("Thriller, Sci-Fi", "&categories=thriller,sci-fi"),
            Pair("Thriller, Supernatural", "&categories=thriller,supernatural"),
        )

        val SORT_TYPE = arrayOf(
            Pair("Popular", "popularity"),
            Pair("New", "newly_added"),
            Pair("Alphabetical", "alphabetical"),
        )

        val LANGUAGE = arrayOf(
            Pair("Sub", "&is_subbed=true"),
            Pair("Dub", "&is_dubbed=true"),
        )

        val MEDIA_TYPE = arrayOf(
            Pair("All", ""),
            Pair("Series", "&type=series"),
            Pair("Movies", "&type=movie_listing"),
        )
    }
}
