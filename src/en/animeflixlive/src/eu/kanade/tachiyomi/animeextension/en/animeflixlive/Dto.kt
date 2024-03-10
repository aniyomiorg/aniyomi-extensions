package eu.kanade.tachiyomi.animeextension.en.animeflixlive

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import kotlin.math.ceil
import kotlin.math.floor

@Serializable
class TrendingDto(
    val trending: List<AnimeDto>,
)

@Serializable
class AnimeDto(
    val slug: String,
    @SerialName("title") val titleObj: TitleObject,
    val images: ImageObject,
) {
    @Serializable
    class TitleObject(
        val english: String? = null,
        val native: String? = null,
        val romaji: String? = null,
    )

    @Serializable
    class ImageObject(
        val large: String? = null,
        val medium: String? = null,
        val small: String? = null,
    )

    fun toSAnime(titlePref: String): SAnime = SAnime.create().apply {
        title = when (titlePref) {
            "English" -> titleObj.english ?: titleObj.romaji ?: titleObj.native ?: "Title N/A"
            "Romaji" -> titleObj.romaji ?: titleObj.english ?: titleObj.native ?: "Title N/A"
            else -> titleObj.native ?: titleObj.romaji ?: titleObj.english ?: "Title N/A"
        }
        thumbnail_url = images.large ?: images.medium ?: images.small ?: ""
        url = slug
    }
}

@Serializable
class DetailsDto(
    val slug: String,
    @SerialName("title") val titleObj: TitleObject,
    val description: String,
    val genres: List<String>,
    val status: String? = null,
    val images: ImageObject,
) {
    @Serializable
    class TitleObject(
        val english: String? = null,
        val native: String? = null,
        val romaji: String? = null,
    )

    @Serializable
    class ImageObject(
        val large: String? = null,
        val medium: String? = null,
        val small: String? = null,
    )

    fun toSAnime(titlePref: String): SAnime = SAnime.create().apply {
        title = when (titlePref) {
            "English" -> titleObj.english ?: titleObj.romaji ?: titleObj.native ?: "Title N/A"
            "Romaji" -> titleObj.romaji ?: titleObj.english ?: titleObj.native ?: "Title N/A"
            else -> titleObj.native ?: titleObj.romaji ?: titleObj.english ?: "Title N/A"
        }
        thumbnail_url = images.large ?: images.medium ?: images.small ?: ""
        url = slug
        genre = genres.joinToString()
        status = this@DetailsDto.status.parseStatus()
        description = Jsoup.parseBodyFragment(
            this@DetailsDto.description.replace("<br>", "br2n"),
        ).text().replace("br2n", "\n")
    }

    private fun String?.parseStatus(): Int = when (this?.lowercase()) {
        "releasing" -> SAnime.ONGOING
        "finished" -> SAnime.COMPLETED
        "cancelled" -> SAnime.CANCELLED
        else -> SAnime.UNKNOWN
    }
}

@Serializable
class EpisodeResponseDto(
    val episodes: List<EpisodeDto>,
) {
    @Serializable
    class EpisodeDto(
        val number: Float,
        val title: String? = null,
    ) {
        fun toSEpisode(slug: String): SEpisode = SEpisode.create().apply {
            val epNum = if (floor(number) == ceil(number)) {
                number.toInt().toString()
            } else {
                number.toString()
            }

            url = "/watch/$slug-episode-$epNum"
            episode_number = number
            name = if (title == null) {
                "Episode $epNum"
            } else {
                "Ep. $epNum - $title"
            }
        }
    }
}

@Serializable
class ServerDto(
    val source: String,
)
