package eu.kanade.tachiyomi.animeextension.pt.sukianimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object SKFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray()
    ) {

        fun toQueryPart() = vals[state].second
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)
    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return this.filterIsInstance<R>().joinToString("") {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class AdultFilter : AnimeFilter.CheckBox("Exibir animes adultos", true)

    class FormatFilter : QueryPartFilter("Formato", SKFiltersData.formats)
    class StatusFilter : QueryPartFilter("Status do anime", SKFiltersData.status)
    class TypeFilter : QueryPartFilter("Tipo de vídeo", SKFiltersData.types)

    class GenresFilter : CheckBoxFilterList(
        "Gêneros",
        SKFiltersData.genres.map { CheckBoxVal(it.first, false) }
    )

    // Mimicking the order of filters on the source
    val filterList = AnimeFilterList(
        TypeFilter(),
        StatusFilter(),
        AdultFilter(),
        FormatFilter(),
        GenresFilter()
    )

    data class FilterSearchParams(
        val adult: Boolean = true,
        val format: String = "",
        val genres: List<String> = emptyList<String>(),
        val status: String = "",
        val type: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {

        if (filters.isEmpty()) return FilterSearchParams()

        val genres = filters.getFirst<GenresFilter>().state
            .mapNotNull { genre ->
                if (genre.state) {
                    SKFiltersData.genres.find { it.first == genre.name }!!.second
                } else { null }
            }.toList()

        return FilterSearchParams(
            filters.getFirst<AdultFilter>().state,
            filters.asQueryPart<FormatFilter>(),
            genres,
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<TypeFilter>()
        )
    }

    private object SKFiltersData {
        val every = Pair("Qualquer um", "")

        val types = arrayOf(
            every,
            Pair("Legendado", "1"),
            Pair("Dublado", "2")
        )

        val status = arrayOf(
            every,
            Pair("Completo", "Completo"),
            Pair("Em lançamento", "Lançamento")
        )

        val formats = arrayOf(
            every,
            Pair("Anime", "Anime"),
            Pair("Filme", "Filme")
        )

        val genres = arrayOf(
            Pair("Artes Marciais", "12"),
            Pair("Aventura", "13"),
            Pair("Ação", "5"),
            Pair("Boys Love", "1125"),
            Pair("Carros", "945"),
            Pair("Chinês", "1032"),
            Pair("Comédia Romântica", "15"),
            Pair("Comédia", "14"),
            Pair("Corrida", "1690"),
            Pair("Culinária", "576"),
            Pair("Dementia", "164"),
            Pair("Demônios", "35"),
            Pair("Drama", "9"),
            Pair("Ecchi", "16"),
            Pair("Erótico", "1203"),
            Pair("Escolar", "812"),
            Pair("Espaço", "429"),
            Pair("Esporte", "17"),
            Pair("Fantasia", "10"),
            Pair("Ficção Científica", "18"),
            Pair("Game", "156"),
            Pair("Girls Love", "1228"),
            Pair("Gore", "1708"),
            Pair("Harém", "69"),
            Pair("Histórico", "88"),
            Pair("Horror", "165"),
            Pair("Idols", "1702"),
            Pair("Insanidade", "891"),
            Pair("Isekai", "1138"),
            Pair("Jogos", "19"),
            Pair("Josei", "1345"),
            Pair("Kids", "847"),
            Pair("Magia", "20"),
            Pair("Maid", "1677"),
            Pair("Mecha", "21"),
            Pair("Militar", "6"),
            Pair("Mistério", "7"),
            Pair("Munyuu", "1074"),
            Pair("Musical", "22"),
            Pair("Novel", "252"),
            Pair("Parody", "1197"),
            Pair("Paródia", "207"),
            Pair("Performing Arts", "1564"),
            Pair("Piratas", "172"),
            Pair("Polícia", "229"),
            Pair("Psicológico", "50"),
            Pair("RPG", "94"),
            Pair("Romance", "23"),
            Pair("Samurai", "439"),
            Pair("School", "1065"),
            Pair("Sci-Fi", "42"),
            Pair("Seinen", "24"),
            Pair("Shoujo", "210"),
            Pair("Shoujo-ai", "25"),
            Pair("Shounen", "11"),
            Pair("Shounen-AI", "322"),
            Pair("Slice of Life", "26"),
            Pair("Sobrenatural", "27"),
            Pair("Super Poder", "8"),
            Pair("Suspense", "230"),
            Pair("Terror", "28"),
            Pair("Thriller", "51"),
            Pair("Tragedia", "269"),
            Pair("Vampiro", "134"),
            Pair("Vida Diaria", "253"),
            Pair("Vida Escolar", "29"),
            Pair("Violência", "440"),
            Pair("Yaoi", "612"),
            Pair("Yuri", "1497")
        )
    }
}
