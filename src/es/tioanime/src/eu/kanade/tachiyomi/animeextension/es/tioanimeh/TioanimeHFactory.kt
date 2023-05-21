package eu.kanade.tachiyomi.animeextension.es.tioanimeh

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request

class TioanimeHFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        TioAnime(),
        TioHentai(),
    )
}

class TioAnime : TioanimeH("TioAnime", "https://tioanime.com") {
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = if (filterList.isNotEmpty())filterList.find { it is GenreFilter } as GenreFilter else { GenreFilter().apply { state = 0 } }
        return when {
            query.isNotBlank() -> GET("$baseUrl/directorio?q=$query&p=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/directorio?genero=${genreFilter.toUriPart()}&p=$page")
            else -> GET("$baseUrl/directorio?p=$page ")
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", "all"),
            Pair("Acción", "accion"),
            Pair("Artes Marciales", "artes_marciales"),
            Pair("Aventuras", "aventura"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia_ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Harem", "harem"),
            Pair("Historico", "historico"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la vida", "recuentos_de_la_vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )
}

class TioHentai : TioanimeH("TioHentai", "https://tiohentai.com")
