package eu.kanade.tachiyomi.animeextension.pt.animesorion

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimesOrionFilters {

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

    class TypeFilter : QueryPartFilter("Tipo", AnimesOrionFiltersData.TYPES)
    class GenreFilter : QueryPartFilter("Gênero", AnimesOrionFiltersData.GENRES)
    class StatusFilter : QueryPartFilter("Status", AnimesOrionFiltersData.STATUS)
    class LetterFilter : QueryPartFilter("Letra", AnimesOrionFiltersData.LETTERS)

    class AudioFilter : QueryPartFilter("Áudio", AnimesOrionFiltersData.AUDIOS)
    class YearFilter : QueryPartFilter("Ano", AnimesOrionFiltersData.YEARS)
    class SeasonFilter : QueryPartFilter("Temporada", AnimesOrionFiltersData.SEASONS)

    val FILTER_LIST get() = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        StatusFilter(),
        LetterFilter(),
        AudioFilter(),
        YearFilter(),
        SeasonFilter(),
    )

    data class FilterSearchParams(
        val type: String = "todos",
        val genre: String = "todos",
        val status: String = "todos",
        val letter: String = "todas",
        val audio: String = "todos",
        val year: String = "todos",
        val season: String = "todas",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<GenreFilter>(),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<LetterFilter>(),
            filters.asQueryPart<AudioFilter>(),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<SeasonFilter>(),
        )
    }

    private object AnimesOrionFiltersData {
        val EVERY = Pair("<Escolha>", "todos")
        val EVERY_F = Pair("<Escolha>", "todas")

        val TYPES = arrayOf(
            EVERY,
            Pair("Especial", "especial"),
            Pair("Filme", "filme"),
            Pair("ONA", "ona"),
            Pair("OVA", "ova"),
            Pair("Série de TV", "serie"),
        )

        val GENRES = arrayOf(
            EVERY,
            Pair("Artes Maciais", "artes_maciais"),
            Pair("Aventura", "aventura"),
            Pair("Ação", "acao"),
            Pair("Carros", "carros"),
            Pair("Comédia", "comedia"),
            Pair("Demência", "demencia"),
            Pair("Demônios", "demonios"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Erótico", "erotico"),
            Pair("Escolar", "escolar"),
            Pair("Espaço", "espaco"),
            Pair("Esporte", "esporte"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao_cientifica"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Hentai", "hentai"),
            Pair("Histórico", "historico"),
            Pair("Infantil", "infantil"),
            Pair("Jogos", "jogos"),
            Pair("Josei", "josei"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Música", "musica"),
            Pair("Paródia", "parodia"),
            Pair("Polícia", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo Ai", "shoujo_ai"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen Ai", "shounen_ai"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Super Poder", "super_poder"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Vampiro", "vampiro"),
            Pair("Vida Diária", "vida_diaria"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )

        val STATUS = arrayOf(
            EVERY,
            Pair("Em lançamento", "lancamento"),
            Pair("Finalizado", "finalizado"),
        )

        val LETTERS = arrayOf(EVERY_F) + ('a'..'z').map {
            Pair(it.toString().uppercase(), it.toString())
        }.toTypedArray()

        val AUDIOS = arrayOf(
            EVERY,
            Pair("Dublado", "dublado"),
            Pair("Legendado", "legendado"),
        )

        val YEARS = arrayOf(EVERY) + (2023 downTo 1962).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val SEASONS = arrayOf(
            EVERY_F,
            Pair("Inverno", "inverno"),
            Pair("Outono", "outono"),
            Pair("Primavera", "primavera"),
            Pair("Verão", "verao"),
        )
    }
}
