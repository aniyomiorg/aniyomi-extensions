package eu.kanade.tachiyomi.multisrc.dooplay

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DooPlayGenerator : ThemeSourceGenerator {
    override val themePkg = "dooplay"

    override val themeClass = "Dooplay"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("AnimeOnline.Ninja", "https://ww3.animeonline.ninja", "es", className = "AnimeOnlineNinja", isNsfw = false, overrideVersionCode = 35),
        SingleLang("AnimesOnline", "https://animesonline.nz", "pt-BR", isNsfw = false, overrideVersionCode = 7, pkgName = "animesgratis"),
        SingleLang("AnimePlayer", "https://animeplayer.com.br", "pt-BR", isNsfw = true, overrideVersionCode = 2),
        SingleLang("AnimeSAGA", "https://www.animesaga.in", "hi", isNsfw = false, overrideVersionCode = 9),
        SingleLang("AnimesFox BR", "https://animesfox.net", "pt-BR", isNsfw = false, overrideVersionCode = 2),
        SingleLang("Animes House", "https://animeshouse.net", "pt-BR", isNsfw = false, overrideVersionCode = 9),
        SingleLang("Cinemathek", "https://cinemathek.net", "de", isNsfw = true, overrideVersionCode = 18),
        SingleLang("GoAnimes", "https://goanimes.net", "pt-BR", isNsfw = true, overrideVersionCode = 8),
        SingleLang("JetAnime", "https://ssl.jetanimes.com", "fr", isNsfw = false, overrideVersionCode = 3),
        SingleLang("Kinoking", "https://kinoking.cc", "de", isNsfw = false, overrideVersionCode = 19),
        SingleLang("Multimovies", "https://multimovies.live", "en", isNsfw = false, overrideVersionCode = 14),
        SingleLang("Pi Fansubs", "https://pifansubs.club", "pt-BR", isNsfw = true, overrideVersionCode = 18),
        SingleLang("Pobreflix", "https://pobreflix.biz", "pt-BR", isNsfw = true, overrideVersionCode = 4),
        SingleLang("UniqueStream", "https://uniquestream.net", "en", isNsfw = false, overrideVersionCode = 2),
        SingleLang("VoirCartoon", "https://voircartoon.com", "fr", isNsfw = true, overrideVersionCode = 1),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DooPlayGenerator().createAll()
    }
}
