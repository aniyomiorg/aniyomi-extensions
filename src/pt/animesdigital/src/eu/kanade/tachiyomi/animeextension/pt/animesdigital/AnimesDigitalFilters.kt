package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimesDigitalFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, values: List<TriFilterVal>) : AnimeFilter.Group<TriState>(name, values)
    class TriFilterVal(name: String) : TriState(name)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(
        options: Array<Pair<String, String>>,
    ): List<List<String>> {
        return (first { it is R } as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to options.find { it.first == filter.name }!!.second }
            .groupBy { it.first } // group by state
            .let { dict ->
                val included = dict.get(TriState.STATE_INCLUDE)?.map { it.second }.orEmpty()
                val excluded = dict.get(TriState.STATE_EXCLUDE)?.map { it.second }.orEmpty()
                listOf(included, excluded)
            }
    }

    class InitialLetterFilter : QueryPartFilter("Primeira letra", AnimesDigitalFiltersData.INITIAL_LETTER)
    class AudioFilter : QueryPartFilter("Língua/Áudio", AnimesDigitalFiltersData.AUDIOS)
    class TypeFilter : QueryPartFilter("Tipo", AnimesDigitalFiltersData.TYPES)

    class GenresFilter : TriStateFilterList(
        "Gêneros",
        AnimesDigitalFiltersData.GENRES.map { TriFilterVal(it.first) },
    )

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            InitialLetterFilter(),
            AudioFilter(),
            TypeFilter(),
            AnimeFilter.Separator(),
            GenresFilter(),
        )

    data class FilterSearchParams(
        val initialLetter: String = "0",
        val audio: String = "0",
        val type: String = "Anime",
        val genres: List<String> = emptyList(),
        val deleted_genres: List<String> = emptyList(),
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()
        val (added, deleted) = filters.parseTriFilter<GenresFilter>(AnimesDigitalFiltersData.GENRES)

        return FilterSearchParams(
            filters.asQueryPart<InitialLetterFilter>(),
            filters.asQueryPart<AudioFilter>(),
            filters.asQueryPart<TypeFilter>(),
            added,
            deleted,
        )
    }

    private object AnimesDigitalFiltersData {
        val INITIAL_LETTER = arrayOf(Pair("Selecione", "0")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val AUDIOS = arrayOf(
            Pair("Todos", "0"),
            Pair("Legendado", "legendado"),
            Pair("Dublado", "dublado"),
        )

        val TYPES = arrayOf(
            Pair("Animes", "Anime"),
            Pair("Desenhos", "Desenho"),
            Pair("Doramas", "Dorama"),
            Pair("Tokusatsus", "Tokusatsus"),
        )

        val GENRES = arrayOf(
            Pair("Ação", "10"),
            Pair("Adaptação de Manga", "58"),
            Pair("Adolescente", "149"),
            Pair("Adventure", "100"),
            Pair("Amadurecimento", "207"),
            Pair("Animação", "45"),
            Pair("Aniplex", "201"),
            Pair("Artes Marciais", "13"),
            Pair("Aventura", "11"),
            Pair("Baseball", "96"),
            Pair("Bishounen", "36"),
            Pair("Bolos", "194"),
            Pair("Boys Love", "205"),
            Pair("Cartas", "83"),
            Pair("Clubes", "110"),
            Pair("Clubs", "185"),
            Pair("Comédia", "17"),
            Pair("Cotidiano", "118"),
            Pair("Cozinha", "195"),
            Pair("Crianças", "79"),
            Pair("Culinária", "172"),
            Pair("Cyberpunk Sci-Fi", "128"),
            Pair("Dark Fantasy", "141"),
            Pair("Demência", "105"),
            Pair("Demônio", "77"),
            Pair("Deusas", "152"),
            Pair("Dorama", "182"),
            Pair("Drama", "19"),
            Pair("Dramas Coreanos", "183"),
            Pair("Ecchi", "26"),
            Pair("Elfos", "188"),
            Pair("Escolar", "40"),
            Pair("Espacial", "103"),
            Pair("Espaço", "108"),
            Pair("Espionagem", "150"),
            Pair("Esporte", "29"),
            Pair("Esportes", "52"),
            Pair("eSports", "180"),
            Pair("Família", "121"),
            Pair("Fantasia", "25"),
            Pair("Fantasia científica", "192"),
            Pair("Fatia de Vida", "146"),
            Pair("Ficção", "98"),
            Pair("Ficção Científica", "27"),
            Pair("Ficção de aventura", "161"),
            Pair("Filme de super-herói", "46"),
            Pair("Fuji TV.", "202"),
            Pair("Futebol", "111"),
            Pair("Game", "47"),
            Pair("Gourmet", "209"),
            Pair("Harém?", "20"),
            Pair("Historia", "122"),
            Pair("História de super-herói", "162"),
            Pair("Histórico", "54"),
            Pair("Horror", "78"),
            Pair("Horror e Mistério", "198"),
            Pair("Idol", "211"),
            Pair("Infantil", "112"),
            Pair("Insanidade", "197"),
            Pair("Isekai", "51"),
            Pair("Jogo", "48"),
            Pair("Jogos", "38"),
            Pair("Josei", "84"),
            Pair("Juujin", "153"),
            Pair("Kodomo", "39"),
            Pair("Light novel", "99"),
            Pair("Live Action", "179"),
            Pair("Lolicon", "191"),
            Pair("Luta", "189"),
            Pair("Magia", "33"),
            Pair("Magica", "61"),
            Pair("Mahou Shoujo", "157"),
            Pair("Mangá", "117"),
            Pair("Mecha", "37"),
            Pair("Mechas", "143"),
            Pair("Medieval", "144"),
            Pair("Melodrama", "184"),
            Pair("Militar", "55"),
            Pair("Mistério", "31"),
            Pair("Música", "50"),
            Pair("Novel", "107"),
            Pair("Nudez", "181"),
            Pair("Paródia", "72"),
            Pair("Pastelão", "147"),
            Pair("Piratas", "217"),
            Pair("Policial", "60"),
            Pair("Programa de TV japoneses", "139"),
            Pair("Programa infantis", "175"),
            Pair("Programas e séries brasileiras", "176"),
            Pair("Programas Infantis", "178"),
            Pair("Psicológico", "71"),
            Pair("Realidade Virtual", "164"),
            Pair("Robô", "145"),
            Pair("Romance", "21"),
            Pair("Samurai", "57"),
            Pair("sci-fi", "49"),
            Pair("Seinen", "23"),
            Pair("Série baseado em mangás", "138"),
            Pair("Série baseado em quadrinhos", "87"),
            Pair("Séries", "65"),
            Pair("Shonen", "127"),
            Pair("Shoujo", "32"),
            Pair("Shoujo Mahou", "216"),
            Pair("Shoujo-ai", "59"),
            Pair("Shounen", "14"),
            Pair("Shounen-ai", "95"),
            Pair("Slice Of Life", "35"),
            Pair("Sobrenatural", "16"),
            Pair("Sports", "186"),
            Pair("Steampunk", "80"),
            Pair("Super Heróis", "148"),
            Pair("Super Poderes", "12"),
            Pair("Superaventura", "170"),
            Pair("Superhero fiction", "173"),
            Pair("Supernatural", "86"),
            Pair("Superpoderes", "41"),
            Pair("Suspense", "15"),
            Pair("Terror", "90"),
            Pair("Thriller", "94"),
            Pair("TMS Entertainment", "104"),
            Pair("Tokusatsu", "171"),
            Pair("Tragédia", "85"),
            Pair("Vampiro.", "106"),
            Pair("Vida Colegial", "196"),
            Pair("Vida Cotidiana", "142"),
            Pair("Vida de trabalho", "206"),
            Pair("Vida Diaria", "97"),
            Pair("Vida Escolar", "22"),
            Pair("Violência.", "56"),
            Pair("Violentos", "167"),
            Pair("Visual Novel", "129"),
            Pair("White Fox", "109"),
            Pair("WIT", "213"),
            Pair("Yaoi", "115"),
            Pair("Yuri", "34"),
        )
    }
}
