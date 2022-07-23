package eu.kanade.tachiyomi.animeextension.pt.hentaiyabu

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
object HYFilters {

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

    class InitialLetterFilter : QueryPartFilter("Primeira letra", HYFiltersData.initialLetter)

    class EpisodeFilter : AnimeFilter.Text("Episódios")
    class EpisodeFilterMode : QueryPartFilter("Modo de filtro", HYFiltersData.episodeFilterMode)
    class SortFilter : AnimeFilter.Sort(
        "Ordenar",
        HYFiltersData.orders.map { it.first }.toTypedArray(),
        Selection(0, true)
    )

    class GenresFilter : TriStateFilterList(
        "Gêneros",
        HYFiltersData.genres.map { TriStateVal(it) }
    )

    val filterList = AnimeFilterList(
        InitialLetterFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        EpisodeFilter(),
        EpisodeFilterMode(),
        AnimeFilter.Separator(),
        GenresFilter(),
    )

    data class FilterSearchParams(
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
            filters.asQueryPart<InitialLetterFilter>(),
            filters.asQueryPart<EpisodeFilterMode>(),
        )

        searchParams.numEpisodes = try {
            filters.getFirst<EpisodeFilter>().state.toInt()
        } catch (e: NumberFormatException) { 0 }

        filters.getFirst<SortFilter>().state?.let {
            val order = HYFiltersData.orders[it.index].second
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

    private object HYFiltersData {

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
            "Ahegao",
            "Anal",
            "Artes Marciais",
            "Ashikoki",
            "Aventura",
            "Ação",
            "BDSM",
            "Bara",
            "Boquete",
            "Boys Love",
            "Brinquedos",
            "Brinquedos Sexuais",
            "Bukkake",
            "Bunda Grande",
            "Chikan",
            "Científica",
            "Comédia",
            "Cosplay",
            "Creampie",
            "Dark Skin",
            "Demônio",
            "Drama",
            "Dupla Penetração",
            "Ecchi",
            "Elfos",
            "Empregada",
            "Enfermeira",
            "Eroge",
            "Erótico",
            "Escolar",
            "Esporte",
            "Estupro",
            "Facial",
            "Fantasia",
            "Femdom",
            "Ficção",
            "Ficção Científica",
            "Futanari",
            "Gang Bang",
            "Garotas De Escritório",
            "Gender Bender",
            "Gerakuro",
            "Gokkun",
            "Golden Shower",
            "Gore",
            "Gozando Dentro",
            "Grupo",
            "Grávida",
            "Guerra",
            "Gyaru",
            "Harém",
            "Hipnose",
            "Histórico",
            "Horror",
            "Incesto",
            "Jogos Eróticos",
            "Josei",
            "Kemono",
            "Kemonomimi",
            "Lactação",
            "Lolicon",
            "Magia",
            "Maid",
            "Masturbação",
            "Mecha",
            "Menage",
            "Metrô",
            "Milf",
            "Mind Break",
            "Mind Control",
            "Mistério",
            "Moe",
            "Monstros",
            "Médico",
            "Nakadashi",
            "Nerd",
            "Netorare",
            "Ninjas",
            "Óculos",
            "Oral",
            "Orgia",
            "Paizuri",
            "Paródia",
            "Peitões",
            "Pelos Pubianos",
            "Pettanko",
            "Policial",
            "Preservativo",
            "Professor",
            "Psicológico",
            "Punição",
            "Raio-X",
            "Romance",
            "Ronin",
            "Sci-Fi",
            "Seinen",
            "Sexo Público",
            "Shotacon",
            "Shoujo Ai",
            "Shounen",
            "Shounen Ai",
            "Slice Of Life",
            "Sobrenatural",
            "Submissão",
            "Succubus",
            "Super Poder",
            "Swimsuit",
            "Tentáculos",
            "Terror",
            "Tetas",
            "Thriller",
            "Traição",
            "Trem",
            "Vampiros",
            "Vanilla",
            "Vida Escolar",
            "Virgem",
            "Voyeur",
            "Yaoi",
            "Yuri",
            "Zoofilia",
        )
    }
}
