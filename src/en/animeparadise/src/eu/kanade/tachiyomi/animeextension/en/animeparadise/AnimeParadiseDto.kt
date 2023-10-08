package eu.kanade.tachiyomi.animeextension.en.animeparadise

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AnimeListResponse(
    val data: List<AnimeObject>,
) {
    @Serializable
    data class AnimeObject(
        @SerialName("_id")
        val id: String,
        val title: String,
        val link: String,
        val posterImage: ImageObject,
    ) {
        @Serializable
        data class ImageObject(
            val original: String? = null,
            val large: String? = null,
            val medium: String? = null,
            val small: String? = null,
        )

        fun toSAnime(json: Json): SAnime = SAnime.create().apply {
            title = this@AnimeObject.title
            thumbnail_url = posterImage.original ?: posterImage.large ?: posterImage.medium ?: posterImage.small ?: ""
            url = json.encodeToString(LinkData(slug = link, id = id))
        }
    }
}

@Serializable
data class LinkData(
    val slug: String,
    val id: String,
)

@Serializable
data class AnimeDetails(
    val props: PropsObject,
) {
    @Serializable
    data class PropsObject(
        val pageProps: PagePropsObject,
    ) {
        @Serializable
        data class PagePropsObject(
            val data: DataObject,
        ) {
            @Serializable
            data class DataObject(
                val synopsys: String? = null,
                val genres: List<String>? = null,
            ) {
                fun toSAnime(): SAnime = SAnime.create().apply {
                    description = synopsys
                    genre = genres?.joinToString(", ")
                }
            }
        }
    }
}

@Serializable
data class EpisodeListResponse(
    val data: List<EpisodeObject>,
) {
    @Serializable
    data class EpisodeObject(
        val uid: String,
        val origin: String,
        val number: String? = null,
        val title: String? = null,
    ) {
        fun toSEpisode(): SEpisode = SEpisode.create().apply {
            episode_number = number?.toFloatOrNull() ?: 1F
            name = (number?.let { "Ep. $number" } ?: "Episode") + (title?.let { " - $it" } ?: "")
            url = "/watch/$uid?origin=$origin"
        }
    }
}

@Serializable
data class VideoData(
    val props: PropsObject,
) {
    @Serializable
    data class PropsObject(
        val pageProps: PagePropsObject,
    ) {
        @Serializable
        data class PagePropsObject(
            val subtitles: List<SubtitleObject>? = null,
            val animeData: AnimeDataObject,
            val episode: EpisodeObject,
        ) {
            @Serializable
            data class SubtitleObject(
                val src: String,
                val label: String,
            )

            @Serializable
            data class AnimeDataObject(
                val title: String,
            )

            @Serializable
            data class EpisodeObject(
                val number: String,
            )
        }
    }
}

@Serializable
data class VideoList(
    val directUrl: List<VideoObject>? = null,
    val message: String? = null,
) {
    @Serializable
    data class VideoObject(
        val src: String,
        val label: String,
    )
}
