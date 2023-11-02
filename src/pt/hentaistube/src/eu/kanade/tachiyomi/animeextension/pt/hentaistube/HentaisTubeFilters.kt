package eu.kanade.tachiyomi.animeextension.pt.hentaistube

import eu.kanade.tachiyomi.animeextension.pt.hentaistube.dto.SearchItemDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object HentaisTubeFilters {

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, val vals: Array<String>) :
        AnimeFilter.Group<TriState>(name, vals.map(::TriStateVal))

    private class TriStateVal(name: String) : TriState(name)

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(): List<List<String>> {
        return (getFirst<R>() as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to filter.name }
            .groupBy { it.first } // group by state
            .let { dict ->
                val included = dict.get(TriState.STATE_INCLUDE)?.map { it.second }.orEmpty()
                val excluded = dict.get(TriState.STATE_EXCLUDE)?.map { it.second }.orEmpty()
                listOf(included, excluded)
            }
    }

    class InitialLetterFilter : QueryPartFilter("Primeira letra", HentaisTubeFiltersData.INITIAL_LETTER)

    class SortFilter : AnimeFilter.Sort(
        "Ordem",
        arrayOf("Alfabética"),
        Selection(0, true),
    )

    class GenresFilter : TriStateFilterList("Gêneros", HentaisTubeFiltersData.GENRES)
    class StudiosFilter : TriStateFilterList("Estúdios", HentaisTubeFiltersData.STUDIOS)

    val FILTER_LIST get() = AnimeFilterList(
        InitialLetterFilter(),
        SortFilter(),

        AnimeFilter.Separator(),
        GenresFilter(),
        StudiosFilter(),
    )

    data class FilterSearchParams(
        val initialLetter: String = "",
        val orderAscending: Boolean = true,
        val blackListedGenres: List<String> = emptyList(),
        val includedGenres: List<String> = emptyList(),
        val blackListedStudios: List<String> = emptyList(),
        val includedStudios: List<String> = emptyList(),
        var animeName: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val isAscending = filters.getFirst<SortFilter>().state?.ascending ?: false

        val (includedGenres, excludedGenres) = filters.parseTriFilter<GenresFilter>()
        val (includedStudios, excludedStudios) = filters.parseTriFilter<StudiosFilter>()

        return FilterSearchParams(
            initialLetter = filters.asQueryPart<InitialLetterFilter>(),
            orderAscending = isAscending,
            blackListedGenres = excludedGenres,
            includedGenres = includedGenres,
            blackListedStudios = excludedStudios,
            includedStudios = includedStudios,
        )
    }

    private fun mustRemove(anime: SearchItemDto, params: FilterSearchParams): Boolean {
        return when {
            params.animeName != "" && !anime.title.contains(params.animeName, true) -> true
            params.initialLetter != "" && !anime.title.lowercase().startsWith(params.initialLetter) -> true
            params.blackListedGenres.size > 0 && params.blackListedGenres.any {
                anime.tags.contains(it, true)
            } -> true
            params.includedGenres.size > 0 && params.includedGenres.any {
                !anime.tags.contains(it, true)
            } -> true
            params.blackListedStudios.size > 0 && params.blackListedStudios.any {
                anime.studios.contains(it, true)
            } -> true
            params.includedStudios.size > 0 && params.includedStudios.any {
                !anime.studios.contains(it, true)
            } -> true
            else -> false
        }
    }

    private inline fun <T, R : Comparable<R>> Sequence<T>.sortedByIf(
        isAscending: Boolean,
        crossinline selector: (T) -> R,
    ): Sequence<T> {
        return when {
            isAscending -> sortedBy(selector)
            else -> sortedByDescending(selector)
        }
    }

    fun Sequence<SearchItemDto>.applyFilterParams(params: FilterSearchParams): Sequence<SearchItemDto> {
        return filterNot { mustRemove(it, params) }
            .sortedByIf(params.orderAscending) { it.title.lowercase() }
    }

    private object HentaisTubeFiltersData {
        val INITIAL_LETTER = arrayOf(Pair("Selecione", "")) + ('A'..'Z').map {
            Pair(it.toString(), it.toString().lowercase())
        }.toTypedArray()

        val GENRES = arrayOf(
            "Anal",
            "Aventura",
            "Boquete",
            "Brinquedos",
            "Comédia",
            "Dark Skin",
            "Demônios",
            "Ecchi",
            "Elfos",
            "Empregada",
            "Enfermeira",
            "Esporte",
            "Estupro",
            "Ficção",
            "Futanari",
            "Gay",
            "Harém",
            "Hospital",
            "Incesto",
            "Lactante",
            "Lolicon",
            "Magia",
            "Masturbação",
            "Milf",
            "Mistério",
            "Monstros",
            "Médico",
            "Netorare",
            "Ninjas",
            "Orgia",
            "Peitões",
            "Policial",
            "Professora",
            "Romance",
            "Shotacon",
            "Submissão",
            "Super Poderes",
            "Tentáculos",
            "Terror",
            "Tetas",
            "Travesti",
            "Vampiros",
            "Vida Escolar",
            "Virgem",
            "Yaoi",
            "Yuri",
        )

        val STUDIOS = arrayOf(
            "3D Pix",
            "A1",
            "AIC",
            "APPP",
            "AT2",
            "Actas",
            "Active",
            "Agent 21",
            "Alice Soft",
            "Amam",
            "Amumo",
            "Angelfish",
            "AniMan",
            "Animac",
            "Animate Film",
            "Anime Antenna Iinkai",
            "Animopron",
            "Antechinus",
            "Arms",
            "Asahi Production",
            "BOMB! CUTE! BOMB!",
            "BOOTLEG",
            "Blue Cat",
            "Blue Eyes",
            "Bootleg",
            "BreakBottle",
            "Bunnywalker",
            "CLOCKUP",
            "Central Park Media",
            "Chaos Project",
            "Cherry Lips",
            "ChiChinoya",
            "Chippai",
            "Chocolat",
            "ChuChu",
            "Cinema Paradise",
            "Circle Tribute",
            "Collaboration Works",
            "Comic Media",
            "Cosmo",
            "Cotton Doll",
            "Cranberry",
            "Crimson",
            "D3",
            "Daiei",
            "Deep Forest",
            "Digital Works",
            "Discovery",
            "Dream Force",
            "Dreamroom",
            "EDGE",
            "Easy Film",
            "Echo",
            "Erozuki",
            "Fan",
            "Fans",
            "Filmlink International",
            "Five Ways",
            "Flavors Soft",
            "Front Line",
            "Frontier Works",
            "Frontline",
            "Game 3D",
            "GeG Entertainment",
            "Gold Bear",
            "Goldenboy",
            "Green Bunny",
            "Himajin Planning",
            "Hoods Entertainment",
            "Horipro",
            "Hot Bear",
            "IMK",
            "Innocent Grey",
            "J.C.Staff",
            "Jam",
            "JapanAnime",
            "KSS",
            "Kadokawa Shoten",
            "King Bee",
            "Kitty Media",
            "Knack Productions",
            "Knack",
            "Kusama Art",
            "L.",
            "Leaf",
            "Lemon Heart",
            "Liberty Ship",
            "Lune Pictures",
            "MS Pictures",
            "Majin",
            "Marvelous Entertainment",
            "Mary Jane",
            "Media Blasters",
            "Media Station",
            "Metro Notes",
            "Milkshake",
            "Milky",
            "Mitsu",
            "Moonrock",
            "Moonstone Cherry",
            "Mousou Senka",
            "Museum Pictures",
            "Nihikime no Dozeu",
            "NuTech Digital",
            "Obtain Future",
            "Office Take Off",
            "Orbit",
            "Orc Soft",
            "Original Work",
            "Otodeli",
            "Oz Inc",
            "Oz",
            "Pashmina",
            "Peachpie",
            "Phoenix Entertainment",
            "Pix",
            "Pixy",
            "PoRO",
            "Poly Animation",
            "Poro",
            "Pìnk Pineapple",
            "Queen Bee",
            "SELFISH",
            "Sakura Purin Animation",
            "Schoolzone",
            "Seisei",
            "Selfish",
            "Seven",
            "Shaft",
            "Shelf",
            "Shinkuukan",
            "Shinyusha",
            "Shouten",
            "Showten",
            "Silky’s",
            "SoftCel Pictures",
            "SoftCell Pictures",
            "Sonsan Kikaku",
            "Speed",
            "Studio 9 Maiami",
            "Studio Eromatick",
            "Studio Fantasia",
            "Studio Jack",
            "Studio Sign",
            "Studio Tulip",
            "Studio Unicorn",
            "Sugar Boy",
            "Suzuki Mirano",
            "T-Rex",
            "TDK Core",
            "Toho Company",
            "Top Marschal",
            "Toranoana",
            "Triple X",
            "Tufos",
            "Union Cho",
            "Ursaite",
            "Valkyria",
            "White Bear",
            "Works",
            "YOUC",
            "ZIZ Entertainment",
            "ZIZ",
            "Zealot",
        )
    }
}
