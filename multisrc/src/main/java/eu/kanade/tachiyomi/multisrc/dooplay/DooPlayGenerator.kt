package eu.kanade.tachiyomi.multisrc.dooplay

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DooPlayGenerator : ThemeSourceGenerator {
    override val themePkg = "dooplay"

    override val themeClass = "Dooplay"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("AnimeOnline360", "https://animeonline360.me", "en", isNsfw = false),
        SingleLang("AnimeOnline.Ninja", "https://www1.animeonline.ninja", "es", className = "AnimeOnlineNinja", isNsfw = false, overrideVersionCode = 26),
        SingleLang("AnimePlayer", "https://animeplayer.com.br", "pt-BR", isNsfw = true),
        SingleLang("AnimesFox BR", "https://animesfoxbr.com", "pt-BR", isNsfw = false, overrideVersionCode = 1),
        SingleLang("Animes House", "https://animeshouse.net", "pt-BR", isNsfw = false, overrideVersionCode = 4),
        SingleLang("Cinemathek", "https://cinemathek.net", "de", isNsfw = true, overrideVersionCode = 12),
        SingleLang("CineVision", "https://cinevisionv3.online", "pt-BR", isNsfw = true, overrideVersionCode = 5),
        SingleLang("DonghuaX", "https://donghuax.com", "pt-BR", isNsfw = false),
        SingleLang("GoAnimes", "https://goanimes.net", "pt-BR", isNsfw = true),
        SingleLang("Multimovies", "https://multimovies.tech", "en", isNsfw = false, overrideVersionCode = 6),
        SingleLang("pactedanime", "https://pactedanime.com", "en", isNsfw = false, className = "PactedAnime", overrideVersionCode = 4),
        SingleLang("Pi Fansubs", "https://pifansubs.org", "pt-BR", isNsfw = true, overrideVersionCode = 16),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DooPlayGenerator().createAll()
    }
}
