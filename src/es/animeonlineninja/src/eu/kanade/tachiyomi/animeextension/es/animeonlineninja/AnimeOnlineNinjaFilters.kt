package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeOnlineNinjaFilters {

    open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {

        fun toUriPart() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.asUriPart(): String {
        return getFirst<R>().let {
            (it as UriPartFilter).toUriPart()
        }
    }

    class InvertedResultsFilter : AnimeFilter.CheckBox("Invertir resultados", false)
    class TypeFilter : UriPartFilter("Tipo", AnimesOnlineNinjaData.TYPES)
    class LetterFilter : UriPartFilter("Filtrar por letra", AnimesOnlineNinjaData.LETTERS)

    class GenreFilter : UriPartFilter("Generos", AnimesOnlineNinjaData.GENRES)
    class LanguageFilter : UriPartFilter("Idiomas", AnimesOnlineNinjaData.LANGUAGES)
    class YearFilter : UriPartFilter("Año", AnimesOnlineNinjaData.YEARS)
    class MovieFilter : UriPartFilter("Peliculas", AnimesOnlineNinjaData.MOVIES)

    class OtherOptionsGroup : AnimeFilter.Group<UriPartFilter>(
        "Otros filtros",
        listOf(
            GenreFilter(),
            LanguageFilter(),
            YearFilter(),
            MovieFilter(),
        ),
    )

    private inline fun <reified R> AnimeFilter.Group<UriPartFilter>.getItemUri(): String {
        return state.first { it is R }.toUriPart()
    }

    val FILTER_LIST get() = AnimeFilterList(
        InvertedResultsFilter(),
        TypeFilter(),
        LetterFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Estos filtros no afectan a la busqueda por texto"),
        OtherOptionsGroup(),
    )

    data class FilterSearchParams(
        val isInverted: Boolean = false,
        val type: String = "",
        val letter: String = "",
        val genre: String = "",
        val language: String = "",
        val year: String = "",
        val movie: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val others = filters.getFirst<OtherOptionsGroup>()

        return FilterSearchParams(
            filters.getFirst<InvertedResultsFilter>().state,
            filters.asUriPart<TypeFilter>(),
            filters.asUriPart<LetterFilter>(),
            others.getItemUri<GenreFilter>(),
            others.getItemUri<LanguageFilter>(),
            others.getItemUri<YearFilter>(),
            others.getItemUri<MovieFilter>(),
        )
    }

    private object AnimesOnlineNinjaData {
        val EVERY = Pair("Seleccionar", "")

        val TYPES = arrayOf(
            Pair("Todos", "todos"),
            Pair("Series", "serie"),
            Pair("Peliculas", "pelicula"),
        )

        val LETTERS = arrayOf(EVERY) + ('a'..'z').map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val GENRES = arrayOf(
            EVERY,
            Pair("Sin Censura \uD83D\uDD1E", "sin-censura"),
            Pair("En emisión ⏩", "en-emision"),
            Pair("Blu-Ray / DVD \uD83D\uDCC0", "blu-ray-dvd"),
            Pair("Próximamente", "proximamente"),
            Pair("Live Action \uD83C\uDDEF\uD83C\uDDF5", "live-action"),
            Pair("Popular en la web \uD83D\uDCAB", "tendencias"),
            Pair("Mejores valorados ⭐", "ratings"),
        )

        val LANGUAGES = arrayOf(
            EVERY,
            Pair("Audio Latino \uD83C\uDDF2\uD83C\uDDFD", "audio-latino"),
            Pair("Audio Castellano \uD83C\uDDEA\uD83C\uDDF8", "anime-castellano"),
        )

        val YEARS = arrayOf(EVERY) + (2024 downTo 1979).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val MOVIES = arrayOf(
            EVERY,
            Pair("Anime ㊗️", "pelicula"),
            Pair("Live Action \uD83C\uDDEF\uD83C\uDDF5", "live-action"),
        )
    }
}
