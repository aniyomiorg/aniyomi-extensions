package eu.kanade.tachiyomi.animeextension.es.cuevana.models
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PopularAnimeList(
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
data class Titles(
    @SerialName("name") var name: String? = null,
    @SerialName("original") var original: Original? = Original(),
)

@Serializable
data class Images(
    @SerialName("poster") var poster: String? = null,
    @SerialName("backdrop") var backdrop: String? = null,
)

@Serializable
data class Rate(
    @SerialName("average") var average: Double? = null,
    @SerialName("votes") var votes: Int? = null,
)

@Serializable
data class Genres(
    @SerialName("id") var id: String? = null,
    @SerialName("slug") var slug: String? = null,
    @SerialName("name") var name: String? = null,
)

@Serializable
data class Acting(
    @SerialName("id") var id: String? = null,
    @SerialName("name") var name: String? = null,
)

@Serializable
data class Cast(
    @SerialName("acting") var acting: ArrayList<Acting> = arrayListOf(),
    @SerialName("directing") var directing: ArrayList<Directing> = arrayListOf(),
)

@Serializable
data class Url(
    @SerialName("slug") var slug: String? = null,
)

@Serializable
data class Slug(
    @SerialName("name") var name: String? = null,
    @SerialName("season") var season: String? = null,
    @SerialName("episode") var episode: String? = null,
)

@Serializable
data class Movies(
    @SerialName("titles") var titles: Titles? = Titles(),
    @SerialName("images") var images: Images? = Images(),
    @SerialName("rate") var rate: Rate? = Rate(),
    @SerialName("overview") var overview: String? = null,
    @SerialName("TMDbId") var TMDbId: String? = null,
    @SerialName("genres") var genres: ArrayList<Genres> = arrayListOf(),
    @SerialName("cast") var cast: Cast? = Cast(),
    @SerialName("runtime") var runtime: Int? = null,
    @SerialName("releaseDate") var releaseDate: String? = null,
    @SerialName("url") var url: Url? = Url(),
    @SerialName("slug") var slug: Slug? = Slug(),
)

@Serializable
data class PageProps(
    @SerialName("thisSerie") var thisSerie: ThisSerie? = ThisSerie(),
    @SerialName("thisMovie") var thisMovie: ThisMovie? = ThisMovie(),
    @SerialName("movies") var movies: ArrayList<Movies> = arrayListOf(),
    @SerialName("pages") var pages: Int? = null,
    @SerialName("season") var season: Season? = Season(),
    @SerialName("episode") var episode: Episode? = Episode(),
)

@Serializable
data class Props(
    @SerialName("pageProps") var pageProps: PageProps? = PageProps(),
    @SerialName("__N_SSG") var _NSSG: Boolean? = null,
)

@Serializable
data class Query(
    @SerialName("page") var page: String? = null,
    @SerialName("serie") var serie: String? = null,
    @SerialName("movie") var movie: String? = null,
    @SerialName("episode") var episode: String? = null,
    @SerialName("q") var q: String? = null,
)

@Serializable
data class Directing(
    @SerialName("name") var name: String? = null,
)

@Serializable
data class Server(
    @SerialName("cyberlocker") var cyberlocker: String? = null,
    @SerialName("result") var result: String? = null,
    @SerialName("quality") var quality: String? = null,
)

@Serializable
data class Videos(
    @SerialName("latino") var latino: ArrayList<Server> = arrayListOf(),
    @SerialName("spanish") var spanish: ArrayList<Server> = arrayListOf(),
    @SerialName("english") var english: ArrayList<Server> = arrayListOf(),
    @SerialName("japanese") var japanese: ArrayList<Server> = arrayListOf(),
)

@Serializable
data class Downloads(
    @SerialName("cyberlocker") var cyberlocker: String? = null,
    @SerialName("result") var result: String? = null,
    @SerialName("quality") var quality: String? = null,
    @SerialName("language") var language: String? = null,
)

@Serializable
data class ThisMovie(
    @SerialName("TMDbId") var TMDbId: String? = null,
    @SerialName("titles") var titles: Titles? = Titles(),
    @SerialName("images") var images: Images? = Images(),
    @SerialName("overview") var overview: String? = null,
    @SerialName("runtime") var runtime: Int? = null,
    @SerialName("genres") var genres: ArrayList<Genres> = arrayListOf(),
    @SerialName("cast") var cast: Cast? = Cast(),
    @SerialName("rate") var rate: Rate? = Rate(),
    @SerialName("url") var url: Url? = Url(),
    @SerialName("slug") var slug: Slug? = Slug(),
    @SerialName("releaseDate") var releaseDate: String? = null,
    @SerialName("videos") var videos: Videos? = Videos(),
    @SerialName("downloads") var downloads: ArrayList<Downloads> = arrayListOf(),
)

@Serializable
data class Season(
    @SerialName("number") var number: Int? = null,
)

@Serializable
data class NextEpisode(
    @SerialName("title") var title: String? = null,
    @SerialName("slug") var slug: String? = null,
)

@Serializable
data class Episode(
    @SerialName("TMDbId") var TMDbId: String? = null,
    @SerialName("title") var title: String? = null,
    @SerialName("number") var number: Int? = null,
    @SerialName("image") var image: String? = null,
    @SerialName("url") var url: Url? = Url(),
    @SerialName("slug") var slug: Slug? = Slug(),
    @SerialName("nextEpisode") var nextEpisode: NextEpisode? = NextEpisode(),
    @SerialName("previousEpisode") var previousEpisode: String? = null,
    @SerialName("videos") var videos: Videos? = Videos(),
    @SerialName("downloads") var downloads: ArrayList<Downloads> = arrayListOf(),
)
