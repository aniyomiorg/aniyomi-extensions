package eu.kanade.tachiyomi.animeextension.en.superstream

import com.fasterxml.jackson.annotation.JsonProperty

data class LinkData(
    val id: Int,
    val type: Int,
    val season: Int?,
    val episode: Int?
)

data class LinkDataProp(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("msg") val msg: String? = null,
    @JsonProperty("data") val data: ArrayList<ParsedLinkData?> = arrayListOf()
)

data class ParsedLinkData(
    @JsonProperty("seconds") val seconds: Int? = null,
    @JsonProperty("quality") val quality: ArrayList<String> = arrayListOf(),
    @JsonProperty("list") val list: ArrayList<LinkList> = arrayListOf()
)

data class LinkList(
    @JsonProperty("path") val path: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("real_quality") val realQuality: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("size_bytes") val sizeBytes: Long? = null,
    @JsonProperty("count") val count: Int? = null,
    @JsonProperty("dateline") val dateline: Long? = null,
    @JsonProperty("fid") val fid: Int? = null,
    @JsonProperty("mmfid") val mmfid: Int? = null,
    @JsonProperty("h265") val h265: Int? = null,
    @JsonProperty("hdr") val hdr: Int? = null,
    @JsonProperty("filename") val filename: String? = null,
    @JsonProperty("original") val original: Int? = null,
    @JsonProperty("colorbit") val colorbit: Int? = null,
    @JsonProperty("success") val success: Int? = null,
    @JsonProperty("timeout") val timeout: Int? = null,
    @JsonProperty("vip_link") val vipLink: Int? = null,
    @JsonProperty("fps") val fps: Int? = null,
    @JsonProperty("bitstream") val bitstream: String? = null,
    @JsonProperty("width") val width: Int? = null,
    @JsonProperty("height") val height: Int? = null
)

data class LoadData(
    val id: Int,
    val type: Int?
)

data class DataJSON(
    @JsonProperty("data") val data: ArrayList<ListJSON> = arrayListOf()
)

data class ListJSON(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("box_type") val boxType: Int? = null,
    @JsonProperty("list") val list: ArrayList<PostJSON> = arrayListOf(),
)

data class PostJSON(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("poster_2") val poster2: String? = null,
    @JsonProperty("box_type") val boxType: Int? = null,
    @JsonProperty("imdb_rating") val imdbRating: String? = null,
    @JsonProperty("quality_tag") val quality_tag: String? = null,
)

data class MainData(
    @JsonProperty("data") val data: ArrayList<Data> = arrayListOf()
)

data class Data(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("mid") val mid: Int? = null,
    @JsonProperty("tid") val tid: Int? = null,
    @JsonProperty("box_type") val boxType: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster_org") val posterOrg: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("cats") val cats: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("imdb_rating") val imdbRating: String? = null,
    @JsonProperty("quality_tag") val qualityTag: String? = null,
)

data class MovieDataProp(
    @JsonProperty("data") val data: MovieData? = MovieData()
)

data class MovieData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("director") val director: String? = null,
    @JsonProperty("writer") val writer: String? = null,
    @JsonProperty("actors") val actors: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("cats") val cats: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("update_time") val updateTime: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("imdb_rating") val imdbRating: String? = null,
    @JsonProperty("trailer") val trailer: String? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("content_rating") val contentRating: String? = null,
    @JsonProperty("tmdb_id") val tmdbId: Int? = null,
    @JsonProperty("tomato_meter") val tomatoMeter: Int? = null,
    @JsonProperty("poster_org") val posterOrg: String? = null,
    @JsonProperty("trailer_url") val trailerUrl: String? = null,
    @JsonProperty("imdb_link") val imdbLink: String? = null,
    @JsonProperty("box_type") val boxType: Int? = null,
    @JsonProperty("recommend") val recommend: List<Data> = listOf() // series does not have any recommendations :pensive:
)

data class SeriesDataProp(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("msg") val msg: String? = null,
    @JsonProperty("data") val data: SeriesData? = SeriesData()
)

data class SeriesLanguage(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("lang") val lang: String? = null
)

