package eu.kanade.tachiyomi.animeextension.fr.franime.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

typealias BigIntegerJson =
    @Serializable(with = BigIntegerSerializer::class)
    BigInteger

@OptIn(ExperimentalSerializationApi::class)
private object BigIntegerSerializer : KSerializer<BigInteger> {

    override val descriptor = PrimitiveSerialDescriptor("java.math.BigInteger", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): BigInteger =
        when (decoder) {
            is JsonDecoder -> decoder.decodeJsonElement().jsonPrimitive.content.toBigInteger()
            else -> decoder.decodeString().toBigInteger()
        }

    override fun serialize(encoder: Encoder, value: BigInteger) =
        when (encoder) {
            is JsonEncoder -> encoder.encodeJsonElement(JsonUnquotedLiteral(value.toString()))
            else -> encoder.encodeString(value.toString())
        }
}

@Serializable
data class Anime(
    @SerialName("themes") val genres: List<String>,
    @SerialName("saisons") val seasons: List<Season>,
    @SerialName("_id") val uid: String?,
    @SerialName("id") val id: BigIntegerJson,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("banner") val banner: String?,
    @SerialName("affiche") val poster: String,
    @SerialName("titleO") val originalTitle: String,
    @SerialName("title") val title: String,
    @SerialName("titles") val titlesAlt: TitlesAlt,
    @SerialName("description") val description: String,
    @SerialName("note") val note: Float,
    @SerialName("format") val format: String,
    @SerialName("startDate") val startDate: String?, // deserialize as date
    @SerialName("endDate") val endDate: String?, // ditto
    @SerialName("status") val status: String,
    @SerialName("nsfw") val nsfw: Boolean,
    @SerialName("__v") val uuv: Int?, // no idea wtf is this
    @SerialName("affiche_small") val posterSmall: String?,
    @SerialName("updatedDate") val updateTime: Long?, // deserialize as timestamp
)

@Serializable
data class Season(
    @SerialName("title") val title: String,
    @SerialName("episodes") val episodes: List<Episode>,
)

@Serializable
data class Episode(
    @SerialName("title") val title: String,
    @SerialName("lang") val languages: EpisodeLanguages,
)

@Serializable
data class EpisodeLanguages(
    @SerialName("vf") val vf: EpisodeLanguage,
    @SerialName("vo") val vo: EpisodeLanguage,
)

@Serializable
data class EpisodeLanguage(
    @SerialName("lecteurs") val players: List<String>,
)

@Serializable
data class TitlesAlt(
    @SerialName("en") val en: String?,
    @SerialName("en_jp") val enJp: String?,
    @SerialName("ja_jp") val jaJp: String?,
)
