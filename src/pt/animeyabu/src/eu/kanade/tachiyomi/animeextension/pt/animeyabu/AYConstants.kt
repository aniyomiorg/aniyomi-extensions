package eu.kanade.tachiyomi.animeextension.pt.animeyabu

object AYConstants {
    const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; SM-A307GT Build/QP1A.190711.020;) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.71 Mobile Safari/537.36"
    const val PREFERRED_QUALITY = "preferred_quality"
    const val PREFERRED_PLAYER = "preferred_player"
    const val PREFIX_SEARCH_SLUG = "slug:"
    val QUALITY_LIST = arrayOf("SD", "HD")
    val PLAYER_NAMES = arrayOf("Player 1", "Player 2")
    val PLAYER_REGEX = Regex("""label: "(\w+)",.*file: "(.*?)"""")
}
