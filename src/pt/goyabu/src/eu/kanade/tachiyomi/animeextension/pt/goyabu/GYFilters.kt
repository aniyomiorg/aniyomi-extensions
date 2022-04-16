package eu.kanade.tachiyomi.animeextension.pt.goyabu

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object GYFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray()
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, values: List<TriState>) : AnimeFilter.Group<AnimeFilter.TriState>(name, values)
    private class TriStateVal(name: String) : AnimeFilter.TriState(name)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return this.getFirst<R>().let {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class LanguageFilter : QueryPartFilter("Idioma", GYFiltersData.languages)
    class InitialLetterFilter : QueryPartFilter("Primeira letra", GYFiltersData.initialLetter)

    class EpisodeFilter : AnimeFilter.Text("Episódios")
    class EpisodeFilterMode : QueryPartFilter("Modo de filtro", GYFiltersData.episodeFilterMode)
    class SortFilter : AnimeFilter.Sort(
        "Ordenar",
        GYFiltersData.orders.map { it.first }.toTypedArray(),
        Selection(0, true)
    )

    class GenresFilter : TriStateFilterList(
        "Gêneros",
        GYFiltersData.genres.map { TriStateVal(it) }
    )

    val filterList = AnimeFilterList(
        LanguageFilter(),
        InitialLetterFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        EpisodeFilter(),
        EpisodeFilterMode(),
        AnimeFilter.Separator(),
        GenresFilter(),
    )

    data class FilterSearchParams(
        val language: String = "",
        val initialLetter: String = "",
        val episodesFilterMode: String = ">=",
        var numEpisodes: Int = 0,
        var orderAscending: Boolean = true,
        var sortBy: String = "",
        val blackListedGenres: ArrayList<String> = ArrayList(),
        val includedGenres: ArrayList<String> = ArrayList(),
        var animeName: String = ""
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        val searchParams = FilterSearchParams(
            filters.asQueryPart<LanguageFilter>(),
            filters.asQueryPart<InitialLetterFilter>(),
            filters.asQueryPart<EpisodeFilterMode>(),
        )

        searchParams.numEpisodes = try {
            filters.getFirst<EpisodeFilter>().state.toInt()
        } catch (e: NumberFormatException) { 0 }

        filters.getFirst<SortFilter>().state?.let {
            val order = GYFiltersData.orders[it.index].second
            searchParams.orderAscending = it.ascending
            searchParams.sortBy = order
        }

        filters.getFirst<GenresFilter>()
            .state.forEach { genre ->
                if (genre.isIncluded()) {
                    searchParams.includedGenres.add(genre.name)
                } else if (genre.isExcluded()) {
                    searchParams.blackListedGenres.add(genre.name)
                }
            }

        return searchParams
    }

    private fun mustRemove(anime: SearchResultDto, params: FilterSearchParams): Boolean {
        val epFilterMode = params.episodesFilterMode
        return when {
            params.animeName != "" && params.animeName.lowercase() !in anime.title.lowercase() -> true
            anime.title == "null" -> true
            params.language != "" && params.language !in anime.type -> true
            params.initialLetter != "" && !anime.title.startsWith(params.initialLetter) -> true
            params.blackListedGenres.size > 0 && params.blackListedGenres.any {
                it.lowercase() in anime.genre.lowercase()
            } -> true
            params.includedGenres.size > 0 && params.includedGenres.any {
                it.lowercase() !in anime.genre.lowercase()
            } -> true
            params.numEpisodes > 0 -> {
                when (epFilterMode) {
                    "==" -> params.numEpisodes != anime.videos
                    ">=" -> params.numEpisodes >= anime.videos
                    "<=" -> params.numEpisodes <= anime.videos
                    else -> false
                }
            }
            else -> false
        }
    }

    fun MutableList<SearchResultDto>.applyFilterParams(params: FilterSearchParams) {
        this.removeAll { anime -> mustRemove(anime, params) }
        when (params.sortBy) {
            "A-Z" -> {
                if (!params.orderAscending)
                    this.reverse()
            }
            "num" -> {
                if (params.orderAscending)
                    this.sortBy { it.videos }
                else
                    this.sortByDescending { it.videos }
            }
        }
    }

    private object GYFiltersData {

        val languages = arrayOf(
            Pair("Todos", ""),
            Pair("Legendado", "Leg"),
            Pair("Dublado", "Dub")
        )

        val orders = arrayOf(
            Pair("Alfabeticamente", "A-Z"),
            Pair("Por número de eps", "num")
        )

        val initialLetter = arrayOf(Pair("Qualquer uma", "")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val episodeFilterMode = arrayOf(
            Pair("Maior ou igual", ">="),
            Pair("Menor ou igual", "<="),
            Pair("Igual", "=="),
        )

        val genres = arrayOf(
            "Alien",
            "Animação Chinesa",
            "Anjos",
            "Artes Marciais",
            "Astronautas",
            "Aventura",
            "Ação",
            "Carros",
            "Comédia",
            "Crianças",
            "Demência",
            "Demônios",
            "Drama",
            "Ecchi",
            "Escolar",
            "Espacial",
            "Espaço",
            "Esporte",
            "Fantasia",
            "Fantasmas",
            "Ficção Científica",
            "Harém",
            "Histórico",
            "Horror",
            "Idol",
            "Infantil",
            "Isekai",
            "Jogo",
            "Josei",
            "Magia",
            "Mecha",
            "Militar",
            "Mistério",
            "Monstros",
            "Magia",
            "Música",
            "Otaku",
            "Paródia",
            "Piratas",
            "Policial",
            "Psicológico",
            "RPG",
            "Realidade Virtual",
            "Romance",
            "Samurai",
            "Sci-Fi",
            "Seinen",
            "Shoujo",
            "Shoujo Ai",
            "Shounen",
            "Shounen Ai",
            "Slice of life",
            "Sobrenatural",
            "Super Poder",
            "Supernatural",
            "Superpotência",
            "Suspense",
            "Teatro",
            "Terror",
            "Thriller",
            "Vampiro",
            "Vida Escolar",
            "Yaoi",
            "Yuri"
        )
    }
}
