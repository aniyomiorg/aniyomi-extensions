package eu.kanade.tachiyomi.animeextension.all.jellyfin

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.apache.commons.text.StringSubstitutor
import org.jsoup.Jsoup
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
class LoginDto(
    val accessToken: String,
    val sessionInfo: SessionDto,
) {
    @Serializable
    class SessionDto(
        val userId: String,
    )
}

@Serializable
class ItemListDto(
    val items: List<ItemDto>,
    val totalRecordCount: Int,
)

@Serializable
class ItemDto(
    // Common
    val name: String,
    val type: String,
    val id: String,
    val locationType: String,
    val imageTags: ImageDto,

    // Items
    val collectionType: String? = null,

    // Anime
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonName: String? = null,

    // Anime Details
    val seriesStatus: String? = null,
    val overview: String? = null,
    val genres: List<String>? = null,
    val studios: List<StudioDto>? = null,

    // Episode
    val indexNumber: Int? = null,
    val premiereDate: String? = null,
    val runTimeTicks: Long? = null,
    val dateCreated: String? = null,
    val mediaSources: List<MediaDto>? = null,
) {
    @Serializable
    class ImageDto(
        val primary: String? = null,
    )

    @Serializable
    class StudioDto(
        val name: String,
    )

    // =============================== Anime ================================

    fun toSAnime(baseUrl: String, userId: String): SAnime = SAnime.create().apply {
        val typeMap = mapOf(
            "Season" to "seriesId,$seriesId",
            "Movie" to "movie",
            "BoxSet" to "boxSet",
            "Series" to "series",
        )

        url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("Users")
            addPathSegment(userId)
            addPathSegment("Items")
            addPathSegment(id)
            fragment(typeMap[type])
        }.build().toString()
        thumbnail_url = "$baseUrl/Items/$id/Images/Primary"
        title = name
        description = overview?.let {
            Jsoup.parseBodyFragment(
                it.replace("<br>", "br2n"),
            ).text().replace("br2n", "\n")
        }
        genre = genres?.joinToString(", ")
        author = studios?.joinToString(", ") { it.name }

        status = if (type == "Movie") {
            SAnime.COMPLETED
        } else {
            seriesStatus.parseStatus()
        }

        if (type == "Season") {
            if (locationType == "Virtual") {
                title = seriesName!!
                thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary"
            } else {
                title = "$seriesName $name"
            }

            // Use series as fallback
            if (imageTags.primary == null) {
                thumbnail_url = "$baseUrl/Items/$seriesId/Images/Primary"
            }
        }
    }

    private fun String?.parseStatus(): Int = when (this) {
        "Ended" -> SAnime.COMPLETED
        "Continuing" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    // ============================== Episode ===============================

    fun toSEpisode(
        baseUrl: String,
        userId: String,
        prefix: String,
        epDetails: Set<String>,
        episodeTemplate: String,
    ): SEpisode = SEpisode.create().apply {
        val runtimeInSec = runTimeTicks?.div(10_000_000)
        val size = mediaSources?.first()?.size?.formatBytes()
        val runTime = runtimeInSec?.formatSeconds()
        val title = buildString {
            append(prefix)
            if (type != "Movie") {
                append(this@ItemDto.name)
            }
        }

        val values = mapOf(
            "title" to title,
            "type" to type,
            "typeShort" to type.replace("Episode", "Ep."),
            "seriesTitle" to (seriesName ?: ""),
            "seasonTitle" to (seasonName ?: ""),
            "number" to (indexNumber?.toString() ?: ""),
            "createdDate" to (dateCreated?.substringBefore("T") ?: ""),
            "releaseDate" to (premiereDate?.substringBefore("T") ?: ""),
            "size" to (size ?: ""),
            "sizeBytes" to (mediaSources?.first()?.size?.toString() ?: ""),
            "runtime" to (runTime ?: ""),
            "runtimeS" to (runtimeInSec?.toString() ?: ""),
        )
        val sub = StringSubstitutor(values, "{", "}")
        val extraInfo = buildList {
            if (epDetails.contains("Overview") && overview != null && type == "Episode") {
                add(overview)
            }
            if (epDetails.contains("Size") && size != null) {
                add(size)
            }
            if (epDetails.contains("Runtime") && runTime != null) {
                add(runTime)
            }
        }

        name = sub.replace(episodeTemplate).trim()
            .removeSuffix("-")
            .removePrefix("-")
            .trim()
        url = "$baseUrl/Users/$userId/Items/$id"
        scanlator = extraInfo.joinToString(" • ")
        premiereDate?.let {
            date_upload = parseDateTime(it.removeSuffix("Z"))
        }
        indexNumber?.let {
            episode_number = it.toFloat()
        }
        if (type == "Movie") {
            episode_number = 1F
        }
    }

    private fun Long.formatSeconds(): String {
        val minutes = this / 60
        val hours = minutes / 60

        val remainingSeconds = this % 60
        val remainingMinutes = minutes % 60

        val formattedHours = if (hours > 0) "${hours}h " else ""
        val formattedMinutes = if (remainingMinutes > 0) "${remainingMinutes}m " else ""
        val formattedSeconds = "${remainingSeconds}s"

        return "$formattedHours$formattedMinutes$formattedSeconds".trim()
    }

    private fun parseDateTime(date: String) = try {
        FORMATTER_DATE_TIME.parse(date.removeSuffix("Z"))!!.time
    } catch (_: ParseException) {
        0L
    }

    companion object {
        private val FORMATTER_DATE_TIME = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS",
            Locale.ENGLISH,
        )
    }
}

@Serializable
class SessionDto(
    val mediaSources: List<MediaDto>,
    val playSessionId: String,
)

@Serializable
class MediaDto(
    val size: Long? = null,
    val supportsTranscoding: Boolean,
    val mediaStreams: List<MediaStreamDto>,
) {
    @Serializable
    class MediaStreamDto(
        val codec: String,
        val index: Int,
        val type: String,
        val supportsExternalStream: Boolean,
        val isExternal: Boolean,
        val language: String? = null,
        val displayTitle: String? = null,
        val height: Int? = null,
        val width: Int? = null,
        val bitRate: Long? = null,
    )
}
