package eu.kanade.tachiyomi.animeextension.all.jellyfin

import eu.kanade.tachiyomi.animeextension.all.jellyfin.Jellyfin.EpisodeType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class LoginDto(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("SessionInfo") val sessionInfo: LoginSessionDto,
) {
    @Serializable
    data class LoginSessionDto(
        @SerialName("UserId") val userId: String,
    )
}

@Serializable
data class ItemsDto(
    @SerialName("Items") val items: List<ItemDto>,
    @SerialName("TotalRecordCount") val itemCount: Int,
)

@Serializable
data class ItemDto(
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: String,
    @SerialName("Id") val id: String,
    @SerialName("LocationType") val locationType: String,
    @SerialName("ImageTags") val imageTags: ImageDto,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,

    // Details
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Studios") val studios: List<StudioDto>? = null,

    // Only for series, not season
    @SerialName("Status") val seriesStatus: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,

    // Episode
    @SerialName("PremiereDate") val premiereData: String? = null,
    @SerialName("RunTimeTicks") val runTime: Long? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaDto>? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
) {
    @Serializable
    data class ImageDto(
        @SerialName("Primary") val primary: String? = null,
    )

    @Serializable
    data class StudioDto(
        @SerialName("Name") val name: String,
    )

    fun toSAnime(baseUrl: String, userId: String, apiKey: String): SAnime = SAnime.create().apply {
        val httpUrl = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("Users")
            addPathSegment(userId)
            addPathSegment("Items")
            addPathSegment(id)
            addQueryParameter("api_key", apiKey)
        }

        thumbnail_url = "$baseUrl/Items/$id/Images/Primary?api_key=$apiKey"

        when (type) {
            "Season" -> {
                // To prevent one extra GET request when fetching episodes
                httpUrl.fragment("seriesId,${seriesId!!}")

                if (locationType == "Virtual") {
                    title = seriesName!!
                    thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
                } else {
                    title = "$seriesName $name"
                }

                // Use series as fallback
                if (imageTags.primary == null) {
                    thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary?api_key=$apiKey"
                }
            }
            "Movie" -> {
                httpUrl.fragment("movie")
                title = name
            }
            "BoxSet" -> {
                httpUrl.fragment("boxSet")
                title = name
            }
            "Series" -> {
                httpUrl.fragment("series")
                title = name
            }
        }

        url = httpUrl.build().toString()

        // Details
        description = overview?.let {
            Jsoup.parseBodyFragment(
                it.replace("<br>", "br2n"),
            ).text().replace("br2n", "\n")
        }
        genre = genres?.joinToString(", ")
        author = studios?.joinToString(", ") { it.name }

        if (type == "Movie") {
            status = SAnime.COMPLETED
        } else {
            status = seriesStatus.parseStatus()
        }
    }

    private fun String?.parseStatus(): Int = when (this) {
        "Ended" -> SAnime.COMPLETED
        "Continuing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    fun toSEpisode(
        baseUrl: String,
        userId: String,
        apiKey: String,
        epDetails: Set<String>,
        epType: EpisodeType,
        prefix: String,
        removeAffixes: Boolean,
    ): SEpisode = SEpisode.create().apply {
        when (epType) {
            EpisodeType.MOVIE -> {
                episode_number = 1F
                name = if (removeAffixes && prefix.isNotBlank()) prefix else "${prefix}Movie"
            }
            EpisodeType.EPISODE -> {
                name = if (indexNumber == null || removeAffixes) {
                    "${prefix}${this@ItemDto.name}"
                } else {
                    "${prefix}Ep. $indexNumber - ${this@ItemDto.name}"
                }
                indexNumber?.let {
                    episode_number = it.toFloat()
                }
            }
        }

        val extraInfo = buildList {
            if (epDetails.contains("Overview") && overview != null && epType == EpisodeType.EPISODE) {
                add(overview)
            }

            if (epDetails.contains("Size") && mediaSources != null) {
                mediaSources.first().size?.also {
                    add(it.formatBytes())
                }
            }

            if (epDetails.contains("Runtime") && runTime != null) {
                add(runTime.formatTicks())
            }
        }

        scanlator = extraInfo.joinToString(" • ")
        premiereData?.also {
            date_upload = parseDate(it.removeSuffix("Z"))
        }
        url = "$baseUrl/Users/$userId/Items/$id?api_key=$apiKey"
    }

    private fun Long.formatBytes(): String = when {
        this >= 1_000_000_000 -> "%.2f GB".format(this / 1_000_000_000.0)
        this >= 1_000_000 -> "%.2f MB".format(this / 1_000_000.0)
        this >= 1_000 -> "%.2f KB".format(this / 1_000.0)
        this > 1 -> "$this bytes"
        this == 1L -> "$this byte"
        else -> ""
    }

    private fun Long.formatTicks(): String {
        val seconds = this / 10_000_000
        val minutes = seconds / 60
        val hours = minutes / 60

        val remainingSeconds = seconds % 60
        val remainingMinutes = minutes % 60

        val formattedHours = if (hours > 0) "${hours}h " else ""
        val formattedMinutes = if (remainingMinutes > 0) "${remainingMinutes}m " else ""
        val formattedSeconds = "${remainingSeconds}s"

        return "$formattedHours$formattedMinutes$formattedSeconds".trim()
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS", Locale.ENGLISH)
        }
    }
}

@Serializable
data class SessionDto(
    @SerialName("MediaSources") val mediaSources: List<MediaDto>,
    @SerialName("PlaySessionId") val playSessionId: String,
)

@Serializable
data class MediaDto(
    @SerialName("Size") val size: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto>,
) {
    @Serializable
    data class MediaStreamDto(
        @SerialName("Codec") val codec: String,
        @SerialName("Index") val index: Int,
        @SerialName("Type") val type: String,
        @SerialName("SupportsExternalStream") val supportsExternalStream: Boolean,
        @SerialName("IsExternal") val isExternal: Boolean,
        @SerialName("Language") val lang: String? = null,
        @SerialName("DisplayTitle") val displayTitle: String? = null,
        @SerialName("Height") val height: Int? = null,
        @SerialName("Width") val width: Int? = null,
    )
}
