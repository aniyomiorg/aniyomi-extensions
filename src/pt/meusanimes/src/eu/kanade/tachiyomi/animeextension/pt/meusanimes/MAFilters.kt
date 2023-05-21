package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MAFilters {

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
        return this.filterIsInstance<R>().joinToString("") {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class LetterFilter : QueryPartFilter("Letra inicial", MAFiltersData.LETTERS)
    class YearFilter : QueryPartFilter("Ano", MAFiltersData.YEARS)
    class AudioFilter : QueryPartFilter("Áudio", MAFiltersData.AUDIO)
    class GenreFilter : QueryPartFilter("Gênero", MAFiltersData.GENRES)

    val FILTER_LIST = AnimeFilterList(
        AnimeFilter.Header(MAFiltersData.IGNORE_SEARCH_MSG),
        LetterFilter(),
        AnimeFilter.Header(MAFiltersData.IGNORE_LETTER_MSG),
        YearFilter(),
        AnimeFilter.Header(MAFiltersData.IGNORE_YEAR_MSG),
        AudioFilter(),
        AnimeFilter.Header(MAFiltersData.IGNORE_AUDIO_MSG),
        GenreFilter(),
    )

    data class FilterSearchParams(
        val letter: String = "",
        val year: String = "",
        val audio: String = "",
        val genre: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        return FilterSearchParams(
            filters.asQueryPart<LetterFilter>(),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<AudioFilter>(),
            filters.asQueryPart<GenreFilter>(),
        )
    }

    private object MAFiltersData {
        const val IGNORE_SEARCH_MSG = "NOTA: Os filtros abaixo irão IGNORAR a pesquisa por nome."
        const val IGNORE_LETTER_MSG = "NOTA: O filtro por ano IGNORA o por letra."
        const val IGNORE_YEAR_MSG = "NOTA: O filtro de áudio IGNORA o por ano."
        const val IGNORE_AUDIO_MSG = "NOTA: O filtro de gêneros IGNORA o de áudio."
        val EVERY = Pair("Selecione", "")

        val LETTERS = arrayOf(EVERY) + ('a'..'z').map {
            Pair(it.toString().uppercase(), it.toString())
        }.toTypedArray()

        val YEARS = arrayOf(EVERY) + (2023 downTo 1985).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val AUDIO = arrayOf(
            EVERY,
            Pair("Dublado", "Dublado"),
            Pair("Legendado", "Legendado"),
        )

        val GENRES = arrayOf(
            EVERY,
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Aventura", "aventura"),
            Pair("Ação", "acao"),
            Pair("Comédia", "comedia"),
            Pair("Demônio", "demonio"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Esportes", "esportes"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Jogos", "jogos"),
            Pair("Josei", "josei"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Musical", "musical"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo-ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen-ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoder", "superpoder"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Vampiros", "vampiros"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}