data class SeriesData(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("mb_id") val mbId: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("display") val display: Int? = null,
    @JsonProperty("state") val state: Int? = null,
    @JsonProperty("vip_only") val vipOnly: Int? = null,
    @JsonProperty("code_file") val codeFile: Int? = null,
    @JsonProperty("director") val director: String? = null,
    @JsonProperty("writer") val writer: String? = null,
    @JsonProperty("actors") val actors: String? = null,
    @JsonProperty("add_time") val addTime: Int? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("poster_imdb") val posterImdb: Int? = null,
    @JsonProperty("banner_mini") val bannerMini: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("cats") val cats: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("collect") val collect: Int? = null,
    @JsonProperty("view") val view: Int? = null,
    @JsonProperty("download") val download: Int? = null,
    @JsonProperty("update_time") val updateTime: String? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("released_timestamp") val releasedTimestamp: Int? = null,
    @JsonProperty("episode_released") val episodeReleased: String? = null,
    @JsonProperty("episode_released_timestamp") val episodeReleasedTimestamp: Int? = null,
    @JsonProperty("max_season") val maxSeason: Int? = null,
    @JsonProperty("max_episode") val maxEpisode: Int? = null,
    @JsonProperty("remark") val remark: String? = null,
    @JsonProperty("imdb_rating") val imdbRating: String? = null,
    @JsonProperty("content_rating") val contentRating: String? = null,
    @JsonProperty("tmdb_id") val tmdbId: Int? = null,
    @JsonProperty("tomato_url") val tomatoUrl: String? = null,
    @JsonProperty("tomato_meter") val tomatoMeter: Int? = null,
    @JsonProperty("tomato_meter_count") val tomatoMeterCount: Int? = null,
    @JsonProperty("tomato_meter_state") val tomatoMeterState: String? = null,
    @JsonProperty("reelgood_url") val reelgoodUrl: String? = null,
    @JsonProperty("audience_score") val audienceScore: Int? = null,
    @JsonProperty("audience_score_count") val audienceScoreCount: Int? = null,
    @JsonProperty("no_tomato_url") val noTomatoUrl: Int? = null,
    @JsonProperty("order_year") val orderYear: Int? = null,
    @JsonProperty("episodate_id") val episodateId: String? = null,
    @JsonProperty("weights_day") val weightsDay: Double? = null,
    @JsonProperty("poster_min") val posterMin: String? = null,
    @JsonProperty("poster_org") val posterOrg: String? = null,
    @JsonProperty("banner_mini_min") val bannerMiniMin: String? = null,
    @JsonProperty("banner_mini_org") val bannerMiniOrg: String? = null,
    @JsonProperty("trailer_url") val trailerUrl: String? = null,
    @JsonProperty("years") val years: ArrayList<Int> = arrayListOf(),
    @JsonProperty("season") val season: ArrayList<Int> = arrayListOf(),
    @JsonProperty("history") val history: ArrayList<String> = arrayListOf(),
    @JsonProperty("imdb_link") val imdbLink: String? = null,
    @JsonProperty("episode") val episode: ArrayList<SeriesEpisode> = arrayListOf(),
//        @JsonProperty("is_collect") val isCollect: Int? = null,
    @JsonProperty("language") val language: ArrayList<SeriesLanguage> = arrayListOf(),
    @JsonProperty("box_type") val boxType: Int? = null,
    @JsonProperty("year_year") val yearYear: String? = null,
    @JsonProperty("season_episode") val seasonEpisode: String? = null,
    @JsonProperty("recommend") val recommend: List<Data> = listOf()
)

data class SeriesSeasonProp(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("msg") val msg: String? = null,
    @JsonProperty("data") val data: ArrayList<SeriesEpisode>? = arrayListOf()
)

data class SeriesEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("tid") val tid: Int? = null,
    @JsonProperty("mb_id") val mbId: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("imdb_id_status") val imdbIdStatus: Int? = null,
    @JsonProperty("srt_status") val srtStatus: Int? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("state") val state: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbs") val thumbs: String? = null,
    @JsonProperty("thumbs_bak") val thumbsBak: String? = null,
    @JsonProperty("thumbs_original") val thumbsOriginal: String? = null,
    @JsonProperty("poster_imdb") val posterImdb: Int? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("view") val view: Int? = null,
    @JsonProperty("download") val download: Int? = null,
    @JsonProperty("source_file") val sourceFile: Int? = null,
    @JsonProperty("code_file") val codeFile: Int? = null,
    @JsonProperty("add_time") val addTime: Int? = null,
    @JsonProperty("update_time") val updateTime: Int? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("released_timestamp") val releasedTimestamp: Long? = null,
    @JsonProperty("audio_lang") val audioLang: String? = null,
    @JsonProperty("quality_tag") val qualityTag: String? = null,
    @JsonProperty("3d") val _3d: Int? = null,
    @JsonProperty("remark") val remark: String? = null,
    @JsonProperty("pending") val pending: String? = null,
    @JsonProperty("imdb_rating") val imdbRating: String? = null,
    @JsonProperty("display") val display: Int? = null,
    @JsonProperty("sync") val sync: Int? = null,
    @JsonProperty("tomato_meter") val tomatoMeter: Int? = null,
    @JsonProperty("tomato_meter_count") val tomatoMeterCount: Int? = null,
    @JsonProperty("tomato_audience") val tomatoAudience: Int? = null,
    @JsonProperty("tomato_audience_count") val tomatoAudienceCount: Int? = null,
    @JsonProperty("thumbs_min") val thumbsMin: String? = null,
    @JsonProperty("thumbs_org") val thumbsOrg: String? = null,
    @JsonProperty("imdb_link") val imdbLink: String? = null,
//        @JsonProperty("quality_tags") val qualityTags: ArrayList<String> = arrayListOf(),
//  @JsonProperty("play_progress"         ) val playProgress        : PlayProgress?     = PlayProgress()

)

data class SubtitleList(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("subtitles") val subtitles: ArrayList<Subtitles> = arrayListOf()
)

data class Subtitles(
    @JsonProperty("sid") val sid: Int? = null,
    @JsonProperty("mid") val mid: String? = null,
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("delay") val delay: Int? = null,
    @JsonProperty("point") val point: String? = null,
    @JsonProperty("order") val order: Int? = null,
    @JsonProperty("admin_order") val adminOrder: Int? = null,
    @JsonProperty("myselect") val myselect: Int? = null,
    @JsonProperty("add_time") val addTime: Long? = null,
    @JsonProperty("count") val count: Int? = null
)

data class PrivateSubtitleData(
    @JsonProperty("select") val select: ArrayList<String> = arrayListOf(),
    @JsonProperty("list") val list: ArrayList<SubtitleList> = arrayListOf()
)

data class SubtitleDataProp(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("msg") val msg: String? = null,
    @JsonProperty("data") val data: PrivateSubtitleData? = PrivateSubtitleData()
)
