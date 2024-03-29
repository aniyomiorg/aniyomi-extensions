package eu.kanade.tachiyomi.animeextension.es.beatzanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class UriPartFilter(
    name: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: String? = null,
) : AnimeFilter.Select<String>(
    name,
    vals.map { it.first }.toTypedArray(),
    vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
) {
    fun getValue(): String {
        return vals[state].second
    }
}

class SourceFilter : UriPartFilter(
    "Status",
    arrayOf(
        Pair("Todos", ""),
        Pair("BDRip", "BDRip"),
        Pair("WebRip", "WebRip"),
    ),
)

class StatusFilter : UriPartFilter(
    "Estado",
    arrayOf(
        Pair("Todos", ""),
        Pair("En Emision", "En Emision"),
        Pair("Finalizado", "Finalizado"),
        Pair("En Proceso", "En Proceso"),
    ),
)

class TypeFilter : UriPartFilter(
    "Tipo",
    arrayOf(
        Pair("Todos", ""),
        Pair("Serie", "Serie"),
        Pair("Pelicula", "Pelicula"),
    ),
)
