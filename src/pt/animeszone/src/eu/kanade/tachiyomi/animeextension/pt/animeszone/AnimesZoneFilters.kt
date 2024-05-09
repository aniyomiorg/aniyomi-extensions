package eu.kanade.tachiyomi.animeextension.pt.animeszone

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimesZoneFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)
    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(name: String): String {
        return (first { it is R } as QueryPartFilter).toQueryPart()
            .takeUnless(String::isEmpty)
            ?.let { "&$name=$it" }
            .orEmpty()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, String>>,
        name: String,
    ): String {
        return (first { it is R } as CheckBoxFilterList).state
            .asSequence()
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .filter(String::isNotBlank)
            .joinToString("%2C") { "&$name=$it" }
    }

    class GenreFilter : CheckBoxFilterList(
        "Selecionar Gêneros",
        AnimesZoneFiltersData.GENRE.map { CheckBoxVal(it.first, false) },
    )

    class YearFilter : QueryPartFilter("Ano", AnimesZoneFiltersData.YEAR)

    class VersionFilter : QueryPartFilter("Versão", AnimesZoneFiltersData.VERSION)

    class StudioFilter : CheckBoxFilterList(
        "Estudio",
        AnimesZoneFiltersData.STUDIO.map { CheckBoxVal(it.first, false) },
    )

    class TypeFilter : QueryPartFilter("Tipo", AnimesZoneFiltersData.TYPE)

    class AdultFilter : QueryPartFilter("Adulto", AnimesZoneFiltersData.ADULT)

    val FILTER_LIST get() = AnimeFilterList(
        GenreFilter(),
        StudioFilter(),
        YearFilter(),
        VersionFilter(),
        TypeFilter(),
        AdultFilter(),
    )

    data class FilterSearchParams(
        val genre: String = "",
        val year: String = "",
        val version: String = "",
        val studio: String = "",
        val type: String = "",
        val adult: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseCheckbox<GenreFilter>(AnimesZoneFiltersData.GENRE, "_generos"),
            filters.asQueryPart<YearFilter>("_versao"),
            filters.asQueryPart<VersionFilter>("_tipo"),
            filters.parseCheckbox<StudioFilter>(AnimesZoneFiltersData.STUDIO, "_estudio"),
            filters.asQueryPart<TypeFilter>("_tipototal"),
            filters.asQueryPart<AdultFilter>("_adulto"),
        )
    }

    private object AnimesZoneFiltersData {
        val ANY = Pair("Selecione", "")

        val GENRE = arrayOf(
            Pair("Comédia", "comedia"),
            Pair("Ação", "acao"),
            Pair("Fantasia", "fantasia"),
            Pair("Romance", "romance"),
            Pair("Drama", "drama"),
            Pair("Escolar", "escolar"),
            Pair("Aventura", "aventura"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Slice-of-life", "slice-of-life"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Ecchi", "ecchi"),
            Pair("Mistério", "misterio"),
            Pair("Seinen", "seinen"),
            Pair("Magia", "magia"),
            Pair("Animação", "animacao"),
            Pair("Harem", "harem"),
            Pair("Psicológico", "psicologico"),
            Pair("Super Poder", "super-poder"),
            Pair("Violência", "violencia"),
            Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
            Pair("Histórico", "historico"),
            Pair("Isekai", "isekai"),
            Pair("Mecha", "mecha"),
            Pair("Demónio", "demonio"),
            Pair("Terror", "terror"),
            Pair("Esportes", "esportes"),
            Pair("Militar", "militar"),
            Pair("Artes Marciais", "artes-marciais"),
            Pair("Jogo", "jogo"),
            Pair("Vampiro", "vampiro"),
            Pair("Musical", "musical"),
            Pair("Suspense", "suspense"),
            Pair("Paródia", "parodia"),
            Pair("Shoujo", "shoujo"),
            Pair("Nudez", "nudez"),
            Pair("Supernatural", "supernatural"),
            Pair("Espacial", "espacial"),
            Pair("Shoujo-ai", "shoujo-ai"),
            Pair("Crime", "crime"),
            Pair("Policial", "policial"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Samurai", "samurai"),
            Pair("Josei", "josei"),
            Pair("Action & Adventure", "action-adventure"),
            Pair("Amizade", "amizade"),
            Pair("Horror", "horror"),
            Pair("Família", "familia"),
            Pair("Música", "musica"),
            Pair("Insanidade", "insanidade"),
            Pair("Obsceno", "obsceno"),
            Pair("Shounen-ai", "shounen-ai"),
            Pair("Carros", "carros"),
            Pair("Gore", "gore"),
            Pair("War & Politics", "war-politics"),
            Pair("Yaoi", "yaoi"),
            Pair("Cinema TV", "cinema-tv"),
            Pair("Gourmet", "gourmet"),
            Pair("Infantil", "infantil"),
            Pair("Vida Escolar", "vida-escolar"),
        )

        val YEAR = arrayOf(ANY) + (1986..2024).map {
            Pair(it.toString(), it.toString())
        }.reversed().toTypedArray()

        val VERSION = arrayOf(
            ANY,
            Pair("Legendados", "legendada"),
            Pair("Dublado", "series02"),
            Pair("Seção de Hentais", "series03"),
        )

        val STUDIO = arrayOf(
            Pair("J.C.Staff", "j-c-staff"),
            Pair("Shueisha", "shueisha"),
            Pair("Aniplex", "aniplex"),
            Pair("BONES", "bones"),
            Pair("Kadokawa", "kadokawa"),
            Pair("TOHO Animation", "toho-animation"),
            Pair("Pony Canyon", "pony-canyon"),
            Pair("A-1 Pictures", "a-1-pictures"),
            Pair("DENTSU", "dentsu"),
            Pair("Kodansha", "kodansha"),
            Pair("Production I.G", "production-i-g"),
            Pair("CloverWorks", "cloverworks"),
            Pair("Madhouse", "madhouse"),
            Pair("Bit grooove promotion", "bit-grooove-promotion"),
            Pair("MAPPA", "mappa"),
            Pair("SILVER LINK.", "silver-link"),
            Pair("Wit Studio", "wit-studio"),
            Pair("Magic Capsule", "magic-capsule"),
            Pair("OLM", "olm"),
            Pair("Lantis", "lantis"),
            Pair("Movic", "movic"),
            Pair("SATELIGHT", "satelight"),
            Pair("Shogakukan-Shueisha Productions", "shogakukan-shueisha-productions"),
            Pair("Square Enix", "square-enix"),
            Pair("STUDIO MAUSU", "studio-mausu"),
            Pair("Yomiuri Telecasting Corporation", "yomiuri-telecasting-corporation"),
            Pair("Bandai Namco Arts", "bandai-namco-arts"),
            Pair("David Production", "david-production"),
            Pair("EGG FIRM", "egg-firm"),
            Pair("Lerche", "lerche"),
            Pair("Liden Films", "liden-films"),
            Pair("Sony Music Entertainment", "sony-music-entertainment-japan"),
            Pair("Studio Deen", "studio-deen"),
            Pair("TMS Entertainment", "tms-entertainment"),
            Pair("Toho", "toho"),
            Pair("Crunchyroll", "crunchyroll"),
            Pair("dugout", "dugout"),
            Pair("ENGI", "engi"),
            Pair("MBS", "mbs"),
            Pair("P.A.Works", "p-a-works"),
            Pair("Tezuka Productions", "tezuka-productions"),
            Pair("TV Tokyo", "tv-tokyo"),
            Pair("Warner Bros. Japan", "warner-bros-japan"),
            Pair("White Fox", "white-fox"),
            Pair("avex pictures", "avex-pictures"),
            Pair("Bibury Animation Studios", "bibury-animation-studios"),
            Pair("Brain's Base", "brains-base"),
            Pair("DMM music", "dmm-music"),
            Pair("DMM pictures", "dmm-pictures"),
            Pair("feel.", "feel"),
            Pair("Hakuhodo DY Music & Pictures", "hakuhodo-dy-music-pictures"),
            Pair("Lidenfilms", "lidenfilms"),
            Pair("MAHO FILM", "maho-film"),
            Pair("NHK Enterprises", "nhk-enterprises"),
            Pair("Passione", "passione"),
            Pair("Pierrot", "pierrot"),
            Pair("Pine Jam", "pine-jam"),
            Pair("Pink Pineapple", "pink-pineapple"),
            Pair("project No.9", "project-no-9"),
            Pair("Seven", "seven"),
            Pair("SHAFT", "shaft"),
            Pair("TNK", "tnk"),
            Pair("Zero-G", "zero-g"),
            Pair("Asahi Production", "asahi-production"),
            Pair("asread", "asread"),
            Pair("AT-X", "at-x"),
            Pair("Bandai Namco Pictures", "bandai-namco-pictures"),
            Pair("BS Fuji", "bs-fuji"),
            Pair("C2C", "c2c"),
            Pair("Children's Playground Entertainment", "childrens-playground-entertainment"),
            Pair("diomedéa", "diomedea"),
            Pair("Doga Kobo", "doga-kobo"),
            Pair("Geno Studio", "geno-studio"),
            Pair("Good Smile Company", "good-smile-company"),
            Pair("Graphinica", "graphinica"),
            Pair("Hakusensha", "hakusensha"),
            Pair("HALF H·P STUDIO", "f279ee47217fbae84c07eb11181f4997"),
            Pair("King Records", "king-records"),
            Pair("Kyoto Animation", "kyoto-animation"),
            Pair("Nippon BS Broadcasting", "nippon-bs-broadcasting"),
            Pair("Nippon Columbia", "nippon-columbia"),
            Pair("Nitroplus", "nitroplus"),
            Pair("Shogakukan", "shogakukan"),
            Pair("Sotsu", "sotsu"),
            Pair("Sound Team・Don Juan", "45e6f4604baaebfbebf4f43139db8d68"),
            Pair("Studio Gokumi", "studio-gokumi"),
            Pair("Suiseisha", "suiseisha"),
            Pair("SUNRISE", "sunrise"),
            Pair("SynergySP", "synergysp"),
            Pair("Techno Sound", "techno-sound"),
            Pair("THE KLOCKWORX", "the-klockworx"),
            Pair("Toei Animation", "toei-animation"),
            Pair("TOY'S FACTORY", "toys-factory"),
            Pair("Twin Engine", "twin-engine"),
            Pair("ufotable", "ufotable"),
            Pair("ABC Animation", "abc-animation"),
            Pair("Ajiado", "ajiado"),
            Pair("APDREAM", "apdream"),
            Pair("Ashi Productions", "ashi-productions"),
        )

        val TYPE = arrayOf(
            ANY,
            Pair("TV Shows", "tvshows"),
            Pair("Filmes", "movies"),
        )

        val ADULT = arrayOf(
            ANY,
            Pair("Hentais", "dublada"),
            Pair("Seção de Hentais", "series03"),
        )
    }
}
