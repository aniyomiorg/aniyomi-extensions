package eu.kanade.tachiyomi.animeextension.pt.animesaria

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimesAriaFilters {

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
        return this.first { it is R }.let {
            (it as QueryPartFilter).toQueryPart()
        }
    }

    class TypeFilter : QueryPartFilter("Tipo", AnimesAriaFiltersData.types)
    class GenreFilter : QueryPartFilter("Gênero", AnimesAriaFiltersData.genres)
    class StatusFilter : QueryPartFilter("Status", AnimesAriaFiltersData.status)
    class LetterFilter : QueryPartFilter("Letra inicial", AnimesAriaFiltersData.letters)
    class AudioFilter : QueryPartFilter("Áudio", AnimesAriaFiltersData.audio)
    class YearFilter : QueryPartFilter("Ano", AnimesAriaFiltersData.years)
    class SeasonFilter : QueryPartFilter("Temporada", AnimesAriaFiltersData.seasons)

    val filterList = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        StatusFilter(),
        LetterFilter(),
        AudioFilter(),
        YearFilter(),
        SeasonFilter(),
    )

    data class FilterSearchParams(
        val type: String,
        val genre: String,
        val status: String,
        val letter: String,
        val audio: String,
        val year: String,
        val season: String,
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
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

    private object AnimesAriaFiltersData {
        val every = Pair("Todos", "todos")
        val every_f = Pair("Todas", "todas")

        val types = arrayOf(
            every,
            Pair("Série de TV", "serie"),
            Pair("OVA", "ova"),
            Pair("Filme", "filme"),
            Pair("Especial", "especial"),
            Pair("ONA", "ona"),
        )

        val genres = arrayOf(
            every,
            Pair("Ação", "acao"),
            Pair("Artes Maciais", "artes_maciais"),
            Pair("Aventura", "aventura"),
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
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo_ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen_ai"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Super Poder", "super_poder"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Vampiro", "vampiro"),
            Pair("Vida Diária", "vida_diaria"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )

        val status = arrayOf(
            every,
            Pair("Em lançamento", "lancamento"),
            Pair("Finalizado", "finalizado"),
        )

        val letters = arrayOf(every_f) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val audio = arrayOf(
            every,
            Pair("Dublado", "dublado"),
            Pair("Legendado", "legendado"),
        )

        val years = arrayOf(every) + (2023 downTo 1962).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val seasons = arrayOf(
            every_f,
            Pair("Primavera", "primavera"),
            Pair("Verão", "verao"),
            Pair("Outono", "outono"),
            Pair("Inverno", "inverno"),
        )
    }
}
