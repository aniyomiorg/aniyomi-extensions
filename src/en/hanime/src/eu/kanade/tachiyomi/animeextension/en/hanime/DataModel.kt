package eu.kanade.tachiyomi.animeextension.en.hanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class SearchParameters(
    val includedTags: ArrayList<String>,
    val blackListedTags: ArrayList<String>,
    val brands: ArrayList<String>,
    val tagsMode: String,
    val orderBy: String,
    val ordering: String,
)

@Serializable
data class HAnimeResponse(
    val page: Long,
    val nbPages: Long,
    val nbHits: Long,
    val hitsPerPage: Long,
    val hits: String,
)

@Serializable
data class HitsModel(
    val id: Long? = null,
    val name: String,
    val titles: List<String> = emptyList(),
    val slug: String? = null,
    val description: String? = null,
    val views: Long? = null,
    val interests: Long? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val brand: String? = null,
    @SerialName("brand_id")
    val brandId: Long? = null,
    @SerialName("duration_in_ms")
    val durationInMs: Long? = null,
    @SerialName("is_censored")
    val isCensored: Boolean? = false,
    val rating: Long? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val downloads: Long? = null,
    @SerialName("monthly_rank")
    val monthlyRank: Long? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at")
    val createdAt: Long? = null,
    @SerialName("released_at")
    val releasedAt: Long? = null,
)

@Serializable
data class VideoModel(
    @SerialName("player_base_url")
    val playerBaseUrl: String? = null,
    @SerialName("hentai_video")
    val hentaiVideo: HentaiVideo? = HentaiVideo(),
    @SerialName("hentai_tags")
    val hentaiTags: List<HentaiTag>? = emptyList(),
    @SerialName("hentai_franchise_hentai_videos")
    val hentaiFranchiseHentaiVideos: List<HentaiFranchiseHentaiVideo>? = emptyList(),
    @SerialName("videos_manifest")
    val videosManifest: VideosManifest? = VideosManifest(),
)

@Serializable
data class HentaiVideo(
    val id: Long? = null,
    @SerialName("is_visible")
    val isVisible: Boolean? = false,
    val name: String? = null,
    val slug: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("released_at")
    val releasedAt: String? = null,
    val description: String? = null,
    val views: Long? = null,
    val interests: Long? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    @SerialName("is_hard_subtitled")
    val isHardSubtitled: Boolean? = false,
    val brand: String? = null,
    @SerialName("duration_in_ms")
    val durationInMs: Long? = null,
    @SerialName("is_censored")
    val isCensored: Boolean? = false,
    val rating: Double? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val downloads: Long? = null,
    @SerialName("monthly_rank")
    val monthlyRank: Long? = null,
    @SerialName("brand_id")
    val brandId: String? = null,
    @SerialName("is_banned_in")
    val isBannedIn: String? = null,
    @SerialName("created_at_unix")
    val createdAtUnix: Long? = null,
    @SerialName("released_at_unix")
    val releasedAtUnix: Long? = null,
    @SerialName("hentai_tags")
    val hentaiTags: List<HentaiTag>? = emptyList(),
)

@Serializable
data class HentaiTag(
    val id: Long? = null,
    val text: String? = null,
    val count: Long? = null,
    val description: String? = null,
    @SerialName("wide_image_url")
    val wideImageUrl: String? = null,
    @SerialName("tall_image_url")
    val tallImageUrl: String? = null,
)

@Serializable
data class HentaiFranchiseHentaiVideo(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("released_at")
    val releasedAt: String? = null,
    val views: Long? = null,
    val interests: Long? = null,
    @SerialName("poster_url")
    val posterUrl: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    @SerialName("is_hard_subtitled")
    val isHardSubtitled: Boolean? = false,
    val brand: String? = null,
    @SerialName("duration_in_ms")
    val durationInMs: Long? = null,
    @SerialName("is_censored")
    val isCensored: Boolean? = false,
    val rating: Double? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val downloads: Long? = null,
    @SerialName("monthly_rank")
    val monthlyRank: Long? = null,
    @SerialName("brand_id")
    val brandId: String? = null,
    @SerialName("is_banned_in")
    val isBannedIn: String? = null,
    @SerialName("created_at_unix")
    val createdAtUnix: Long? = null,
    @SerialName("released_at_unix")
    val releasedAtUnix: Long? = null,
)

@Serializable
data class VideosManifest(
    val servers: List<Server>? = emptyList(),
)

@Serializable
data class Server(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null,
    @SerialName("na_rating")
    val naRating: Long? = null,
    @SerialName("eu_rating")
    val euRating: Long? = null,
    @SerialName("asia_rating")
    val asiaRating: Long? = null,
    val sequence: Long? = null,
    @SerialName("is_permanent")
    val isPermanent: Boolean? = false,
    val streams: List<Stream> = emptyList(),
)

@Serializable
data class Stream(
    val id: Long? = null,
    @SerialName("server_id")
    val serverId: Long? = null,
    val slug: String? = null,
    val kind: String? = null,
    val extension: String? = null,
    @SerialName("mime_type")
    val mimeType: String? = null,
    val width: Long? = null,
    val height: String,
    @SerialName("duration_in_ms")
    val durationInMs: Long? = null,
    @SerialName("filesize_mbs")
    val filesizeMbs: Long? = null,
    val filename: String? = null,
    val url: String,
    @SerialName("is_guest_allowed")
    val isGuestAllowed: Boolean? = false,
    @SerialName("is_member_allowed")
    val isMemberAllowed: Boolean? = false,
    @SerialName("is_premium_allowed")
    val isPremiumAllowed: Boolean? = false,
    @SerialName("is_downloadable")
    val isDownloadable: Boolean? = false,
    val compatibility: String? = null,
    @SerialName("hv_id")
    val hvId: Long? = null,
    @SerialName("server_sequence")
    val serverSequence: Long? = null,
    @SerialName("video_stream_group_id")
    val videoStreamGroupId: String? = null,
)

@Serializable
data class WindowNuxt(
    val state: State,
) {
    @Serializable
    data class State(
        val data: Data,
    ) {
        @Serializable
        data class Data(
            val video: DataVideo,
        ) {
            @Serializable
            data class DataVideo(
                val videos_manifest: VideosManifest,
            ) {
                @Serializable
                data class VideosManifest(
                    val servers: List<Server>,
                ) {
                    @Serializable
                    data class Server(
                        val streams: List<Stream>,
                    ) {
                        @Serializable
                        data class Stream(
                            val height: String,
                            val url: String,
                        )
                    }
                }
            }
        }
    }
}
