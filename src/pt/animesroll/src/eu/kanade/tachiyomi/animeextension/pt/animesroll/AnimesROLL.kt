package eu.kanade.tachiyomi.animeextension.pt.animesroll

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Request
import okhttp3.Response

class AnimesROLL : AnimeHttpSource() {

    override val name = "AnimesROLL"

    override val baseUrl = "https://www.anroll.net"

    override val lang = "pt-BR"

    override val supportsLatest = false

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        TODO("Not yet implemented")
    }

    override fun popularAnimeRequest(page: Int): Request {
        TODO("Not yet implemented")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        TODO("Not yet implemented")
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        TODO("Not yet implemented")
    }

    override fun videoListParse(response: Response): List<Video> {
        TODO("Not yet implemented")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeParse(response: Response): AnimesPage {
        TODO("Not yet implemented")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        TODO("Not yet implemented")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesRequest(page: Int): Request {
        TODO("Not yet implemented")
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
