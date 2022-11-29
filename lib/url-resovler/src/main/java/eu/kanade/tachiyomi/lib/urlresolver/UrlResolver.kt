package eu.kanade.tachiyomi.lib.urlresolver

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.urlresolver.extractors.Dood
import eu.kanade.tachiyomi.lib.urlresolver.extractors.Multi
import eu.kanade.tachiyomi.lib.urlresolver.extractors.Okru
import eu.kanade.tachiyomi.lib.urlresolver.extractors.StreamTape
import eu.kanade.tachiyomi.lib.urlresolver.extractors.StreamSB
import eu.kanade.tachiyomi.lib.urlresolver.extractors.Fembed
import eu.kanade.tachiyomi.lib.urlresolver.extractors.Linkbox
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Currently supports:
 * DoodStream, Fembed, Okru, StreamSB, StreamTape, Vidbom, Uqload, Vidhd, linkbox, gostream, govad, moshahda
 */
class UrlResolver(private val client: OkHttpClient) {
    fun resolve(url: String, headers: Headers): List<Video> {
        val videoList = mutableListOf<Video>()
        getWebsitesList().forEach{
            val urlRegex = it.regex.toRegex()
            if (urlRegex.containsMatchIn(url)) {
                val match = urlRegex.find(url)!!.groupValues
                val newUrl = it.url.replace("{host}", match[1]).replace("{media_id}", match[2])
                videoList.addAll(
                    when (it.name) {
                        "dood" -> Dood(client).extract(newUrl)
                        "fembed" -> Fembed(client).extract(newUrl)
                        "okru" -> Okru(client).extract(newUrl)
                        "streamsb" -> StreamSB(client).extract(newUrl, headers)
                        "streamtape" -> StreamTape(client).extract(newUrl)
                        "multi" -> Multi(client).extract(newUrl, match[1].substringBefore("."))
                        "linkbox" -> Linkbox(client).extract(newUrl)
                        else -> { emptyList() }
                    }
                )
            }
        }
        return videoList
    }
    private data class Website(val name: Any, val regex: String, val url: String)
    private fun getWebsitesList() = listOf(
        Website(
            "dood",
            "(?://|\\.)(dood(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re))/(?:d|e)/([0-9a-zA-Z]+)",
            "https://{host}/e/{media_id}"
        ),
        Website(
            "fembed",
            """
            (?://|\.)(
            (?:femb[ae]d(?:[-9]hd)?|feurl|femax20|24hd|anime789|[fv]cdn|sharinglink|streamm4u|votrefil[em]s?|
            femoload|asianclub|dailyplanet|[jf]player|mrdhan|there|sexhd|gcloud|mediashore|xstreamcdn|
            vcdnplay|vidohd|vidsource|viplayer|zidiplay|embedsito|dutrag|youvideos|moviepl|vidcloud|
            diasfem|moviemaniac|albavid[eo]|ncdnstm|superplayxyz|cinegrabber|ndrama|jav(?:stream|poll)|
            suzihaza|ezsubz|reeoov|diampokusy|filmvi|vidsrc|i18n|vanfem|watchjavnow)\.
            (?:com|club|io|xyz|pw|net|to|live|me|stream|co|cc|org|ru|tv|fun|info|top|tube))
            /(?:v|f)/([a-zA-Z0-9-]+)""".trimIndent(),
            "https://{host}/v/{media_id}"
        ),
        Website(
            "okru",
            "(?://|\\.)(ok\\.ru|odnoklassniki\\.ru)/(?:videoembed|video|live)/(\\d+)",
            "http://{host}/videoembed/{media_id}"
        ),
        Website(
            "streamsb",
            """(?://|\.)(
               (?:view|watch|embed(?:tv)?|tube|player|cloudemb|japopav|javplaya|p1ayerjavseen|stream(?:ovies)?|vidmovie)?s{0,2}b?
               (?:embed\d?|play\d?|video|fast|full|streams{0,3}|the|speed|l?anh|tvmshow|longvu)?\.(?:com|net|org|one|tv|xyz|fun))/
               (?:embed[-/]|e/|play/|d/|sup/)?([0-9a-zA-Z]+)""".trimIndent(),
            "https://{host}/d/{media_id}.html"
        ),
        Website(
            "streamtape",
            """(?://|\.)(s(?:tr)?(?:eam|have)?(?:ta?p?e?|cloud|adblockplus)\.
               (?:com|cloud|net|pe|site|link|cc|online|fun|cash|to))/(?:e|v)/([0-9a-zA-Z]+)""".trimIndent(),
            "https://{host}/e/{media_id}"
        ),
        Website(
            "multi",
            "(?://|\\.)((?:v[aie]d[bp][aoe]?m|myvii?d|v[aei]dshar[er]?|vidhd|uqload|gostream|govad|moshahda)\\.(?:com|net|org|xyz|fun|pro))(?::\\d+)?/(?:embed[-])?([A-Za-z0-9]+)",
            "https://{host}/embed-{media_id}.html"
        ),
        Website(
            "linkbox",
            "(?://|\\.)((?:linkbox|sharezweb)\\.(?:to|com))/(?:player\\.html\\?id=|file/)([0-9a-zA-Z]+)",
            "https://www.linkbox.to/api/open/get_url?itemId={media_id}"
        )

    )

}
