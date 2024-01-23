package eu.kanade.tachiyomi.multisrc.animestream

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class AnimeStreamGenerator : ThemeSourceGenerator {
    override val themePkg = "animestream"

    override val themeClass = "AnimeStream"

    override val baseVersionCode = 2

    override val sources = listOf(
        SingleLang("AnimeBalkan", "https://animebalkan.org", "sr", isNsfw = false, overrideVersionCode = 1),
        SingleLang("AnimeIndo", "https://animeindo.skin", "id", isNsfw = false, overrideVersionCode = 8),
        SingleLang("AnimeKhor", "https://animekhor.xyz", "en", isNsfw = false, overrideVersionCode = 3),
        SingleLang("Animenosub", "https://animenosub.com", "en", isNsfw = true, overrideVersionCode = 4),
        SingleLang("AnimeXin", "https://animexin.vip", "all", isNsfw = false, overrideVersionCode = 8),
        SingleLang("AnimeYT.es", "https://animeyt.es", "es", isNsfw = false, className = "AnimeYTES", pkgName = "animeytes", overrideVersionCode = 2),
        SingleLang("Tiodonghua.com", "https://anime.tiodonghua.com", "es", isNsfw = false, className = "Tiodonghua", pkgName = "tiodonghua", overrideVersionCode = 1),
        SingleLang("AsyaAnimeleri", "https://asyaanimeleri.com", "tr", isNsfw = false, overrideVersionCode = 3),
        SingleLang("ChineseAnime", "https://chineseanime.top", "all", isNsfw = false, overrideVersionCode = 4),
        SingleLang("desu-online", "https://desu-online.pl", "pl", className = "DesuOnline", isNsfw = false, overrideVersionCode = 4),
        SingleLang("DonghuaStream", "https://donghuastream.co.in", "en", isNsfw = false, overrideVersionCode = 3),
        SingleLang("LMAnime", "https://lmanime.com", "all", isNsfw = false, overrideVersionCode = 6),
        SingleLang("LuciferDonghua", "https://luciferdonghua.in", "en", isNsfw = false, overrideVersionCode = 4),
        SingleLang("MiniOppai", "https://minioppai.org", "id", isNsfw = true, overrideVersionCode = 3),
        SingleLang("RineCloud", "https://rine.cloud", "pt-BR", isNsfw = false, overrideVersionCode = 5),
        SingleLang("TRAnimeCI", "https://tranimaci.com", "tr", isNsfw = false, overrideVersionCode = 1),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = AnimeStreamGenerator().createAll()
    }
}
