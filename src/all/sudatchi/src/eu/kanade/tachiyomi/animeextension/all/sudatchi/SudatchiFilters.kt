package eu.kanade.tachiyomi.animeextension.all.sudatchi

import eu.kanade.tachiyomi.animeextension.all.sudatchi.dto.DirectoryFiltersDto
import eu.kanade.tachiyomi.animeextension.all.sudatchi.dto.FilterItemDto
import eu.kanade.tachiyomi.animeextension.all.sudatchi.dto.FilterYearDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.OkHttpClient

class SudatchiFilters(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {

    private var error = false

    private lateinit var filterList: AnimeFilterList

    interface QueryParameterFilter { fun toQueryParameter(): Pair<String, String?> }

    private class Checkbox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private class CheckboxList(name: String, private val paramName: String, private val pairs: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { Checkbox(it.first) }), QueryParameterFilter {
        override fun toQueryParameter() = Pair(
            paramName,
            state.asSequence()
                .filter { it.state }
                .map { checkbox -> pairs.find { it.first == checkbox.name }!!.second }
                .filter(String::isNotBlank)
                .joinToString(","),
        )
    }

    fun getFilterList(): AnimeFilterList {
        return if (error) {
            AnimeFilterList(AnimeFilter.Header("Error fetching the filters."))
        } else if (this::filterList.isInitialized) {
            filterList
        } else {
            AnimeFilterList(AnimeFilter.Header("Use 'Reset' to load the filters."))
        }
    }

    fun fetchFilters() {
        if (!this::filterList.isInitialized) {
            runCatching {
                error = false
                filterList = client.newCall(GET("$baseUrl/api/directory"))
                    .execute()
                    .parseAs<DirectoryFiltersDto>()
                    .let(::filtersParse)
            }.onFailure { error = true }
        }
    }

    private fun List<FilterItemDto>.toPairList() = map { Pair(it.name, it.id.toString()) }

    @JvmName("toPairList2")
    private fun List<FilterYearDto>.toPairList() = map { Pair(it.year.toString(), it.year.toString()) }

    private fun filtersParse(directoryFiltersDto: DirectoryFiltersDto): AnimeFilterList {
        return AnimeFilterList(
            CheckboxList("Genres", "genres", directoryFiltersDto.genres.toPairList()),
            CheckboxList("Years", "years", directoryFiltersDto.years.toPairList()),
            CheckboxList("Types", "types", directoryFiltersDto.types.toPairList()),
            CheckboxList("Status", "status", directoryFiltersDto.status.toPairList()),
        )
    }
}
