package eu.kanade.tachiyomi.multisrc.dopeflix

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DopeFlixGenerator : ThemeSourceGenerator {
    override val themePkg = "dopeflix"

    override val themeClass = "DopeFlix"

    override val baseVersionCode = 19

    override val sources = listOf(
        SingleLang("DopeBox", "https://dopebox.to", "en", isNsfw = false, overrideVersionCode = 6),
        SingleLang("SFlix", "https://sflix.to", "en", isNsfw = false, overrideVersionCode = 5),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DopeFlixGenerator().createAll()
    }
}
