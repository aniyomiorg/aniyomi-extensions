package eu.kanade.tachiyomi.animeextension.en.multimovies

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal fun getMultimoviesFilterList() = AnimeFilterList(
    AnimeFilter.Header("NOTE: Ignored if using text search!"),
    AnimeFilter.Separator(),
    GenreFilter(getGenreList()),
    StreamingFilter(getStreamingList()),
    FicUniFilter(getFictionalUniverseList()),
    ChannelFilter(getChannelList()),
)

internal class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Genres", vals)
internal class StreamingFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Streaming service", vals)
internal class FicUniFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Fictional universe", vals)
internal class ChannelFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Channel", vals)

internal fun getGenreList() = arrayOf(
    Pair("<select>", ""),
    Pair("Action", "action"),
    Pair("Adventure", "adventure"),
    Pair("Animation", "animation"),
    Pair("Crime", "crime"),
    Pair("Comedy", "comedy"),
    Pair("Fantasy", "fantasy"),
    Pair("Family", "family"),
    Pair("Horror", "horror"),
    Pair("Mystery", "mystery"),
    Pair("Romance", "romance"),
    Pair("Thriller", "thriller"),
    Pair("Science Fiction", "science-fiction"),
)

internal fun getStreamingList() = arrayOf(
    Pair("<select>", ""),
    Pair("Amazon Prime", "amazon-prime"),
    Pair("Disney Hotstar", "disney-hotstar"),
    Pair("K-drama", "k-drama"),
    Pair("Netflix", "netflix"),
    Pair("Sony Liv", "sony-liv"),
)

internal fun getFictionalUniverseList() = arrayOf(
    Pair("<select>", ""),
    Pair("DC Universe", "dc-universe"),
    Pair("Fast and Furious movies", "multimovies-com-fast-and-furious-movies"),
    Pair("Harry Potter movies", "multimovies-com-harry-potter-movies"),
    Pair("Marvel Collection", "marvel-collection"),
    Pair("Mission Impossible", "mission-impossible-collection"),
    Pair("Pirates of the Caribbean Collection", "pirates-of-the-caribbean-collection"),
    Pair("Resident Evil", "resident-evil"),
    Pair("Transformers Collection", "transformers-collection"),
    Pair("Wrong Turn", "wrong-turn"),
    Pair("X-Men Collection", "x-men-collection"),
)

internal fun getChannelList() = arrayOf(
    Pair("<select>", ""),
    Pair("Hindi Dub Anime", "anime-hindi"),
    Pair("Anime Series", "anime-series"),
    Pair("Anime Movies", "anime-movies"),
    Pair("Cartoon Network", "cartoon-network"),
    Pair("Disney Channel", "disney-channel"),
    Pair("Disney XD", "disney-xd"),
    Pair("Hungama", "hungama"),
)

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
    AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    override fun toString() = vals[state].second
}
