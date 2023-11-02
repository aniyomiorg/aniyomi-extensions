package eu.kanade.tachiyomi.multisrc.animestream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import org.jsoup.select.Elements

object AnimeStreamFilters {
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

    inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

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

    internal class GenresFilter(name: String) : CheckBoxFilterList(name, GENRES_LIST)
    internal class SeasonFilter(name: String) : CheckBoxFilterList(name, SEASON_LIST)
    internal class StudioFilter(name: String) : CheckBoxFilterList(name, STUDIO_LIST)

    internal class StatusFilter(name: String) : QueryPartFilter(name, STATUS_LIST)
    internal class TypeFilter(name: String) : QueryPartFilter(name, TYPE_LIST)
    internal class SubFilter(name: String) : QueryPartFilter(name, SUB_LIST)
    internal class OrderFilter(name: String) : QueryPartFilter(name, ORDER_LIST)

    internal data class FilterSearchParams(
        val genres: String = "",
        val seasons: String = "",
        val studios: String = "",
        val status: String = "",
        val type: String = "",
        val sub: String = "",
        val order: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty() || !filterInitialized()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(GENRES_LIST, "genre"),
            filters.parseCheckbox<SeasonFilter>(SEASON_LIST, "season"),
            filters.parseCheckbox<StudioFilter>(STUDIO_LIST, "studio"),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<SubFilter>(),
            filters.asQueryPart<OrderFilter>(),
        )
    }

    lateinit var filterElements: Elements

    fun filterInitialized() = ::filterElements.isInitialized

    fun getPairListByIndex(index: Int) = filterElements.get(index)
        .select("li")
        .map { element ->
            val key = element.selectFirst("label")!!.text()
            val value = element.selectFirst("input")!!.attr("value")
            Pair(key, value)
        }.toTypedArray()

    private val GENRES_LIST by lazy { getPairListByIndex(0) }
    private val SEASON_LIST by lazy { getPairListByIndex(1) }
    private val STUDIO_LIST by lazy { getPairListByIndex(2) }
    private val STATUS_LIST by lazy { getPairListByIndex(3) }
    private val TYPE_LIST by lazy { getPairListByIndex(4) }
    private val SUB_LIST by lazy { getPairListByIndex(5) }
    private val ORDER_LIST by lazy { getPairListByIndex(6) }
}
