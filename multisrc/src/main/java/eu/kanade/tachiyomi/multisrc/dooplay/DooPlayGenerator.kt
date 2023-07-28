package eu.kanade.tachiyomi.multisrc.dooplay

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DooPlayGenerator : ThemeSourceGenerator {
    override val themePkg = "dooplay"

    override val themeClass = "Dooplay"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("AnimeOnline360", "https://animeonline360.me", "en", isNsfw = false),
        SingleLang("AnimeOnline.Ninja", "https://www1.animeonline.ninja", "es", className = "AnimeOnlineNinja", isNsfw = false, overrideVersionCode = 28),
        SingleLang("AnimePlayer", "https://animeplayer.com.br", "pt-BR", isNsfw = true, overrideVersionCode = 1),
        SingleLang("AnimesFox BR", "https://animesfox.net", "pt-BR", isNsfw = false, overrideVersionCode = 2),
        SingleLang("Animes Grátis", "https://animesgratis.org", "pt-BR", className = "AnimesGratis", isNsfw = false, overrideVersionCode = 1),
        SingleLang("Animes House", "https://animeshouse.net", "pt-BR", isNsfw = false, overrideVersionCode = 5),
        SingleLang("Cinemathek", "https://cinemathek.net", "de", isNsfw = true, overrideVersionCode = 13),
        SingleLang("CineVision", "https://cinevisionv3.online", "pt-BR", isNsfw = true, overrideVersionCode = 5),
        SingleLang("DonghuaX", "https://donghuax.com", "pt-BR", isNsfw = false),
        SingleLang("GoAnimes", "https://goanimes.net", "pt-BR", isNsfw = true, overrideVersionCode = 2),
        SingleLang("JetAnime", "https://ssl.jetanimes.com", "fr", isNsfw = false),
        SingleLang("Kinoking", "https://kinoking.cc", "de", isNsfw = false, overrideVersionCode = 16),
        SingleLang("Multimovies", "https://multimovies.shop", "en", isNsfw = false, overrideVersionCode = 8),
        SingleLang("pactedanime", "https://pactedanime.com", "en", isNsfw = false, className = "PactedAnime", overrideVersionCode = 4),
        SingleLang("Pi Fansubs", "https://pifansubs.org", "pt-BR", isNsfw = true, overrideVersionCode = 16),
        SingleLang("Pobreflix", "https://pobreflix.biz", "pt-BR", isNsfw = true),
        SingleLang("UniqueStream", "https://uniquestream.net", "en", isNsfw = false, overrideVersionCode = 2),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DooPlayGenerator().createAll()
    }
}
