package eu.kanade.tachiyomi.animeextension.de.aniflix.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class AnimeDto(
    @SerialName("airing")
    @Serializable(with = IntSerializer::class)
    val airing: Int? = null,
    @SerialName("cover_portrait")
    val coverPortrait: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("description")
    val description: String? = null,
    @SerialName("id")
    val id: Int? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("url")
    val url: String? = null
)

object IntSerializer : JsonTransformingSerializer<Int>(Int.serializer()) {
    // If response is not a primitive, then return something else
    override fun transformDeserialize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonPrimitive(1)
            is JsonPrimitive -> element
            else -> JsonPrimitive(-1)
        }
}

@Serializable
data class AnimeDetailsDto(
    @SerialName("airing")
    @Serializable(with = IntSerializer::class)
    val airing: Int? = null,
    @SerialName("cover_portrait")
    val coverPortrait: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("description")
    val description: String? = null,
    @SerialName("genres")
    val genres: List<Genre>? = null,
    @SerialName("id")
    val id: Int? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("url")
    val url: String? = null,
    @SerialName("seasons")
    val seasons: List<Season>? = null
)

@Serializable
data class Season(
    @SerialName("episodes")
    val episodes: List<Episode>? = null,
    @SerialName("number")
    val number: Int? = null,
    @SerialName("id")
    val id: Int? = null,
    @SerialName("length")
    val length: Int? = null
)

@Serializable
data class Episode(
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("number")
    val number: Int? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("streams")
    val streams: List<Stream>? = null
)

@Serializable
data class Release(
    @SerialName("season")
    val season: ShortSeason? = null
)

@Serializable
data class ShortSeason(
    @SerialName("show")
    val anime: AnimeDto? = null
)

@Serializable
data class Stream(
    @SerialName("link")
    val link: String? = null,
    @SerialName("lang")
    val lang: String? = null,
    @SerialName("hoster")
    val hoster: Hoster? = null
)

@Serializable
data class Hoster(
    @SerialName("name")
    val name: String? = null
)

@Serializable
data class Genre(
    @SerialName("name")
    val name: String? = null,
    @SerialName("id")
    val id: Int? = null
)
