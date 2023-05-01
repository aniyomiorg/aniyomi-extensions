package eu.kanade.tachiyomi.multisrc.dooplay

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DooPlayGenerator : ThemeSourceGenerator {
    override val themePkg = "dooplay"

    override val themeClass = "Dooplay"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("Animes House", "https://animeshouse.net", "pt-BR", isNsfw = false, overrideVersionCode = 4),
        SingleLang("AnimePlayer", "https://animeplayer.com.br", "pt-BR", isNsfw = true),
        SingleLang("Cinemathek", "https://cinemathek.net", "de", isNsfw = true, overrideVersionCode = 11),
        SingleLang("CineVision", "https://cinevision.vc", "pt-BR", isNsfw = true, overrideVersionCode = 4),
        SingleLang("GoAnimes", "https://goanimes.net", "pt-BR", isNsfw = true),
        SingleLang("pactedanime", "https://pactedanime.com", "en", isNsfw = false, overrideVersionCode = 4),
        SingleLang("Pi Fansubs", "https://pifansubs.org", "pt-BR", isNsfw = true, overrideVersionCode = 14),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DooPlayGenerator().createAll()
    }
}
