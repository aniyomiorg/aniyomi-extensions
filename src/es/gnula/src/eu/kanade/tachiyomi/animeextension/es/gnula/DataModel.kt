package eu.kanade.tachiyomi.animeextension.es.gnula

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------Popular Model-------------------------
@Serializable
data class PopularModel(
    val props: Props = Props(),
    val page: String? = null,
    val query: Query? = null,
)

@Serializable
data class Props(
    val pageProps: PageProps = PageProps(),
    @SerialName("__N_SSG")
    val nSsg: Boolean = false,
)

@Serializable
data class PageProps(
    val currentPage: String? = null,
    val results: Results = Results(),
)

@Serializable
data class Results(
    @SerialName("__typename")
    val typename: String? = null,
    val pages: Long? = null,
    val data: List<Daum> = emptyList(),
)

@Serializable
data class Daum(
    val titles: Titles = Titles(),
    @SerialName("TMDbId")
    val tmdbId: String? = null,
    val images: Images = Images(),
    val releaseDate: String? = null,
    val slug: Slug = Slug(),
)

@Serializable
data class Titles(
    val name: String? = null,
)

@Serializable
data class Images(
    val poster: String? = null,
)

@Serializable
data class Slug(
    val name: String? = null,
)

@Serializable
data class Query(
    val slug: List<String> = emptyList(),
)

// ---------------------Season Model-------------------------

@Serializable
data class SeasonModel(
    val props: SeasonProps = SeasonProps(),
    val page: String,
    val query: SeasonQuery = SeasonQuery(),
)

@Serializable
data class SeasonProps(
    val pageProps: SeasonPageProps = SeasonPageProps(),
    @SerialName("__N_SSG")
    val nSsg: Boolean = false,
)

@Serializable
data class SeasonPageProps(
    val post: SeasonPost = SeasonPost(),
)

@Serializable
data class SeasonPost(
    @SerialName("TMDbId")
    val tmdbId: String? = null,
    val titles: SeasonTitles = SeasonTitles(),
    val images: SeasonImages = SeasonImages(),
    val overview: String? = null,
    val genres: List<SeasonGenre> = emptyList(),
    val cast: SeasonCast = SeasonCast(),
    val slug: SeasonSlug = SeasonSlug(),
    val releaseDate: String? = null,
    val seasons: List<Season> = emptyList(),
)

@Serializable
data class SeasonTitles(
    val name: String? = null,
    val original: Original? = null,
)

@Serializable
data class Original(
    val name: String? = null,
)

@Serializable
data class SeasonImages(
    val poster: String? = null,
    val backdrop: String? = null,
)

@Serializable
data class SeasonGenre(
    val name: String? = null,
)

@Serializable
data class SeasonCast(
    val acting: List<Acting> = emptyList(),
    val directing: List<Directing> = emptyList(),
    val production: List<Production> = emptyList(),
)

@Serializable
data class Acting(
    val name: String? = null,
)

@Serializable
data class Directing(
    val name: String? = null,
)

@Serializable
data class Production(
    val name: String? = null,
)

@Serializable
data class SeasonSlug(
    val name: String? = null,
)

@Serializable
data class Season(
    val number: Long? = null,
    val episodes: List<SeasonEpisode> = emptyList(),
)

@Serializable
data class SeasonEpisode(
    val title: String? = null,
    @SerialName("TMDbId")
    val tmdbId: String? = null,
    val number: Long? = null,
    val releaseDate: String? = null,
    val image: String? = null,
    val slug: Slug2 = Slug2(),
)

@Serializable
data class Slug2(
    val name: String? = null,
    val season: String? = null,
    val episode: String? = null,
)

@Serializable
data class SeasonQuery(
    val slug: String? = null,
)
