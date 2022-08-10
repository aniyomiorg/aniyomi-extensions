package eu.kanade.tachiyomi.animeextension.en.hahomoe

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception
import java.lang.Float.parseFloat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayList

class HahoMoe : ParsedAnimeHttpSource() {

    override val name = "haho.moe"

    override val baseUrl = "https://haho.moe"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularAnimeSelector(): String = "ul.anime-loop.loop li a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime?s=vdy-d&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        anime.title = element.select("div span").not(".badge").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun episodeListSelector() = "ul.episode-loop li a"

    private fun episodeNextPageSelector() = popularAnimeNextPageSelector()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodes(document: Document) {
            document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
            document.select(episodeNextPageSelector()).firstOrNull()
                ?.let { addEpisodes(client.newCall(GET(it.attr("href"), headers)).execute().asJsoup()) }
        }

        addEpisodes(response.asJsoup())
        return episodes
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        val episodeNumberString = element.select("div.episode-number").text().removePrefix("Episode ")
        var numeric = true
        try {
            parseFloat(episodeNumberString)
        } catch (e: NumberFormatException) {
            numeric = false
        }
        episode.episode_number = if (numeric) episodeNumberString.toFloat() else element.parent().className().removePrefix("episode").toFloat()
        episode.name = element.select("div.episode-number").text() + ": " + element.select("div.episode-label").text() + element.select("div.episode-title").text()
        val date: String = element.select("div.date").text()
        val parsedDate = parseDate(date)
        if (parsedDate.time != -1L) episode.date_upload = parsedDate.time
        return episode
    }

    @SuppressLint("SimpleDateFormat")
    private fun parseDate(date: String): Date {
        val knownPatterns: MutableList<SimpleDateFormat> = ArrayList()
        knownPatterns.add(SimpleDateFormat("dd'th of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'nd of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'st of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'rd of 'MMM, yyyy"))

        for (pattern in knownPatterns) {
            try {
                // Take a try
                return Date(pattern.parse(date)!!.time)
            } catch (e: Throwable) {
                // Loop on
            }
        }
        return Date(-1L)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("src")
        val referer = response.request.url.encodedPath
        val newHeaderList = mutableMapOf(Pair("referer", baseUrl + referer))
        headers.forEach { newHeaderList[it.first] = it.second }
        val iframeResponse = client.newCall(GET(iframe, newHeaderList.toHeaders()))
            .execute().asJsoup()
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        return Video(element.attr("src"), element.attr("title"), element.attr("src"))
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        anime.title = element.select("div span.thumb-title, div span.text-primary").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "ul.anime-loop.loop li a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val (includedTags, blackListedTags, orderBy, ordering) = getSearchParameters(filters)

        val incTags = includedTags.ifEmpty { null }
            ?.joinToString(prefix = "+genre:", separator = "+genre:") ?: ""
        val excTags = blackListedTags.ifEmpty { null }
            ?.joinToString(prefix = "+-genre:", separator = "+-genre:") ?: ""
        return GET("$baseUrl/anime?q=title:$query$incTags$excTags&page=$page&s=$orderBy$ordering")
    }
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.cover-image.img-thumbnail").first().attr("src")
        anime.title = document.select("li.breadcrumb-item.active").text()
        anime.genre = document.select("li.genre span.value").joinToString(", ") { it.text() }
        anime.description = document.select("div.card-body").text()
        anime.author = document.select("li.production span.value").joinToString(", ") { it.text() }
        anime.artist = document.select("li.group span.value").text()
        anime.status = parseStatus(document.select("li.status span.value").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        anime.title = element.select("div span").not(".badge").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime?s=rel-d&page=$page")
    override fun latestUpdatesSelector(): String = "ul.anime-loop.loop li a"
    internal class Tag(val id: String, name: String) : AnimeFilter.TriState(name)
    private class TagList(tags: List<Tag>) : AnimeFilter.Group<Tag>("Tags", tags)
    private fun getTags() = listOf(
        Tag("3D CG animation", "3D CG animation"),
        Tag("absurdist humour", "absurdist humour"),
        Tag("action", "action"),
        Tag("action game", "action game"),
        Tag("adapted into Japanese movie", "adapted into Japanese movie"),
        Tag("adapted into JDrama", "adapted into JDrama"),
        Tag("adapted into other media", "adapted into other media"),
        Tag("adults are useless", "adults are useless"),
        Tag("adventure", "adventure"),
        Tag("Africa", "Africa"),
        Tag("age difference romance", "age difference romance"),
        Tag("ahegao", "ahegao"),
        Tag("air force", "air force"),
        Tag("Akihabara", "Akihabara"),
        Tag("alcohol", "alcohol"),
        Tag("alien", "alien"),
        Tag("alien invasion", "alien invasion"),
        Tag("all-boys school", "all-boys school"),
        Tag("all-girls school", "all-girls school"),
        Tag("alternating animation style", "alternating animation style"),
        Tag("alternative past", "alternative past"),
        Tag("alternative present", "alternative present"),
        Tag("Americas", "Americas"),
        Tag("amnesia", "amnesia"),
        Tag("anal", "anal"),
        Tag("anal fingering", "anal fingering"),
        Tag("anal pissing", "anal pissing"),
        Tag("android", "android"),
        Tag("angel", "angel"),
        Tag("angst", "angst"),
        Tag("animal abuse", "animal abuse"),
        Tag("animal protagonist", "animal protagonist"),
        Tag("Animerama", "Animerama"),
        Tag("anthropomorphism", "anthropomorphism"),
        Tag("aphrodisiac", "aphrodisiac"),
        Tag("archery", "archery"),
        Tag("Asia", "Asia"),
        Tag("ass-kicking girls", "ass-kicking girls"),
        Tag("assjob", "assjob"),
        Tag("association football", "association football"),
        Tag("attempted rape", "attempted rape"),
        Tag("aunt-nephew incest", "aunt-nephew incest"),
        Tag("autofellatio", "autofellatio"),
        Tag("autumn", "autumn"),
        Tag("bad cooking", "bad cooking"),
        Tag("Bakumatsu - Meiji Period", "Bakumatsu - Meiji Period"),
        Tag("baseball", "baseball"),
        Tag("basketball", "basketball"),
        Tag("BDSM", "BDSM"),
        Tag("be careful what you wish for", "be careful what you wish for"),
        Tag("bestiality", "bestiality"),
        Tag("betrayal", "betrayal"),
        Tag("bishoujo", "bishoujo"),
        Tag("bishounen", "bishounen"),
        Tag("bitter-sweet", "bitter-sweet"),
        Tag("black humour", "black humour"),
        Tag("blackmail", "blackmail"),
        Tag("board games", "board games"),
        Tag("body and host", "body and host"),
        Tag("body exchange", "body exchange"),
        Tag("body takeover", "body takeover"),
        Tag("bondage", "bondage"),
        Tag("borderline porn", "borderline porn"),
        Tag("boxing", "boxing"),
        Tag("boy meets girl", "boy meets girl"),
        Tag("brainwashing", "brainwashing"),
        Tag("branching story", "branching story"),
        Tag("breast expansion", "breast expansion"),
        Tag("breast fondling", "breast fondling"),
        Tag("breasts", "breasts"),
        Tag("brother-sister incest", "brother-sister incest"),
        Tag("Buddhism", "Buddhism"),
        Tag("bukkake", "bukkake"),
        Tag("bullying", "bullying"),
        Tag("call my name", "call my name"),
        Tag("calling your attacks", "calling your attacks"),
        Tag("car crash", "car crash"),
        Tag("cast", "cast"),
        Tag("castaway", "castaway"),
        Tag("catholic school", "catholic school"),
        Tag("censored uncensored version", "censored uncensored version"),
        Tag("cervix penetration", "cervix penetration"),
        Tag("CG collection", "CG collection"),
        Tag("CGI", "CGI"),
        Tag("cheating", "cheating"),
        Tag("chikan", "chikan"),
        Tag("child abuse", "child abuse"),
        Tag("China", "China"),
        Tag("Christianity", "Christianity"),
        Tag("classical music", "classical music"),
        Tag("cockring", "cockring"),
        Tag("collateral damage", "collateral damage"),
        Tag("colour coded", "colour coded"),
        Tag("combat", "combat"),
        Tag("comedy", "comedy"),
        Tag("coming of age", "coming of age"),
        Tag("competition", "competition"),
        Tag("conspiracy", "conspiracy"),
        Tag("contemporary fantasy", "contemporary fantasy"),
        Tag("content indicators", "content indicators"),
        Tag("contraband", "contraband"),
        Tag("cooking", "cooking"),
        Tag("cops", "cops"),
        Tag("corrupt church", "corrupt church"),
        Tag("corrupt nobility", "corrupt nobility"),
        Tag("cosplaying", "cosplaying"),
        Tag("countryside", "countryside"),
        Tag("cram school", "cram school"),
        Tag("creampie", "creampie"),
        Tag("crime", "crime"),
        Tag("cross-dressing", "cross-dressing"),
        Tag("cum play", "cum play"),
        Tag("cum swapping", "cum swapping"),
        Tag("cunnilingus", "cunnilingus"),
        Tag("curse", "curse"),
        Tag("cyberpunk", "cyberpunk"),
        Tag("cybersex", "cybersex"),
        Tag("cyborg", "cyborg"),
        Tag("daily life", "daily life"),
        Tag("damsel in distress", "damsel in distress"),
        Tag("dancing", "dancing"),
        Tag("dark", "dark"),
        Tag("dark atmosphere", "dark atmosphere"),
        Tag("dark elf", "dark elf"),
        Tag("dark fantasy", "dark fantasy"),
        Tag("dark-skinned girl", "dark-skinned girl"),
        Tag("death", "death"),
        Tag("defeat means friendship", "defeat means friendship"),
        Tag("deflowering", "deflowering"),
        Tag("deity", "deity"),
        Tag("delinquent", "delinquent"),
        Tag("dementia", "dementia"),
        Tag("demon", "demon"),
        Tag("demon hunt", "demon hunt"),
        Tag("demonic power", "demonic power"),
        Tag("DESCRIPTION NEEDS IMPROVEMENT", "DESCRIPTION NEEDS IMPROVEMENT"),
        Tag("desert", "desert"),
        Tag("despair", "despair"),
        Tag("detective", "detective"),
        Tag("dildos - vibrators", "dildos - vibrators"),
        Tag("disaster", "disaster"),
        Tag("discontinued", "discontinued"),
        Tag("disturbing", "disturbing"),
        Tag("divorce", "divorce"),
        Tag("doggy style", "doggy style"),
        Tag("dominatrix", "dominatrix"),
        Tag("double fellatio", "double fellatio"),
        Tag("double penetration", "double penetration"),
        Tag("double-sided dildo", "double-sided dildo"),
        Tag("doujin", "doujin"),
        Tag("dragon", "dragon"),
        Tag("drastic change of life", "drastic change of life"),
        Tag("dreams", "dreams"),
        Tag("dreams and reality", "dreams and reality"),
        Tag("drugs", "drugs"),
        Tag("dungeon", "dungeon"),
        Tag("dutch wife", "dutch wife"),
        Tag("dynamic", "dynamic"),
        Tag("dysfunctional family", "dysfunctional family"),
        Tag("dystopia", "dystopia"),
        Tag("Earth", "Earth"),
        Tag("earthquake", "earthquake"),
        Tag("eating of humans", "eating of humans"),
        Tag("ecchi", "ecchi"),
        Tag("Egypt", "Egypt"),
        Tag("elements", "elements"),
        Tag("elf", "elf"),
        Tag("emotions awaken superpowers", "emotions awaken superpowers"),
        Tag("ending", "ending"),
        Tag("enema", "enema"),
        Tag("Engrish", "Engrish"),
        Tag("enjo-kousai", "enjo-kousai"),
        Tag("enjoyable rape", "enjoyable rape"),
        Tag("entertainment industry", "entertainment industry"),
        Tag("episodic", "episodic"),
        Tag("erotic asphyxiation", "erotic asphyxiation"),
        Tag("erotic game", "erotic game"),
        Tag("erotic torture", "erotic torture"),
        Tag("Europe", "Europe"),
        Tag("European stylised", "European stylised"),
        Tag("everybody dies", "everybody dies"),
        Tag("everybody has sex", "everybody has sex"),
        Tag("evil military", "evil military"),
        Tag("excessive censoring", "excessive censoring"),
        Tag("exhibitionism", "exhibitionism"),
        Tag("exorcism", "exorcism"),
        Tag("experimental animation", "experimental animation"),
        Tag("extrasensory perception", "extrasensory perception"),
        Tag("eye penetration", "eye penetration"),
        Tag("faceless background characters", "faceless background characters"),
        Tag("facesitting", "facesitting"),
        Tag("facial distortion", "facial distortion"),
        Tag("fairy", "fairy"),
        Tag("fake relationship", "fake relationship"),
        Tag("family life", "family life"),
        Tag("family without mother", "family without mother"),
        Tag("fantasy", "fantasy"),
        Tag("father-daughter incest", "father-daughter incest"),
        Tag("felching", "felching"),
        Tag("fellatio", "fellatio"),
        Tag("female protagonist", "female protagonist"),
        Tag("female rapes female", "female rapes female"),
        Tag("female student", "female student"),
        Tag("female teacher", "female teacher"),
        Tag("femdom", "femdom"),
        Tag("feminism", "feminism"),
        Tag("fetishes", "fetishes"),
        Tag("feudal warfare", "feudal warfare"),
        Tag("FFM threesome", "FFM threesome"),
        Tag("fictional location", "fictional location"),
        Tag("fighting", "fighting"),
        Tag("fingering", "fingering"),
        Tag("fire", "fire"),
        Tag("first love", "first love"),
        Tag("fishing", "fishing"),
        Tag("fisting", "fisting"),
        Tag("foot fetish", "foot fetish"),
        Tag("footage reuse", "footage reuse"),
        Tag("footjob", "footjob"),
        Tag("forbidden love", "forbidden love"),
        Tag("foreskin sex", "foreskin sex"),
        Tag("foursome", "foursome"),
        Tag("France", "France"),
        Tag("French kiss", "French kiss"),
        Tag("friendship", "friendship"),
        Tag("full HD version available", "full HD version available"),
        Tag("funny expressions", "funny expressions"),
        Tag("futa x female", "futa x female"),
        Tag("futa x futa", "futa x futa"),
        Tag("futa x male", "futa x male"),
        Tag("futanari", "futanari"),
        Tag("future", "future"),
        Tag("Gainax bounce", "Gainax bounce"),
        Tag("game", "game"),
        Tag("gang bang", "gang bang"),
        Tag("gang rape", "gang rape"),
        Tag("gangs", "gangs"),
        Tag("gender bender", "gender bender"),
        Tag("genetic modification", "genetic modification"),
        Tag("Germany", "Germany"),
        Tag("ghost", "ghost"),
        Tag("ghost hunting", "ghost hunting"),
        Tag("giant insects", "giant insects"),
        Tag("gigantic breasts", "gigantic breasts"),
        Tag("girl rapes girl", "girl rapes girl"),
        Tag("girly tears", "girly tears"),
        Tag("glory hole", "glory hole"),
        Tag("go", "go"),
        Tag("god is a girl", "god is a girl"),
        Tag("gokkun", "gokkun"),
        Tag("golden shower", "golden shower"),
        Tag("gore", "gore"),
        Tag("grandiose displays of wealth", "grandiose displays of wealth"),
        Tag("Greek mythology", "Greek mythology"),
        Tag("groping", "groping"),
        Tag("group love", "group love"),
        Tag("gunfights", "gunfights"),
        Tag("guro", "guro"),
        Tag("gymnastics", "gymnastics"),
        Tag("half-length episodes", "half-length episodes"),
        Tag("handjob", "handjob"),
        Tag("happy ending", "happy ending"),
        Tag("harem", "harem"),
        Tag("heaven", "heaven"),
        Tag("hell", "hell"),
        Tag("henshin", "henshin"),
        Tag("heroic sacrifice", "heroic sacrifice"),
        Tag("hidden agenda", "hidden agenda"),
        Tag("hidden vibrator", "hidden vibrator"),
        Tag("high fantasy", "high fantasy"),
        Tag("high school", "high school"),
        Tag("historical", "historical"),
        Tag("Hong Kong", "Hong Kong"),
        Tag("horny nosebleed", "horny nosebleed"),
        Tag("horror", "horror"),
        Tag("hospital", "hospital"),
        Tag("hostage situation", "hostage situation"),
        Tag("housewives", "housewives"),
        Tag("human cannibalism", "human cannibalism"),
        Tag("human enhancement", "human enhancement"),
        Tag("human experimentation", "human experimentation"),
        Tag("human sacrifice", "human sacrifice"),
        Tag("human-android love", "human-android love"),
        Tag("humanoid alien", "humanoid alien"),
        Tag("hyperspace mallet", "hyperspace mallet"),
        Tag("i got a crush on you", "i got a crush on you"),
        Tag("ice skating", "ice skating"),
        Tag("idol", "idol"),
        Tag("immortality", "immortality"),
        Tag("imperial stormtrooper marksmanship academy", "imperial stormtrooper marksmanship academy"),
        Tag("important haircut", "important haircut"),
        Tag("impregnation", "impregnation"),
        Tag("impregnation with larvae", "impregnation with larvae"),
        Tag("improbable physics", "improbable physics"),
        Tag("in medias res", "in medias res"),
        Tag("incest", "incest"),
        Tag("India", "India"),
        Tag("infidelity", "infidelity"),
        Tag("Injuu Hentai Series", "Injuu Hentai Series"),
        Tag("inter-dimensional schoolgirl", "inter-dimensional schoolgirl"),
        Tag("intercrural sex", "intercrural sex"),
        Tag("internal shots", "internal shots"),
        Tag("isekai", "isekai"),
        Tag("island", "island"),
        Tag("Japan", "Japan"),
        Tag("Japanese mythology", "Japanese mythology"),
        Tag("jealousy", "jealousy"),
        Tag("Journey to the West", "Journey to the West"),
        Tag("just as planned", "just as planned"),
        Tag("juujin", "juujin"),
        Tag("kamikaze", "kamikaze"),
        Tag("kendo", "kendo"),
        Tag("kidnapping", "kidnapping"),
        Tag("killing criminals", "killing criminals"),
        Tag("Korea", "Korea"),
        Tag("lactation", "lactation"),
        Tag("large breasts", "large breasts"),
        Tag("law and order", "law and order"),
        Tag("library", "library"),
        Tag("light-hearted", "light-hearted"),
        Tag("lingerie", "lingerie"),
        Tag("live-action imagery", "live-action imagery"),
        Tag("loli", "loli"),
        Tag("long episodes", "long episodes"),
        Tag("love between enemies", "love between enemies"),
        Tag("love polygon", "love polygon"),
        Tag("macabre", "macabre"),
        Tag("mafia", "mafia"),
        Tag("magic", "magic"),
        Tag("magic circles", "magic circles"),
        Tag("magic weapons", "magic weapons"),
        Tag("magical girl", "magical girl"),
        Tag("maid", "maid"),
        Tag("main character dies", "main character dies"),
        Tag("maintenance tags", "maintenance tags"),
        Tag("male protagonist", "male protagonist"),
        Tag("male rape victim", "male rape victim"),
        Tag("mammary intercourse", "mammary intercourse"),
        Tag("manga", "manga"),
        Tag("manipulation", "manipulation"),
        Tag("martial arts", "martial arts"),
        Tag("massacre", "massacre"),
        Tag("master-servant relationship", "master-servant relationship"),
        Tag("master-slave relation", "master-slave relation"),
        Tag("masturbation", "masturbation"),
        Tag("mecha", "mecha"),
        Tag("mechanical tentacle", "mechanical tentacle"),
        Tag("medium awareness", "medium awareness"),
        Tag("merchandising show", "merchandising show"),
        Tag("mermaid", "mermaid"),
        Tag("meta tags", "meta tags"),
        Tag("Middle East", "Middle East"),
        Tag("middle school", "middle school"),
        Tag("military", "military"),
        Tag("military is useless", "military is useless"),
        Tag("mind fuck", "mind fuck"),
        Tag("misunderstanding", "misunderstanding"),
        Tag("MMF threesome", "MMF threesome"),
        Tag("MMM threesome", "MMM threesome"),
        Tag("molestation", "molestation"),
        Tag("money", "money"),
        Tag("monster of the week", "monster of the week"),
        Tag("mother-daughter incest", "mother-daughter incest"),
        Tag("mother-son incest", "mother-son incest"),
        Tag("movie", "movie"),
        Tag("multi-anime projects", "multi-anime projects"),
        Tag("multi-segment episodes", "multi-segment episodes"),
        Tag("multiple couples", "multiple couples"),
        Tag("murder", "murder"),
        Tag("murder of family members", "murder of family members"),
        Tag("music", "music"),
        Tag("musical band", "musical band"),
        Tag("mutation", "mutation"),
        Tag("mutilation", "mutilation"),
        Tag("mystery", "mystery"),
        Tag("mythology", "mythology"),
        Tag("Nagasaki", "Nagasaki"),
        Tag("narration", "narration"),
        Tag("navel fuck", "navel fuck"),
        Tag("navy", "navy"),
        Tag("nearly almighty protagonist", "nearly almighty protagonist"),
        Tag("necrophilia", "necrophilia"),
        Tag("nervous breakdown", "nervous breakdown"),
        Tag("netorare", "netorare"),
        Tag("netori", "netori"),
        Tag("new", "new"),
        Tag("ninja", "ninja"),
        Tag("nipple penetration", "nipple penetration"),
        Tag("non-linear", "non-linear"),
        Tag("Norse mythology", "Norse mythology"),
        Tag("nostril hook", "nostril hook"),
        Tag("not for kids", "not for kids"),
        Tag("novel", "novel"),
        Tag("nudity", "nudity"),
        Tag("nun", "nun"),
        Tag("nurse", "nurse"),
        Tag("nurse office", "nurse office"),
        Tag("nyotaimori", "nyotaimori"),
        Tag("occult", "occult"),
        Tag("occupation and career", "occupation and career"),
        Tag("ocean", "ocean"),
        Tag("off-model animation", "off-model animation"),
        Tag("office lady", "office lady"),
        Tag("older female younger male", "older female younger male"),
        Tag("omnibus format", "omnibus format"),
        Tag("onahole", "onahole"),
        Tag("One Thousand and One Nights", "One Thousand and One Nights"),
        Tag("onmyoudou", "onmyoudou"),
        Tag("open-ended", "open-ended"),
        Tag("oral", "oral"),
        Tag("orgasm denial", "orgasm denial"),
        Tag("orgy", "orgy"),
        Tag("origin", "origin"),
        Tag("original work", "original work"),
        Tag("otaku culture", "otaku culture"),
        Tag("other planet", "other planet"),
        Tag("out-of-body experience", "out-of-body experience"),
        Tag("outdoor sex", "outdoor sex"),
        Tag("oyakodon", "oyakodon"),
        Tag("painting", "painting"),
        Tag("pantsu", "pantsu"),
        Tag("panty theft", "panty theft"),
        Tag("pantyjob", "pantyjob"),
        Tag("paper clothes", "paper clothes"),
        Tag("parallel world", "parallel world"),
        Tag("parasite", "parasite"),
        Tag("parental abandonment", "parental abandonment"),
        Tag("parody", "parody"),
        Tag("parricide", "parricide"),
        Tag("past", "past"),
        Tag("pegging", "pegging"),
        Tag("performance", "performance"),
        Tag("photographic backgrounds", "photographic backgrounds"),
        Tag("photography", "photography"),
        Tag("pillory", "pillory"),
        Tag("piloted robot", "piloted robot"),
        Tag("pirate", "pirate"),
        Tag("place", "place"),
        Tag("plot continuity", "plot continuity"),
        Tag("plot twists", "plot twists"),
        Tag("plot with porn", "plot with porn"),
        Tag("point of view", "point of view"),
        Tag("police", "police"),
        Tag("police are useless", "police are useless"),
        Tag("pornography", "pornography"),
        Tag("post-apocalyptic", "post-apocalyptic"),
        Tag("poverty", "poverty"),
        Tag("power corrupts", "power corrupts"),
        Tag("power suit", "power suit"),
        Tag("predominantly adult cast", "predominantly adult cast"),
        Tag("predominantly female cast", "predominantly female cast"),
        Tag("predominantly male cast", "predominantly male cast"),
        Tag("pregnant sex", "pregnant sex"),
        Tag("present", "present"),
        Tag("prison", "prison"),
        Tag("promise", "promise"),
        Tag("prostate massage", "prostate massage"),
        Tag("prostitution", "prostitution"),
        Tag("proxy battles", "proxy battles"),
        Tag("psychoactive drugs", "psychoactive drugs"),
        Tag("psychological", "psychological"),
        Tag("psychological manipulation", "psychological manipulation"),
        Tag("psychological sexual abuse", "psychological sexual abuse"),
        Tag("public sex", "public sex"),
        Tag("pussy sandwich", "pussy sandwich"),
        Tag("rape", "rape"),
        Tag("real-world location", "real-world location"),
        Tag("rebellion", "rebellion"),
        Tag("recycled animation", "recycled animation"),
        Tag("red-light district", "red-light district"),
        Tag("reincarnation", "reincarnation"),
        Tag("religion", "religion"),
        Tag("remastered version available", "remastered version available"),
        Tag("restaurant", "restaurant"),
        Tag("revenge", "revenge"),
        Tag("reverse harem", "reverse harem"),
        Tag("reverse spitroast", "reverse spitroast"),
        Tag("reverse trap", "reverse trap"),
        Tag("rimming", "rimming"),
        Tag("rivalry", "rivalry"),
        Tag("robot", "robot"),
        Tag("romance", "romance"),
        Tag("rotten world", "rotten world"),
        Tag("RPG", "RPG"),
        Tag("rugby", "rugby"),
        Tag("running gag", "running gag"),
        Tag("safer sex", "safer sex"),
        Tag("sakura", "sakura"),
        Tag("samurai", "samurai"),
        Tag("scat", "scat"),
        Tag("school clubs", "school clubs"),
        Tag("school dormitory", "school dormitory"),
        Tag("school for the rich elite", "school for the rich elite"),
        Tag("school life", "school life"),
        Tag("science fiction", "science fiction"),
        Tag("scissoring", "scissoring"),
        Tag("season", "season"),
        Tag("Secret Anima", "Secret Anima"),
        Tag("Secret Anima Series", "Secret Anima Series"),
        Tag("seiyuu", "seiyuu"),
        Tag("self-parody", "self-parody"),
        Tag("setting", "setting"),
        Tag("sex", "sex"),
        Tag("sex change", "sex change"),
        Tag("sex doll", "sex doll"),
        Tag("sex tape", "sex tape"),
        Tag("sex toys", "sex toys"),
        Tag("sex while on the phone", "sex while on the phone"),
        Tag("sexual abuse", "sexual abuse"),
        Tag("sexual fantasies", "sexual fantasies"),
        Tag("shibari", "shibari"),
        Tag("Shinjuku", "Shinjuku"),
        Tag("shinsengumi", "shinsengumi"),
        Tag("shipboard", "shipboard"),
        Tag("short episodes", "short episodes"),
        Tag("short movie", "short movie"),
        Tag("short story collection", "short story collection"),
        Tag("shota", "shota"),
        Tag("shoujo ai", "shoujo ai"),
        Tag("shounen ai", "shounen ai"),
        Tag("sibling rivalry", "sibling rivalry"),
        Tag("sibling yin yang", "sibling yin yang"),
        Tag("sister-sister incest", "sister-sister incest"),
        Tag("sixty-nine", "sixty-nine"),
        Tag("skimpy clothing", "skimpy clothing"),
        Tag("slapstick", "slapstick"),
        Tag("slavery", "slavery"),
        Tag("sleeping sex", "sleeping sex"),
        Tag("slide show animation", "slide show animation"),
        Tag("slow when it comes to love", "slow when it comes to love"),
        Tag("slums", "slums"),
        Tag("small breasts", "small breasts"),
        Tag("soapland", "soapland"),
        Tag("social class issues", "social class issues"),
        Tag("social commentary", "social commentary"),
        Tag("softball", "softball"),
        Tag("some weird shit goin` on", "some weird shit goin` on"),
        Tag("South Korean production", "South Korean production"),
        Tag("space", "space"),
        Tag("space pirates", "space pirates"),
        Tag("space travel", "space travel"),
        Tag("spacing out", "spacing out"),
        Tag("spanking", "spanking"),
        Tag("special squads", "special squads"),
        Tag("speculative fiction", "speculative fiction"),
        Tag("spellcasting", "spellcasting"),
        Tag("spirit realm", "spirit realm"),
        Tag("spirits", "spirits"),
        Tag("spiritual powers", "spiritual powers"),
        Tag("spitroast", "spitroast"),
        Tag("sports", "sports"),
        Tag("spring", "spring"),
        Tag("squirting", "squirting"),
        Tag("stand-alone movie", "stand-alone movie"),
        Tag("stereotypes", "stereotypes"),
        Tag("stomach bulge", "stomach bulge"),
        Tag("stomach stretch", "stomach stretch"),
        Tag("storytelling", "storytelling"),
        Tag("strapon", "strapon"),
        Tag("strappado", "strappado"),
        Tag("strappado bondage", "strappado bondage"),
        Tag("strong female lead", "strong female lead"),
        Tag("strong male lead", "strong male lead"),
        Tag("student government", "student government"),
        Tag("submission", "submission"),
        Tag("succubus", "succubus"),
        Tag("sudden girlfriend appearance", "sudden girlfriend appearance"),
        Tag("sudden naked girl appearance", "sudden naked girl appearance"),
        Tag("suicide", "suicide"),
        Tag("sumata", "sumata"),
        Tag("summer", "summer"),
        Tag("summoning", "summoning"),
        Tag("super deformed", "super deformed"),
        Tag("super power", "super power"),
        Tag("superhero", "superhero"),
        Tag("surreal", "surreal"),
        Tag("survival", "survival"),
        Tag("suspension bondage", "suspension bondage"),
        Tag("swimming", "swimming"),
        Tag("swordplay", "swordplay"),
        Tag("table tennis", "table tennis"),
        Tag("tales", "tales"),
        Tag("tank warfare", "tank warfare"),
        Tag("teacher x student", "teacher x student"),
        Tag("technical aspects", "technical aspects"),
        Tag("tennis", "tennis"),
        Tag("tentacle", "tentacle"),
        Tag("the arts", "the arts"),
        Tag("the power of love", "the power of love"),
        Tag("themes", "themes"),
        Tag("thievery", "thievery"),
        Tag("thigh sex", "thigh sex"),
        Tag("Three Kingdoms", "Three Kingdoms"),
        Tag("threesome", "threesome"),
        Tag("threesome with sisters", "threesome with sisters"),
        Tag("thriller", "thriller"),
        Tag("throat fucking", "throat fucking"),
        Tag("time", "time"),
        Tag("time loop", "time loop"),
        Tag("time travel", "time travel"),
        Tag("Tokugawa period", "Tokugawa period"),
        Tag("Tokyo", "Tokyo"),
        Tag("torture", "torture"),
        Tag("tournament", "tournament"),
        Tag("track and field", "track and field"),
        Tag("tragedy", "tragedy"),
        Tag("tragic beginning", "tragic beginning"),
        Tag("training", "training"),
        Tag("transforming craft", "transforming craft"),
        Tag("transforming weapons", "transforming weapons"),
        Tag("trap", "trap"),
        Tag("trapped", "trapped"),
        Tag("triple penetration", "triple penetration"),
        Tag("tropes", "tropes"),
        Tag("tsunami", "tsunami"),
        Tag("TV censoring", "TV censoring"),
        Tag("twincest", "twincest"),
        Tag("ukiyo-e", "ukiyo-e"),
        Tag("uncle-niece incest", "uncle-niece incest"),
        Tag("undead", "undead"),
        Tag("under one roof", "under one roof"),
        Tag("unexpected inheritance", "unexpected inheritance"),
        Tag("uniform fetish", "uniform fetish"),
        Tag("unintentional comedy", "unintentional comedy"),
        Tag("United States", "United States"),
        Tag("university", "university"),
        Tag("unrequited love", "unrequited love"),
        Tag("unrequited shounen ai", "unrequited shounen ai"),
        Tag("unsorted", "unsorted"),
        Tag("urethra penetration", "urethra penetration"),
        Tag("urination", "urination"),
        Tag("urophagia", "urophagia"),
        Tag("vampire", "vampire"),
        Tag("Vanilla Series", "Vanilla Series"),
        Tag("video game development", "video game development"),
        Tag("violence", "violence"),
        Tag("violent retribution for accidental infringement", "violent retribution for accidental infringement"),
        Tag("virtual world", "virtual world"),
        Tag("visible aura", "visible aura"),
        Tag("visual novel", "visual novel"),
        Tag("volleyball", "volleyball"),
        Tag("voyeurism", "voyeurism"),
        Tag("waitress", "waitress"),
        Tag("wakamezake", "wakamezake"),
        Tag("wardrobe malfunction", "wardrobe malfunction"),
        Tag("water sex", "water sex"),
        Tag("watercolour style", "watercolour style"),
        Tag("wax play", "wax play"),
        Tag("Weekly Shounen Jump", "Weekly Shounen Jump"),
        Tag("whip", "whip"),
        Tag("whipping", "whipping"),
        Tag("window fuck", "window fuck"),
        Tag("winter", "winter"),
        Tag("wooden horse", "wooden horse"),
        Tag("working life", "working life"),
        Tag("world domination", "world domination"),
        Tag("World War II", "World War II"),
        Tag("wrestling", "wrestling"),
        Tag("yaoi", "yaoi"),
        Tag("Yokohama", "Yokohama"),
        Tag("youji play", "youji play"),
        Tag("yuri", "yuri"),
        Tag("zero to hero", "zero to hero"),
        Tag("zombie", "zombie"),
    )
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(sortableList.map { it.first }.toTypedArray()),
        TagList(getTags()),
    )
    data class SearchParameters(
        val includedTags: ArrayList<String>,
        val blackListedTags: ArrayList<String>,
        val orderBy: String,
        val ordering: String
    )
    internal class Brand(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private val sortableList = listOf(
        Pair("Alphabetical", "az-"),
        Pair("Released", "rel-"),
        Pair("Added", "add-"),
        Pair("Bookmarked", "bkm-"),
        Pair("Rated", "rtg-"),
        Pair("Popular", "vtt-"),
        Pair("Popular Today", "vdy-"),
        Pair("Popular This Week", "vwk-"),
        Pair("Popular This Month", "vmt-"),
        Pair("Popular This Year", "vyr-"),
    )
    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Sort", sortables, Selection(0, true))

    private fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        val includedTags = ArrayList<String>()
        val blackListedTags = ArrayList<String>()
        var orderBy = "az-"
        var ordering = "a"
        filters.forEach { filter ->
            when (filter) {
                is TagList -> {
                    filter.state.forEach { tag ->
                        if (tag.isIncluded()) {
                            includedTags.add(
                                "\"" + tag.id.toLowerCase(
                                    Locale.US
                                ) + "\""
                            )
                        } else if (tag.isExcluded()) {
                            blackListedTags.add(
                                "\"" + tag.id.toLowerCase(
                                    Locale.US
                                ) + "\""
                            )
                        }
                    }
                }
                is SortFilter -> {
                    if (filter.state != null) {
                        val query = sortableList[filter.state!!.index].second
                        val value = when (filter.state!!.ascending) {
                            true -> "a"
                            false -> "d"
                        }
                        ordering = value
                        orderBy = query
                    }
                }
                else -> { }
            }
        }
        return SearchParameters(includedTags, blackListedTags, orderBy, ordering)
    }
}
