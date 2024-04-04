package eu.kanade.tachiyomi.animeextension.fr.anisama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

class AniSamaFilters(
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

    private class Select(name: String, private val paramName: String, private val pairs: List<Pair<String, String>>) :
        AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()), QueryParameterFilter {
        override fun toQueryParameter() = Pair(paramName, pairs[state].second)
    }

    fun getFilterList(): AnimeFilterList {
        return if (error) {
            AnimeFilterList(AnimeFilter.Header("Erreur lors de la récupération des filtres."))
        } else if (this::filterList.isInitialized) {
            filterList
        } else {
            AnimeFilterList(AnimeFilter.Header("Utilise \"Réinitialiser\" pour charger les filtres."))
        }
    }

    fun fetchFilters() {
        if (!this::filterList.isInitialized) {
            runCatching {
                error = false
                filterList = client.newCall(GET("$baseUrl/filter"))
                    .execute()
                    .asJsoup()
                    .let(::filtersParse)
            }.onFailure { error = true }
        }
    }

    private fun Elements.parseFilterValues(name: String): List<Pair<String, String>> =
        select(".item:has(.btn:contains($name)) li").map {
            Pair(it.text(), it.select("input").attr("value"))
        }

    private fun filtersParse(document: Document): AnimeFilterList {
        val form = document.select(".block_area-filter")
        return AnimeFilterList(
            CheckboxList("Genres", "genre", form.parseFilterValues("Genre")),
            CheckboxList("Saisons", "season", form.parseFilterValues("Saison")),
            CheckboxList("Années", "year", form.parseFilterValues("Année")),
            CheckboxList("Types", "type", form.parseFilterValues("Type")),
            CheckboxList("Status", "status", form.parseFilterValues("Status")),
            CheckboxList("Langues", "language", form.parseFilterValues("Langue")),
            Select("Trié par", "sort", form.parseFilterValues("Sort")),
        )
    }
}
