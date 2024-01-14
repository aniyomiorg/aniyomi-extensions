package eu.kanade.tachiyomi.multisrc.zorotheme

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class ZoroThemeGenerator : ThemeSourceGenerator {
    override val themePkg = "zorotheme"

    override val themeClass = "ZoroTheme"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("AniWatch", "https://aniwatch.to", "en", isNsfw = false, pkgName = "zoro", overrideVersionCode = 36),
        SingleLang("Kaido", "https://kaido.to", "en", isNsfw = false, overrideVersionCode = 3),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = ZoroThemeGenerator().createAll()
    }
}
