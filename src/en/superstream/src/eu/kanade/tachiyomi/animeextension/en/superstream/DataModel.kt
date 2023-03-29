package eu.kanade.tachiyomi.animeextension.en.superstream

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LinkData(
    val id: Int,
    val type: Int,
    val season: Int?,
    val episode: Int?,
)

@Serializable
data class LinkDataProp(
    val code: Int? = null,
    val msg: String? = null,
    val data: ParsedLinkData? = null,
)

@Serializable
data class ParsedLinkData(
    val seconds: Int? = null,
    val quality: ArrayList<String> = arrayListOf(),
    val list: ArrayList<LinkList> = arrayListOf(),
)

@Serializable
data class LinkList(
    val path: String? = null,
    val quality: String? = null,
    val real_quality: String? = null,
    val format: String? = null,
    val size: String? = null,
    val size_bytes: Long? = null,
    val count: Int? = null,
    val dateline: Long? = null,
    val fid: Int? = null,
    val mmfid: Int? = null,
    val h265: Int? = null,
    val hdr: Int? = null,
    val filename: String? = null,
    val original: Int? = null,
    val colorbit: Int? = null,
    val success: Int? = null,
    val timeout: Int? = null,
    val vip_link: Int? = null,
    val fps: Int? = null,
    val bitstream: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class LoadData(
    val id: Int,
    val type: Int?,
)

@Serializable
data class DataJSON(
    val data: ArrayList<ListJSON> = arrayListOf(),
)

@Serializable
data class ListJSON(
    val code: Int? = null,
    val type: String? = null,
    val name: String? = null,
    val box_type: Int? = null,
    val list: ArrayList<PostJSON> = arrayListOf(),
)

@Serializable
data class PostJSON(
    val id: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val poster_2: String? = null,
    val box_type: Int? = null,
    val imdb_rating: String? = null,
    val quality_tag: String? = null,
)

@Serializable
data class MainData(
    val data: ArrayList<Data> = arrayListOf(),
)

@Serializable
data class Data(
    val id: Int? = null,
    val mid: Int? = null,
    val tid: Int? = null,
    val box_type: Int? = null,
    val title: String? = null,
    val poster_org: String? = null,
    val poster: String? = null,
    val cats: String? = null,
    val year: Int? = null,
    val imdb_rating: String? = null,
    val quality_tag: String? = null,
)

@Serializable
data class MovieDataProp(
    val data: MovieData? = MovieData(),
)

@Serializable
data class MovieData(
    val id: Int? = null,
    val title: String? = null,
    val director: String? = null,
    val writer: String? = null,
    val actors: String? = null,
    val runtime: Int? = null,
    val poster: String? = null,
    val description: String? = null,
    val cats: String? = null,
    val year: Int? = null,
    val update_time: Int? = null,
    val imdbId: String? = null,
    val imdb_rating: String? = null,
    val trailer: String? = null,
    val released: String? = null,
    val content_rating: String? = null,
    val tmdb_id: Int? = null,
    val tomatoMeter: Int? = null,
    val poster_org: String? = null,
    val trailer_url: String? = null,
    val imdb_link: String? = null,
    val box_type: Int? = null,
    val recommend: List<Data> = listOf(), // series does not have any recommendations :pensive:
)

@Serializable
data class SeriesDataProp(
    val code: Int? = null,
    val msg: String? = null,
    val data: SeriesData? = SeriesData(),
)

@Serializable
data class SeriesLanguage(
    val title: String? = null,
    val lang: String? = null,
)

@Serializable
data class SeriesData(
    val id: Int? = null,
    val mb_id: Int? = null,
    val title: String? = null,
    val display: Int? = null,
    val state: Int? = null,
    val vip_only: Int? = null,
    val code_file: Int? = null,
    val director: String? = null,
    val writer: String? = null,
    val actors: String? = null,
    val add_time: Int? = null,
    val poster: String? = null,
    val poster_imdb: Int? = null,
    val banner_mini: String? = null,
    val description: String? = null,
    val imdb_id: String? = null,
    val cats: String? = null,
    val year: Int? = null,
    val collect: Int? = null,
    val view: Int? = null,
    val download: Int? = null,
    val update_time: JsonElement? = null,
    val released: String? = null,
    val released_timestamp: Int? = null,
    val episode_released: String? = null,
    val episode_released_timestamp: Int? = null,
    val max_season: Int? = null,
    val max_episode: Int? = null,
    val remark: String? = null,
    val imdb_rating: String? = null,
    val content_rating: String? = null,
    val tmdb_id: Int? = null,
    val tomato_url: String? = null,
    val tomato_meter: Int? = null,
    val tomato_meter_count: Int? = null,
    val tomato_meter_state: String? = null,
    val reelgood_url: String? = null,
    val audience_score: Int? = null,
    val audience_score_count: Int? = null,
    val no_tomato_url: Int? = null,
    val order_year: Int? = null,
    val episodate_id: String? = null,
    val weights_day: Double? = null,
    val poster_min: String? = null,
    val poster_org: String? = null,
    val banner_mini_min: String? = null,
    val banner_mini_org: String? = null,
    val trailer_url: String? = null,
    val years: ArrayList<Int> = arrayListOf(),
    val season: ArrayList<Int> = arrayListOf(),
    val history: ArrayList<String> = arrayListOf(),
    val imdb_link: String? = null,
    val episode: ArrayList<SeriesEpisode> = arrayListOf(),
    val language: ArrayList<SeriesLanguage> = arrayListOf(),
    val box_type: Int? = null,
    val year_year: String? = null,
    val season_episode: String? = null,
    val recommend: List<Data> = listOf(),
)

@Serializable
data class SeriesSeasonProp(
    val code: Int? = null,
    val msg: String? = null,
    val data: ArrayList<SeriesEpisode>? = arrayListOf(),
)

@Serializable
data class SeriesEpisode(
    val id: Int? = null,
    val tid: Int? = null,
    val mb_id: Int? = null,
    val imdb_id: String? = null,
    val imdb_id_status: Int? = null,
    val srt_status: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val state: Int? = null,
    val title: String? = null,
    val thumbs: String? = null,
    val thumbs_bak: String? = null,
    val thumbs_original: String? = null,
    val poster_imdb: Int? = null,
    val synopsis: String? = null,
    val runtime: Int? = null,
    val view: Int? = null,
    val download: Int? = null,
    val source_file: Int? = null,
    val code_file: Int? = null,
    val add_time: Int? = null,
    val update_time: Int? = null,
    val released: String? = null,
    val released_timestamp: Long? = null,
    val audio_lang: String? = null,
    val quality_tag: String? = null,
    val remark: String? = null,
    val pending: String? = null,
    val imdb_rating: String? = null,
    val display: Int? = null,
    val sync: Int? = null,
    val tomato_meter: Int? = null,
    val imdb_link: String? = null,
)

@Serializable
data class SubtitleList(
    val language: String? = null,
    val subtitles: ArrayList<Subtitles> = arrayListOf(),
)

@Serializable
data class Subtitles(
    val sid: Int? = null,
    val mid: String? = null,
    val file_path: String? = null,
    val lang: String? = null,
    val language: String? = null,
    val delay: Int? = null,
    val point: JsonElement? = null,
    val order: Int? = null,
    val admin_order: Int? = null,
    val myselect: Int? = null,
    val add_time: Long? = null,
    val count: Int? = null,
)

@Serializable
data class PrivateSubtitleData(
    val select: ArrayList<String> = arrayListOf(),
    val list: ArrayList<SubtitleList> = arrayListOf(),
)

@Serializable
data class SubtitleDataProp(
    val code: Int? = null,
    val msg: String? = null,
    val data: PrivateSubtitleData? = PrivateSubtitleData(),
)
