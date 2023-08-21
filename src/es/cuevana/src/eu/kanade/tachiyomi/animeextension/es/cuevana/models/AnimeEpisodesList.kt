package eu.kanade.tachiyomi.animeextension.es.cuevana.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeEpisodesList(
    @SerialName("props") var props: Props? = Props(),
    @SerialName("page") var page: String? = null,
    @SerialName("query") var query: Query? = Query(),
    @SerialName("buildId") var buildId: String? = null,
    @SerialName("isFallback") var isFallback: Boolean? = null,
    @SerialName("gsp") var gsp: Boolean? = null,
    @SerialName("locale") var locale: String? = null,
    @SerialName("locales") var locales: ArrayList<String> = arrayListOf(),
    @SerialName("defaultLocale") var defaultLocale: String? = null,
    @SerialName("scriptLoader") var scriptLoader: ArrayList<String> = arrayListOf(),
)

@Serializable
data class Episodes(
    @SerialName("title") var title: String? = null,
    @SerialName("TMDbId") var TMDbId: String? = null,
    @SerialName("number") var number: Int? = null,
    @SerialName("releaseDate") var releaseDate: String? = null,
    @SerialName("image") var image: String? = null,
    @SerialName("url") var url: Url? = Url(),
    @SerialName("slug") var slug: Slug? = Slug(),
)

@Serializable
data class Seasons(
    @SerialName("number") var number: Int? = null,
    @SerialName("episodes") var episodes: ArrayList<Episodes> = arrayListOf(),
)

@Serializable
data class Original(
    @SerialName("name") var name: String? = null,
)

@Serializable
data class ThisSerie(
    @SerialName("TMDbId") var TMDbId: String? = null,
    @SerialName("seasons") var seasons: ArrayList<Seasons> = arrayListOf(),
    @SerialName("titles") var titles: Titles? = Titles(),
    @SerialName("images") var images: Images? = Images(),
    @SerialName("overview") var overview: String? = null,
    @SerialName("genres") var genres: ArrayList<Genres> = arrayListOf(),
    @SerialName("cast") var cast: Cast? = Cast(),
    @SerialName("rate") var rate: Rate? = Rate(),
    @SerialName("url") var url: Url? = Url(),
    @SerialName("slug") var slug: Slug? = Slug(),
    @SerialName("releaseDate") var releaseDate: String? = null,
)
