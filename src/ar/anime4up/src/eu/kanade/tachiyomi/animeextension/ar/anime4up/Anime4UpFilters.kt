package eu.kanade.tachiyomi.animeextension.ar.anime4up

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Anime4UpFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    internal class GenreFilter : QueryPartFilter("تصنيف الأنمي", Anime4UpFiltersData.GENRES)
    internal class TypeFilter : QueryPartFilter("نوع الأنمي", Anime4UpFiltersData.TYPES)
    internal class StatusFilter : QueryPartFilter("حالة الأنمي", Anime4UpFiltersData.STATUS)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    data class FilterSearchParams(
        val genre: String = "",
        val type: String = "",
        val status: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<GenreFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<StatusFilter>(),
        )
    }

    private object Anime4UpFiltersData {
        private val ANY = Pair("أختر", "")

        val GENRES = arrayOf(
            ANY,
            Pair("أطفال", "%d8%a3%d8%b7%d9%81%d8%a7%d9%84"),
            Pair("أكشن", "%d8%a3%d9%83%d8%b4%d9%86/"),
            Pair("إيتشي", "%d8%a5%d9%8a%d8%aa%d8%b4%d9%8a/"),
            Pair("اثارة", "%d8%a7%d8%ab%d8%a7%d8%b1%d8%a9/"),
            Pair("العاب", "%d8%a7%d9%84%d8%b9%d8%a7%d8%a8/"),
            Pair("بوليسي", "%d8%a8%d9%88%d9%84%d9%8a%d8%b3%d9%8a/"),
            Pair("تاريخي", "%d8%aa%d8%a7%d8%b1%d9%8a%d8%ae%d9%8a/"),
            Pair("جنون", "%d8%ac%d9%86%d9%88%d9%86/"),
            Pair("جوسي", "%d8%ac%d9%88%d8%b3%d9%8a/"),
            Pair("حربي", "%d8%ad%d8%b1%d8%a8%d9%8a/"),
            Pair("حريم", "%d8%ad%d8%b1%d9%8a%d9%85/"),
            Pair("خارق للعادة", "%d8%ae%d8%a7%d8%b1%d9%82-%d9%84%d9%84%d8%b9%d8%a7%d8%af%d8%a9/"),
            Pair("خيال علمي", "%d8%ae%d9%8a%d8%a7%d9%84-%d8%b9%d9%84%d9%85%d9%8a/"),
            Pair("دراما", "%d8%af%d8%b1%d8%a7%d9%85%d8%a7/"),
            Pair("رعب", "%d8%b1%d8%b9%d8%a8/"),
            Pair("رومانسي", "%d8%b1%d9%88%d9%85%d8%a7%d9%86%d8%b3%d9%8a/"),
            Pair("رياضي", "%d8%b1%d9%8a%d8%a7%d8%b6%d9%8a/"),
            Pair("ساموراي", "%d8%b3%d8%a7%d9%85%d9%88%d8%b1%d8%a7%d9%8a/"),
            Pair("سحر", "%d8%b3%d8%ad%d8%b1/"),
            Pair("سينين", "%d8%b3%d9%8a%d9%86%d9%8a%d9%86/"),
            Pair("شريحة من الحياة", "%d8%b4%d8%b1%d9%8a%d8%ad%d8%a9-%d9%85%d9%86-%d8%a7%d9%84%d8%ad%d9%8a%d8%a7%d8%a9/"),
            Pair("شوجو", "%d8%b4%d9%88%d8%ac%d9%88/"),
            Pair("شوجو اَي", "%d8%b4%d9%88%d8%ac%d9%88-%d8%a7%d9%8e%d9%8a/"),
            Pair("شونين", "%d8%b4%d9%88%d9%86%d9%8a%d9%86/"),
            Pair("شونين اي", "%d8%b4%d9%88%d9%86%d9%8a%d9%86-%d8%a7%d9%8a/"),
            Pair("شياطين", "%d8%b4%d9%8a%d8%a7%d8%b7%d9%8a%d9%86/"),
            Pair("غموض", "%d8%ba%d9%85%d9%88%d8%b6/"),
            Pair("فضائي", "%d9%81%d8%b6%d8%a7%d8%a6%d9%8a/"),
            Pair("فنتازيا", "%d9%81%d9%86%d8%aa%d8%a7%d8%b2%d9%8a%d8%a7/"),
            Pair("فنون قتالية", "%d9%81%d9%86%d9%88%d9%86-%d9%82%d8%aa%d8%a7%d9%84%d9%8a%d8%a9/"),
            Pair("قوى خارقة", "%d9%82%d9%88%d9%89-%d8%ae%d8%a7%d8%b1%d9%82%d8%a9/"),
            Pair("كوميدي", "%d9%83%d9%88%d9%85%d9%8a%d8%af%d9%8a/"),
            Pair("محاكاة ساخرة", "%d9%85%d8%ad%d8%a7%d9%83%d8%a7%d8%a9-%d8%b3%d8%a7%d8%ae%d8%b1%d8%a9/"),
            Pair("مدرسي", "%d9%85%d8%af%d8%b1%d8%b3%d9%8a/"),
            Pair("مصاصي دماء", "%d9%85%d8%b5%d8%a7%d8%b5%d9%8a-%d8%af%d9%85%d8%a7%d8%a1/"),
            Pair("مغامرات", "%d9%85%d8%ba%d8%a7%d9%85%d8%b1%d8%a7%d8%aa/"),
            Pair("موسيقي", "%d9%85%d9%88%d8%b3%d9%8a%d9%82%d9%8a/"),
            Pair("ميكا", "%d9%85%d9%8a%d9%83%d8%a7/"),
            Pair("نفسي", "%d9%86%d9%81%d8%b3%d9%8a/"),
        )

        val TYPES = arrayOf(
            ANY,
            Pair("Movie", "movie-3"),
            Pair("ONA", "ona1"),
            Pair("OVA", "ova1"),
            Pair("Special", "special1"),
            Pair("TV", "tv2"),
        )

        val STATUS = arrayOf(
            ANY,
            Pair("لم يعرض بعد", "%d9%84%d9%85-%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a8%d8%b9%d8%af"),
            Pair("مكتمل", "complete"),
            Pair("يعرض الان", "%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a7%d9%84%d8%a7%d9%86-1"),
        )
    }
}
