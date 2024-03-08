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

        internal class SeasonalFilter : QueryPartFilter("موسم الانمى", Anime4UpFiltersData.SEASON)

        val FILTER_LIST get() =
            AnimeFilterList(
                AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
                SeasonalFilter(),
                GenreFilter(),
                TypeFilter(),
                StatusFilter(),
            )

        data class FilterSearchParams(
            val genre: String = "",
            val type: String = "",
            val status: String = "",
            val season: String = "",
        )

        internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
            if (filters.isEmpty()) return FilterSearchParams()

            return FilterSearchParams(
                filters.asQueryPart<GenreFilter>(),
                filters.asQueryPart<TypeFilter>(),
                filters.asQueryPart<StatusFilter>(),
                filters.asQueryPart<SeasonalFilter>(),
            )
        }

        private object Anime4UpFiltersData {
            private val ANY = Pair("أختر", "")

            val GENRES =
                arrayOf(
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

            val TYPES =
                arrayOf(
                    ANY,
                    Pair("Movie", "movie-3"),
                    Pair("ONA", "ona1"),
                    Pair("OVA", "ova1"),
                    Pair("Special", "special1"),
                    Pair("TV", "tv2"),
                )

            val STATUS =
                arrayOf(
                    ANY,
                    Pair("لم يعرض بعد", "%d9%84%d9%85-%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a8%d8%b9%d8%af"),
                    Pair("مكتمل", "complete"),
                    Pair("يعرض الان", "%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a7%d9%84%d8%a7%d9%86-1"),
                )
            val SEASON =
                arrayOf(
                    ANY,
                    Pair("شتاء 2024","%d8%b4%d8%aa%d8%a7%d8%a1-2024"),
                    Pair("خريف 2023","%d8%ae%d8%b1%d9%8a%d9%81-2023"),
                    Pair("صيف 2023","%d8%b5%d9%8a%d9%81-2023"),
                    Pair("ربيع 2023","%d8%b1%d8%a8%d9%8a%d8%b9-2023"),
                    Pair("شتاء 2023","%d8%b4%d8%aa%d8%a7%d8%a1-2023"),
                    Pair("خريف 2022","%d8%ae%d8%b1%d9%8a%d9%81-2022"),
                    Pair("صيف 2022","%d8%b5%d9%8a%d9%81-2022"),
                    Pair("ربيع 2022","%d8%b1%d8%a8%d9%8a%d8%b9-2022"),
                    Pair("شتاء 2022","%d8%b4%d8%aa%d8%a7%d8%a1-2022"),
                    Pair("خريف 2021","%d8%ae%d8%b1%d9%8a%d9%81-%d8%b9%d8%a7%d9%85-2021"),
                    Pair("صيف 2021","%d8%a3%d9%86%d9%85%d9%8a%d8%a7%d8%aa-%d8%b5%d9%8a%d9%81-2021"),
                    Pair("ربيع 2021","%d8%b1%d8%a8%d9%8a%d8%b9-2021"),
                    Pair("شتاء 2021","%d8%b4%d8%aa%d8%a7%d8%a1-2021"),
                    Pair("خريف 2020","%d8%ae%d8%b1%d9%8a%d9%81-2020"),
                    Pair("صيف 2020","%d8%b5%d9%8a%d9%81-2020"),
                    Pair("ربيع 2020","%d8%b1%d8%a8%d9%8a%d8%b9-2020"),
                    Pair("شتاء 2020","%d8%b4%d8%aa%d8%a7%d8%a1-2020"),
                    Pair("خريف 2019","%d8%ae%d8%b1%d9%8a%d9%81-2019"),
                    Pair("صيف 2019","%d8%b5%d9%8a%d9%81-2019"),
                    Pair("ربيع 2019","%d8%b1%d8%a8%d9%8a%d8%b9-2019"),
                    Pair("شتاء 2019","%d8%b4%d8%aa%d8%a7%d8%a1-2019"),
                    Pair("خريف 2018","%d8%ae%d8%b1%d9%8a%d9%81-2018"),
                    Pair("صيف 2018","%d8%b5%d9%8a%d9%81-2018"),
                    Pair("ربيع 2018","%d8%b1%d8%a8%d9%8a%d8%b9-2018"),
                    Pair("شتاء 2018","%d8%b4%d8%aa%d8%a7%d8%a1-2018"),
                    Pair("خريف 2017","%d8%ae%d8%b1%d9%8a%d9%81-2017"),
                    Pair("صيف 2017","%d8%b5%d9%8a%d9%81-2017"),
                    Pair("ربيع 2017","%d8%b1%d8%a8%d9%8a%d8%b9-2017"),
                    Pair("شتاء 2017","%d8%b4%d8%aa%d8%a7%d8%a1-2017"),
                    Pair("خريف 2016","%d8%ae%d8%b1%d9%8a%d9%81-2016"),
                    Pair("صيف 2016","%d8%b5%d9%8a%d9%81-2016"),
                    Pair("ربيع 2016","%d8%b1%d8%a8%d9%8a%d8%b9-2016"),
                    Pair("شتاء 2016","%d8%b4%d8%aa%d8%a7%d8%a1-2016"),
                    Pair("خريف 2015","%d8%ae%d8%b1%d9%8a%d9%81-2015"),
                    Pair("صيف 2015","%d8%b5%d9%8a%d9%81-2015"),
                    Pair("ربيع 2015","%d8%b1%d8%a8%d9%8a%d8%b9-2015"),
                    Pair("شتاء 2015","%d8%b4%d8%aa%d8%a7%d8%a1-2015"),
                    Pair("خريف 2014","%d8%ae%d8%b1%d9%8a%d9%81-2014"),
                    Pair("صيف 2014","%d8%b5%d9%8a%d9%81-2014"),
                    Pair("ربيع 2014","%d8%b1%d8%a8%d9%8a%d8%b9-2014"),
                    Pair("شتاء 2014","%d8%b4%d8%aa%d8%a7%d8%a1-2014"),
                    Pair("خريف 2013","%d8%ae%d8%b1%d9%8a%d9%81-2013"),
                    Pair("صيف 2013","%d8%b5%d9%8a%d9%81-2013"),
                    Pair("ربيع 2013","%d8%b1%d8%a8%d9%8a%d8%b9-2013"),
                    Pair("شتاء 2013","%d8%b4%d8%aa%d8%a7%d8%a1-2013"),
                    Pair("خريف 2012","%d8%ae%d8%b1%d9%8a%d9%81-2012"),
                    Pair("صيف 2012","%d8%b5%d9%8a%d9%81-2012"),
                    Pair("ربيع 2012","%d8%b1%d8%a8%d9%8a%d8%b9-2012"),
                    Pair("شتاء 2012","%d8%b4%d8%aa%d8%a7%d8%a1-2012"),
                    Pair("خريف 2011","%d8%ae%d8%b1%d9%8a%d9%81-2011"),
                    Pair("صيف 2011","%d8%b5%d9%8a%d9%81-2011"),
                    Pair("ربيع 2011","%d8%b1%d8%a8%d9%8a%d8%b9-2011"),
                    Pair("خريف 2010","%d8%ae%d8%b1%d9%8a%d9%81-2010"),
                    Pair("ربيع 2010","%d8%b1%d8%a8%d9%8a%d8%b9-2010"),
                    Pair("شتاء 2010","%d8%b4%d8%aa%d8%a7%d8%a1-2010"),
                    Pair("خريف 2009","%d8%ae%d8%b1%d9%8a%d9%81-2009"),
                    Pair("صيف 2009","%d8%b5%d9%8a%d9%81-2009"),
                    Pair("ربيع 2009","%d8%b1%d8%a8%d9%8a%d8%b9-2009"),
                    Pair("شتاء 2009","%d8%b4%d8%aa%d8%a7%d8%a1-2009"),
                    Pair("خريف 2008","%d8%ae%d8%b1%d9%8a%d9%81-2008"),
                    Pair("صيف 2008","%d8%b5%d9%8a%d9%81-2008"),
                    Pair("ربيع 2008","%d8%a3%d9%86%d9%85%d9%8a%d8%a7%d8%aa-%d9%85%d9%88%d8%b3%d9%85-%d8%b1%d8%a8%d9%8a%d8%b9-2008"),
                    Pair("شتاء 2008","%d8%b4%d8%aa%d8%a7%d8%a1-2008"),
                    Pair("خريف 2007","%d8%ae%d8%b1%d9%8a%d9%81-2007"),
                    Pair("صيف 2007","%d8%b5%d9%8a%d9%81-2007"),
                    Pair("ربيع 2007","%d8%b1%d8%a8%d9%8a%d8%b9-2007"),
                    Pair("شتاء 2007","%d8%b4%d8%aa%d8%a7%d8%a1-2007"),
                    Pair("خريف 2006","%d8%ae%d8%b1%d9%8a%d9%81-2006"),
                    Pair("ربيع 2006","%d8%b1%d8%a8%d9%8a%d8%b9-2006"),
                    Pair("شتاء 2006","%d8%b4%d8%aa%d8%a7%d8%a1-2006"),
                    Pair("خريف 2005","%d8%ae%d8%b1%d9%8a%d9%81-2005"),
                    Pair("ربيع 2005","%d8%b1%d8%a8%d9%8a%d8%b9-2005"),
                    Pair("شتاء 2005","%d8%b4%d8%aa%d8%a7%d8%a1-2005"),
                    Pair("خريف 2004","%d8%ae%d8%b1%d9%8a%d9%81-2004"),
                    Pair("ربيع 2004","%d8%b1%d8%a8%d9%8a%d8%b9-2004"),
                    Pair("شتاء 2004","%d8%b4%d8%aa%d8%a7%d8%a1-2004"),
                    Pair("صيف 2003","%d8%b5%d9%8a%d9%81-2003"),
                    Pair("ربيع 2003","%d8%b1%d8%a8%d9%8a%d8%b9-2003"),
                    Pair("شتاء 2003","%d8%b4%d8%aa%d8%a7%d8%a1-2003"),
                    Pair("خريف 2002","%d8%ae%d8%b1%d9%8a%d9%81-2002"),
                    Pair("ربيع 2002","%d8%b1%d8%a8%d9%8a%d8%b9-2002"),
                    Pair("شتاء 2002","%d8%b4%d8%aa%d8%a7%d8%a1-2002"),
                    Pair("ربيع 2001","%d8%b1%d8%a8%d9%8a%d8%b9-2001"),
                    Pair("شتاء 2001","%d8%b4%d8%aa%d8%a7%d8%a1-2001"),
                    Pair("خريف 2000","%d8%ae%d8%b1%d9%8a%d9%81-2000"),
                    Pair("ربيع 2000","%d8%b1%d8%a8%d9%8a%d8%b9-2000"),
                    Pair("خريف 1999","%d8%ae%d8%b1%d9%8a%d9%81-1999"),
                    Pair("صيف 1998","%d8%b5%d9%8a%d9%81-1998"),
                    Pair("شتاء 1996","%d8%b4%d8%aa%d8%a7%d8%a1-1996"),
                    Pair("صيف 1995","%d8%b5%d9%8a%d9%81-1995"),
                    Pair("خريف 1991","%d8%ae%d8%b1%d9%8a%d9%81-1991"),
                    Pair("ربيع 1989","%d8%b1%d8%a8%d9%8a%d8%b9-1989"),
                    Pair("شتاء 1986","%d8%b4%d8%aa%d8%a7%d8%a1-1986"),
                )
        }
    }

    //         text = ""
    // Array.from (document.querySelectorAll("body > div.second-section > div > div.anime-filter-options > ul > li:nth-child(5) > div > ul >li > a") ).forEach ((li)=>(
    //     text += `Pair("${li.textContent}","${String(li.getAttribute("href")).split('/')[4]}") \n `

    //     ))
    //     console.log(text)
