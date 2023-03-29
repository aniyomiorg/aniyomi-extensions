package eu.kanade.tachiyomi.multisrc.dooplay

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DooPlayGenerator : ThemeSourceGenerator {
    override val themePkg = "dooplay"

    override val themeClass = "Dooplay"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("Animes House", "https://animeshouse.net", "pt-BR", isNsfw = false, overrideVersionCode = 4),
        SingleLang("Cinemathek", "https://cinemathek.net", "de", isNsfw = true, overrideVersionCode = 9),
        SingleLang("CineVision", "https://cinevision.vc", "pt-BR", isNsfw = true, overrideVersionCode = 4),
        SingleLang("pactedanime", "https://pactedanime.com", "en", isNsfw = false, overrideVersionCode = 4),
        SingleLang("Pi Fansubs", "https://pifansubs.org", "pt-BR", isNsfw = true, overrideVersionCode = 12),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DooPlayGenerator().createAll()
    }
}
