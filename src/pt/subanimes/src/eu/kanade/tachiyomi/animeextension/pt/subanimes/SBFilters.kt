package eu.kanade.tachiyomi.animeextension.pt.subanimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object SBFilters {

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

    class FormatFilter : QueryPartFilter("Tipo de série", SBFiltersData.formats)
    class StatusFilter : QueryPartFilter("Status do anime", SBFiltersData.status)
    class TypeFilter : QueryPartFilter("Tipo de áudio", SBFiltersData.types)

    class GenresFilter : CheckBoxFilterList(
        "Gêneros",
        SBFiltersData.genres.map { CheckBoxVal(it.first, false) }
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
                    SBFiltersData.genres.find { it.first == genre.name }!!.second
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

    private object SBFiltersData {
        val every = Pair("Qualquer um", "")

        val types = arrayOf(
            every,
            Pair("Japonês/Legendado", "1"),
            Pair("Português/Dublado", "2")
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
            Pair("Adulto", "334"),
            Pair("Animação", "2374"),
            Pair("Arte Marcial", "16"),
            Pair("Avant Garde", "2846"),
            Pair("Avant", "2845"),
            Pair("Aventura", "4"),
            Pair("Ação", "15"),
            Pair("Boys Love", "2435"),
            Pair("Card Battles", "1157"),
            Pair("Carro", "605"),
            Pair("China", "865"),
            Pair("Comédia Romântica", "1254"),
            Pair("Comédia", "5"),
            Pair("Corridas", "1514"),
            Pair("Crime", "1962"),
            Pair("Culinária", "925"),
            Pair("Cultivo", "1133"),
            Pair("Demônio", "19"),
            Pair("Drama", "36"),
            Pair("Ecchi", "49"),
            Pair("Escolar", "140"),
            Pair("Espacial", "646"),
            Pair("Esporte", "106"),
            Pair("Família", "1431"),
            Pair("Fantasia", "6"),
            Pair("Ficção Científica", "99"),
            Pair("Ficção Mítica", "1575"),
            Pair("Gathering", "2756"),
            Pair("Gourmet", "2813"),
            Pair("Harém", "189"),
            Pair("Histórico", "20"),
            Pair("Horror", "256"),
            Pair("Insanidade", "387"),
            Pair("Isekai", "10"),
            Pair("Jogos", "63"),
            Pair("Josei", "733"),
            Pair("Magia", "82"),
            Pair("Maid", "2772"),
            Pair("Mecha", "200"),
            Pair("Militar", "58"),
            Pair("Mistério", "50"),
            Pair("Musical", "112"),
            Pair("Novel", "951"),
            Pair("Paródia", "171"),
            Pair("Policial", "249"),
            Pair("Psicológico", "66"),
            Pair("Pós-Apocalíptico", "470"),
            Pair("Reencarnação", "1134"),
            Pair("Romance", "7"),
            Pair("Samurai", "127"),
            Pair("Sci-fi", "203"),
            Pair("Seinen", "51"),
            Pair("Seven", "1449"),
            Pair("Shoujo Ai", "507"),
            Pair("Shoujo", "78"),
            Pair("Shounen Ai", "1326"),
            Pair("Shounen", "17"),
            Pair("Slice of Life", "79"),
            Pair("Sobrenatural", "8"),
            Pair("Studio Deen", "2451"),
            Pair("Sunrise", "318"),
            Pair("Super Poder", "18"),
            Pair("Suspense", "134"),
            Pair("Terror", "42"),
            Pair("Thriller", "960"),
            Pair("Tragédia", "264"),
            Pair("Vampiros", "358"),
            Pair("Vida Diaria", "1518"),
            Pair("Vida Escolar", "67"),
            Pair("Violência", "59"),
            Pair("Yaoi", "1386"),
            Pair("Yuri", "243"),
            Pair("Zumbi", "574")
        )
    }
}
