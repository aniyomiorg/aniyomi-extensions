package eu.kanade.tachiyomi.animeextension.pt.animevibe

import kotlinx.serialization.Serializable

typealias AnimeVibePopularDto = AnimeVibeResultDto<List<AnimeVibeAnimeDto>>
typealias AnimeVibeEpisodeListDto = AnimeVibeResultDto<List<AnimeVibeEpisodeDto>>

@Serializable
data class AnimeVibeResultDto<T>(
    val data: T? = null
)

/*
  id: 26673,
  slug: 'kyou-no-asuka-show',
  type: 'Anime',
  audio: 'Legendado',
  score: 6.3,
  title: { native: '今日のあすかショー', romaji: 'Kyou no Asuka Show', english: null },
  views: 0,
  format: 'Ona',
  genres: [ 'Comédia', 'Ecchi', 'Seinen' ],
  season: 'Verão',
  source: 'Manga',
  status: 'Completo',
  studios: [ 'Lantis', 'Half H.P Studio' ],
  duration: '3 min',
  episodes: 20,
  synonyms: [ "Today's Asuka show" ],
  producers: [ 'SILVER LINK.' ],
  startDate: { day: 3, year: 2012, month: 8 },
  description: `Asuka é uma garota bonita e sem noção do ensino médio que costuma fazer coisas, com toda inocência, que parecem sexualmente sugestivas para os homens ao seu redor. Cada capítulo de "Today's Asuka Show" apresenta uma situação diferente e embaraçosa. \n` +
    '\n',
  countryOfOrigin: 'JP'
 */

@Serializable
data class AnimeVibeAnimeDto(
    val id: Int = -1,
    val slug: String = "",
    val audio: String = "",
    val title: Map<String, String?> = emptyMap(),
    val views: Int = -1,
    val genres: List<String>? = emptyList(),
    val status: String? = "",
    val description: String? = ""
)

/*
{
  audio: 'Legendado',
  title: 'Koharu Biyori',
  number: 3,
  mediaID: 27476,
  videoSource: [
    'https://www.blogger.com/video.g?token=AD6v5dyxFaNmvMsasiRlDr8PiwoMlF7j4ykPewpKr_uRyAPZifCqlt0nj5D1oOgI9bjhbvKBO0CRwDUTXg-U81pRt6FBuj_GN6ynwPY5bwtYyAeURxT1HrB4MWxwr70mIP1m-8NHGrNs'
  ],
  datePublished: '2022-01-28T21:41:18.366Z'
}
 */

@Serializable
data class AnimeVibeEpisodeDto(
    val title: String = "",
    val number: Float = -1f,
    val mediaID: Int = -1,
    val videoSource: List<String> = emptyList(),
    val datePublished: String = ""
)
