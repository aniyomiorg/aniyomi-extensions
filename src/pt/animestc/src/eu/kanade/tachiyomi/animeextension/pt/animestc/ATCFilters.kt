package eu.kanade.tachiyomi.animeextension.pt.animestc

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object ATCFilters {
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

    class TypeFilter : QueryPartFilter("Tipo", ATCFiltersData.TYPES)
    class YearFilter : QueryPartFilter("Ano", ATCFiltersData.YEARS)
    class GenreFilter : QueryPartFilter("Gênero", ATCFiltersData.GENRES)
    class StatusFilter : QueryPartFilter("Status", ATCFiltersData.STATUS)

    val FILTER_LIST get() = AnimeFilterList(
        TypeFilter(),
        YearFilter(),
        GenreFilter(),
        StatusFilter(),
    )

    data class FilterSearchParams(
        val type: String = "series",
        val year: String = "",
        val genre: String = "",
        val status: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.asQueryPart<TypeFilter>(),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<GenreFilter>(),
            filters.asQueryPart<StatusFilter>(),
        )
    }

    private object ATCFiltersData {
        val TYPES = arrayOf(
            Pair("Anime", "series"),
            Pair("Filme", "movie"),
            Pair("OVA", "ova"),
        )

        val SELECT = Pair("Selecione", "")

        val STATUS = arrayOf(
            SELECT,
            Pair("Cancelado", "canceled"),
            Pair("Completo", "complete"),
            Pair("Em Lançamento", "airing"),
            Pair("Pausado", "onhold"),
        )

        val YEARS = arrayOf(SELECT) + (1997..2024).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRES = arrayOf(
            SELECT,
            Pair("Ação", "acao"),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Artes Marcial", "artes-marcial"),
            Pair("Aventura", "aventura"),
            Pair("Beisebol", "beisebol"),
            Pair("Boys Love", "boys-love"),
            Pair("Comédia", "comedia"),
            Pair("Comédia Romântica", "comedia-romantica"),
            Pair("Comedy", "comedy"),
            Pair("Crianças", "criancas"),
            Pair("Culinária", "culinaria"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Demônios", "demonios"),
            Pair("Distopia", "distopia"),
            Pair("Documentário", "documentario"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escola", "escola"),
            Pair("Escolar", "escolar"),
            Pair("Espaço", "espaco"),
            Pair("Esporte", "esporte"),
            Pair("Esportes", "esportes"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Futebol", "futebol"),
            Pair("Game", "game"),
            Pair("Girl battleships", "girl-battleships"),
            Pair("Gourmet", "gourmet"),
            Pair("Gundam", "gundam"),
            Pair("Harém", "harem"),
            Pair("Hentai", "hentai"),
            Pair("Historia", "historia"),
            Pair("Historial", "historial"),
            Pair("Historical", "historical"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Humor Negro", "humor-negro"),
            Pair("Ídolo", "idolo"),
            Pair("Infantis", "infantis"),
            Pair("Investigação", "investigacao"),
            Pair("Isekai", "isekai"),
            Pair("Jogo", "jogo"),
            Pair("Jogos", "jogos"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Luta", "luta"),
            Pair("Maduro", "maduro"),
            Pair("Máfia", "mafia"),
            Pair("Magia", "magia"),
            Pair("Mágica", "magica"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Militares", "militares"),
            Pair("Mistério", "misterio"),
            Pair("Música", "musica"),
            Pair("Musical", "musical"),
            Pair("Não Informado!", "nao-informado"),
            Pair("Paródia", "parodia"),
            Pair("Piratas", "piratas"),
            Pair("Polícia", "policia"),
            Pair("Policial", "policial"),
            Pair("Político", "politico"),
            Pair("Pós-Apocalíptico", "pos-apocaliptico"),
            Pair("Psico", "psico"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Samurais", "samurais"),
            Pair("Sátiro", "satiro"),
            Pair("School Life", "school-life"),
            Pair("SciFi", "scifi"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shotacon", "shotacon"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounem", "shounem"),
            Pair("Shounen", "shounen"),
            Pair("Shounen-ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Space", "space"),
            Pair("Supernatural", "supernatural"),
            Pair("Super Poder", "super-poder"),
            Pair("Super-Poderes", "super-poderes"),
            Pair("Suspense", "suspense"),
            Pair("tear-studio", "tear-studio"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Tragédia", "tragedia"),
            Pair("Vampiro", "vampiro"),
            Pair("Vampiros", "vampiros"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Zombie", "zombie"),
        )
    }
}
