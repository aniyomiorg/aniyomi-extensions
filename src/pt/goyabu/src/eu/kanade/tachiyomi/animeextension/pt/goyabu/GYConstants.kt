package eu.kanade.tachiyomi.animeextension.pt.goyabu

object GYConstants {
    const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
    const val USER_AGENT = "Mozilla/5.0 (iPad; CPU OS 13_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.4 Mobile/15E148 Safari/604.1"
    const val PREFERRED_QUALITY = "preferred_quality"
    const val PREFERRED_PLAYER = "preferred_player"
    val QUALITY_LIST = arrayOf("SD", "HD")
    val PLAYER_NAMES = arrayOf("Player 1", "Player 2")
    val PLAYER_REGEX = Regex("""label: "(\w+)",.*file: "(.*?)"""")
}
