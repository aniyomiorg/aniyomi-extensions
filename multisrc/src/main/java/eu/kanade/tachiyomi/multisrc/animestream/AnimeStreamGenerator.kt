package eu.kanade.tachiyomi.multisrc.animestream

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class AnimeStreamGenerator : ThemeSourceGenerator {
    override val themePkg = "animestream"

    override val themeClass = "AnimeStream"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("AnimeXin", "https://animexin.vip", "all", isNsfw = false, overrideVersionCode = 4),
        SingleLang("RineCloud", "https://rine.cloud", "pt-BR", isNsfw = false),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = AnimeStreamGenerator().createAll()
    }
}
