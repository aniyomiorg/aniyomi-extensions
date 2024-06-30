package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Response

val SharedPreferences.username
    get() = getString(Jellyfin.USERNAME_KEY, Jellyfin.USERNAME_DEFAULT)!!

val SharedPreferences.password
    get() = getString(Jellyfin.PASSWORD_KEY, Jellyfin.PASSWORD_DEFAULT)!!

val SharedPreferences.apiKey
    get() = getString(Jellyfin.APIKEY_KEY, "")!!

fun SharedPreferences.clearApiKey() {
    edit().remove(Jellyfin.APIKEY_KEY).apply()
}

fun Long.formatBytes(): String = when {
    this >= 1_000_000_000 -> "%.2f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000 -> "%.2f MB".format(this / 1_000_000.0)
    this >= 1_000 -> "%.2f KB".format(this / 1_000.0)
    this > 1 -> "$this bytes"
    this == 1L -> "$this byte"
    else -> ""
}

object PascalCaseToCamelCase : JsonNamingStrategy {
    override fun serialNameForJson(
        descriptor: SerialDescriptor,
        elementIndex: Int,
        serialName: String,
    ): String {
        return serialName.replaceFirstChar { it.uppercase() }
    }
}

val JSON = Json {
    ignoreUnknownKeys = true
    namingStrategy = PascalCaseToCamelCase
}

inline fun <reified T> Response.parseAs(): T {
    return JSON.decodeFromString(body.string())
}

fun PreferenceScreen.addEditTextPreference(
    title: String,
    default: String,
    summary: String,
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: String? = null,
    key: String = title,
    restartRequired: Boolean = false,
    onComplete: () -> Unit = {},
) {
    EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.setDefaultValue(default)
        dialogTitle = title
        this.dialogMessage = dialogMessage

        setOnBindEditTextListener { editText ->
            if (inputType != null) {
                editText.inputType = inputType
            }

            if (validate != null) {
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                        override fun afterTextChanged(editable: Editable?) {
                            requireNotNull(editable)

                            val text = editable.toString()
                            val isValid = text.isBlank() || validate(text)

                            editText.error = if (!isValid) validationMessage else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)
                                ?.isEnabled = editText.error == null
                        }
                    },
                )
            }
        }

        setOnPreferenceChangeListener { _, newValue ->
            try {
                val text = newValue as String
                val result = text.isBlank() || validate?.invoke(text) ?: true

                if (restartRequired && result) {
                    Toast.makeText(context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                }

                onComplete()

                result
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }.also(::addPreference)
}

object Constants {
    val QUALITIES_LIST = arrayOf(
        Quality(3840, 2160, 120000000, 192000, "4K - 120 Mbps"),
        Quality(3840, 2160, 80000000, 192000, "4K - 80 Mbps"),
        Quality(1920, 1080, 59808000, 192000, "1080p - 60 Mbps"),
        Quality(1920, 1080, 39808000, 192000, "1080p - 40 Mbps"),
        Quality(1920, 1080, 19808000, 192000, "1080p - 20 Mbps"),
        Quality(1920, 1080, 14808000, 192000, "1080p - 15 Mbps"),
        Quality(1920, 1080, 9808000, 192000, "1080p - 10 Mbps"),
        Quality(1280, 720, 7808000, 192000, "720p - 8 Mbps"),
        Quality(1280, 720, 5808000, 192000, "720p - 6 Mbps"),
        Quality(1280, 720, 3808000, 192000, "720p - 4 Mbps"),
        Quality(854, 480, 2808000, 192000, "480p - 3 Mbps"),
        Quality(854, 480, 1308000, 192000, "480p - 1.5 Mbps"),
        Quality(854, 480, 528000, 192000, "480p - 720 kbps"),
        Quality(480, 360, 292000, 128000, "360p - 420 kbps"),
    )

    class Quality(
        val width: Int,
        val height: Int,
        val videoBitrate: Int,
        val audioBitrate: Int,
        val description: String,
    )

    val LANG_VALUES = arrayOf(
        "aar", "abk", "ace", "ach", "ada", "ady", "afh", "afr", "ain", "aka", "akk", "ale", "alt", "amh", "ang", "anp", "apa",
        "ara", "arc", "arg", "arn", "arp", "arw", "asm", "ast", "ath", "ava", "ave", "awa", "aym", "aze", "bai", "bak", "bal",
        "bam", "ban", "bas", "bej", "bel", "bem", "ben", "ber", "bho", "bik", "bin", "bis", "bla", "bod", "bos", "bra", "bre",
        "bua", "bug", "bul", "byn", "cad", "car", "cat", "ceb", "ces", "cha", "chb", "che", "chg", "chk", "chm", "chn", "cho",
        "chp", "chr", "chu", "chv", "chy", "cnr", "cop", "cor", "cos", "cre", "crh", "csb", "cym", "dak", "dan", "dar", "del",
        "den", "deu", "dgr", "din", "div", "doi", "dsb", "dua", "dum", "dyu", "dzo", "efi", "egy", "eka", "ell", "elx", "eng",
        "enm", "epo", "est", "eus", "ewe", "ewo", "fan", "fao", "fas", "fat", "fij", "fil", "fin", "fiu", "fon", "fra", "frm",
        "fro", "frr", "frs", "fry", "ful", "fur", "gaa", "gay", "gba", "gez", "gil", "gla", "gle", "glg", "glv", "gmh", "goh",
        "gon", "gor", "got", "grb", "grc", "grn", "gsw", "guj", "gwi", "hai", "hat", "hau", "haw", "heb", "her", "hil", "hin",
        "hit", "hmn", "hmo", "hrv", "hsb", "hun", "hup", "hye", "iba", "ibo", "ido", "iii", "ijo", "iku", "ile", "ilo", "ina",
        "inc", "ind", "inh", "ipk", "isl", "ita", "jav", "jbo", "jpn", "jpr", "jrb", "kaa", "kab", "kac", "kal", "kam", "kan",
        "kar", "kas", "kat", "kau", "kaw", "kaz", "kbd", "kha", "khm", "kho", "kik", "kin", "kir", "kmb", "kok", "kom", "kon",
        "kor", "kos", "kpe", "krc", "krl", "kru", "kua", "kum", "kur", "kut", "lad", "lah", "lam", "lao", "lat", "lav", "lez",
        "lim", "lin", "lit", "lol", "loz", "ltz", "lua", "lub", "lug", "lui", "lun", "luo", "lus", "mad", "mag", "mah", "mai",
        "mak", "mal", "man", "mar", "mas", "mdf", "mdr", "men", "mga", "mic", "min", "mkd", "mkh", "mlg", "mlt", "mnc", "mni",
        "moh", "mon", "mos", "mri", "msa", "mus", "mwl", "mwr", "mya", "myv", "nah", "nap", "nau", "nav", "nbl", "nde", "ndo",
        "nds", "nep", "new", "nia", "nic", "niu", "nld", "nno", "nob", "nog", "non", "nor", "nqo", "nso", "nub", "nwc", "nya",
        "nym", "nyn", "nyo", "nzi", "oci", "oji", "ori", "orm", "osa", "oss", "ota", "oto", "pag", "pal", "pam", "pan", "pap",
        "pau", "peo", "phn", "pli", "pol", "pon", "por", "pro", "pus", "que", "raj", "rap", "rar", "roh", "rom", "ron", "run",
        "rup", "rus", "sad", "sag", "sah", "sam", "san", "sas", "sat", "scn", "sco", "sel", "sga", "shn", "sid", "sin", "slk",
        "slv", "sma", "sme", "smj", "smn", "smo", "sms", "sna", "snd", "snk", "sog", "som", "son", "sot", "spa", "sqi", "srd",
        "srn", "srp", "srr", "ssw", "suk", "sun", "sus", "sux", "swa", "swe", "syc", "syr", "tah", "tai", "tam", "tat", "tel",
        "tem", "ter", "tet", "tgk", "tgl", "tha", "tig", "tir", "tiv", "tkl", "tlh", "tli", "tmh", "tog", "ton", "tpi", "tsi",
        "tsn", "tso", "tuk", "tum", "tup", "tur", "tvl", "twi", "tyv", "udm", "uga", "uig", "ukr", "umb", "urd", "uzb", "vai",
        "ven", "vie", "vol", "vot", "wal", "war", "was", "wen", "wln", "wol", "xal", "xho", "yao", "yap", "yid", "yor", "zap",
        "zbl", "zen", "zgh", "zha", "zho", "zul", "zun", "zza",
    )

    val LANG_ENTRIES = arrayOf(
        "Qafaraf; ’Afar Af; Afaraf; Qafar af", "Аҧсуа бызшәа Aƥsua bızšwa; Аҧсшәа Aƥsua", "بهسا اچيه", "Lwo", "Dangme",
        "Адыгабзэ; Кӏахыбзэ", "El-Afrihili", "Afrikaans", "アイヌ・イタㇰ Ainu-itak", "Akan", "𒀝𒅗𒁺𒌑", "Уна́ӈам тунуу́; Унаӈан умсуу",
        "Алтай тили", "አማርኛ Amârıñâ", "Ænglisc; Anglisc; Englisc", "Angika", "Apache languages", "العَرَبِيَّة al'Arabiyyeẗ",
        "Official Aramaic (700–300 BCE); Imperial Aramaic (700–300 BCE)", "aragonés", "Mapudungun; Mapuche", "Hinónoʼeitíít",
        "Lokono", "অসমীয়া", "Asturianu; Llïonés", "Athapascan languages", "Магӏарул мацӏ; Авар мацӏ", "Avestan", "अवधी",
        "Aymar aru", "Azərbaycan dili; آذربایجان دیلی; Азәрбајҹан дили", "Bamiléké", "Башҡорт теле; Başqort tele",
        "بلوچی", "ߓߊߡߊߣߊߣߞߊߣ", "ᬪᬵᬱᬩᬮᬶ; ᬩᬲᬩᬮᬶ; Basa Bali", "Mbene; Ɓasaá", "Bidhaawyeet", "Беларуская мова Belaruskaâ mova",
        "Chibemba", "বাংলা Bāŋlā", "Tamaziɣt; Tamazight; ⵜⴰⵎⴰⵣⵉⵖⵜ; ⵝⴰⵎⴰⵣⵉⵗⵝ; ⵜⴰⵎⴰⵣⵉⵗⵜ", "भोजपुरी", "Bikol", "Ẹ̀dó",
        "Bislama", "ᓱᖽᐧᖿ", "བོད་སྐད་ Bodskad; ལྷ་སའི་སྐད་ Lhas'iskad", "bosanski", "Braj", "Brezhoneg", "буряад хэлэн",
        "ᨅᨔ ᨕᨘᨁᨗ", "български език bălgarski ezik", "ብሊና; ብሊን", "Hasí:nay", "Kari'nja", "català,valencià", "Sinugbuanong Binisayâ",
        "čeština; český jazyk", "Finu' Chamoru", "Muysccubun", "Нохчийн мотт; نَاخچیین موٓتت; ნახჩიე მუოთთ", "جغتای",
        "Chuukese", "марий йылме", "chinuk wawa; wawa; chinook lelang; lelang", "Chahta'", "ᑌᓀᓱᒼᕄᓀ (Dënesųłiné)",
        "ᏣᎳᎩ ᎦᏬᏂᎯᏍᏗ Tsalagi gawonihisdi", "Славе́нскїй ѧ҆зы́къ", "Чӑвашла", "Tsėhésenėstsestȯtse", "crnogorski / црногорски",
        "ϯⲙⲉⲑⲣⲉⲙⲛ̀ⲭⲏⲙⲓ; ⲧⲙⲛ̄ⲧⲣⲙ̄ⲛ̄ⲕⲏⲙⲉ", "Kernowek", "Corsu; Lingua corsa", "Cree", "Къырымтатарджа; Къырымтатар тили; Ҡырымтатарҗа; Ҡырымтатар тили",
        "Kaszëbsczi jãzëk", "Cymraeg; y Gymraeg", "Dakhótiyapi; Dakȟótiyapi", "dansk", "дарган мез", "Delaware", "Dene K'e",
        "Deutsch", "Dogrib", "Thuɔŋjäŋ", "ދިވެހި; ދިވެހިބަސް Divehi", "𑠖𑠵𑠌𑠤𑠮; डोगरी; ڈوگرى", "Dolnoserbski; Dolnoserbšćina",
        "Duala", "Dutch, Middle (ca. 1050–1350)", "Julakan", "རྫོང་ཁ་ Ĵoŋkha", "Efik", "Egyptian (Ancient)", "Ekajuk",
        "Νέα Ελληνικά Néa Ellêniká", "Elamite", "English", "English, Middle (1100–1500)", "Esperanto", "eesti keel",
        "euskara", "Èʋegbe", "Ewondo", "Fang", "føroyskt", "فارسی Fārsiy", "Mfantse; Fante; Fanti", "Na Vosa Vakaviti",
        "Wikang Filipino", "suomen kieli", "Finno-Ugrian languages", "Fon gbè", "français", "françois; franceis", "Franceis; François; Romanz",
        "Frasch; Fresk; Freesk; Friisk", "Oostfreesk; Plattdüütsk", "Frysk", "Fulfulde; Pulaar; Pular", "Furlan",
        "Gã", "Basa Gayo", "Gbaya", "ግዕዝ", "Taetae ni Kiribati", "Gàidhlig", "Gaeilge", "galego", "Gaelg; Gailck", "Diutsch",
        "Diutisk", "Gondi", "Bahasa Hulontalo", "Gothic", "Grebo", "Ἑλληνική", "Avañe'ẽ", "Schwiizerdütsch", "ગુજરાતી Gujarātī",
        "Dinjii Zhu’ Ginjik", "X̱aat Kíl; X̱aadas Kíl; X̱aayda Kil; Xaad kil", "kreyòl ayisyen", "Harshen Hausa; هَرْشَن",
        "ʻŌlelo Hawaiʻi", "עברית 'Ivriyþ", "Otjiherero", "Ilonggo", "हिन्दी Hindī", "𒉈𒅆𒇷", "lus Hmoob; lug Moob; lol Hmongb; 𖬇𖬰𖬞 𖬌𖬣𖬵",
        "Hiri Motu", "hrvatski", "hornjoserbšćina", "magyar nyelv", "Na:tinixwe Mixine:whe'", "Հայերէն Hayerèn; Հայերեն Hayeren",
        "Jaku Iban", "Asụsụ Igbo", "Ido", "ꆈꌠꉙ Nuosuhxop", "Ịjọ", "ᐃᓄᒃᑎᑐᑦ Inuktitut", "Interlingue; Occidental", "Pagsasao nga Ilokano; Ilokano",
        "Interlingua (International Auxiliary Language Association)", "Indo-Aryan languages", "bahasa Indonesia",
        "ГӀалгӀай мотт", "Iñupiaq", "íslenska", "italiano; lingua italiana", "ꦧꦱꦗꦮ / Basa Jawa", "la .lojban.", "日本語 Nihongo",
        "Dzhidi", "عربية يهودية / ערבית יהודית", "Qaraqalpaq tili; Қарақалпақ тили", "Tamaziɣt Taqbaylit; Tazwawt",
        "Jingpho", "Kalaallisut; Greenlandic", "Kamba", "ಕನ್ನಡ Kannađa", "Karen languages", "कॉशुर / كأشُر", "ქართული Kharthuli",
        "Kanuri", "ꦧꦱꦗꦮ", "қазақ тілі qazaq tili; қазақша qazaqşa", "Адыгэбзэ (Къэбэрдейбзэ) Adıgăbză (Qăbărdeĭbză)",
        "কা কতিয়েন খাশি", "ភាសាខ្មែរ Phiəsaakhmær", "Khotanese; Sakan", "Gĩkũyũ", "Ikinyarwanda", "кыргызча kırgızça; кыргыз тили kırgız tili",
        "Kimbundu", "कोंकणी", "Коми кыв", "Kongo", "한국어 Han'gug'ô", "Kosraean", "Kpɛlɛwoo", "Къарачай-Малкъар тил; Таулу тил",
        "karjal; kariela; karjala", "कुड़ुख़", "Kuanyama; Kwanyama", "къумукъ тил/qumuq til", "kurdî / کوردی", "Kutenai",
        "Judeo-español", "بھارت کا", "Lamba", "ພາສາລາວ Phasalaw", "Lingua latīna", "Latviešu valoda", "Лезги чӏал",
        "Lèmburgs", "Lingala", "lietuvių kalba", "Lomongo", "Lozi", "Lëtzebuergesch", "Cilubà / Tshiluba", "Kiluba",
        "Luganda", "Cham'teela", "Chilunda", "Dholuo", "Mizo ṭawng", "Madhura", "मगही", "Kajin M̧ajeļ", "मैथिली; মৈথিলী",
        "Basa Mangkasara' / ᨅᨔ ᨆᨀᨔᨑ", "മലയാളം Malayāļã", "Mandi'nka kango", "मराठी Marāţhī", "ɔl", "мокшень кяль",
        "Mandar", "Mɛnde yia", "Gaoidhealg", "Míkmawísimk", "Baso Minang", "македонски јазик makedonski jazik", "Mon-Khmer languages",
        "Malagasy", "Malti", "ᠮᠠᠨᠵᡠ ᡤᡳᠰᡠᠨ Manju gisun", "Manipuri", "Kanien’kéha", "монгол хэл mongol xel; ᠮᠣᠩᠭᠣᠯ ᠬᠡᠯᠡ",
        "Mooré", "Te Reo Māori", "Bahasa Melayu", "Mvskoke", "mirandés; lhéngua mirandesa", "मारवाड़ी", "မြန်မာစာ Mrãmācā; မြန်မာစကား Mrãmākā:",
        "эрзянь кель", "Nahuatl languages", "napulitano", "dorerin Naoero", "Diné bizaad; Naabeehó bizaad", "isiNdebele seSewula",
        "siNdebele saseNyakatho", "ndonga", "Plattdütsch; Plattdüütsch", "नेपाली भाषा Nepālī bhāśā", "नेपाल भाषा; नेवाः भाय्",
        "Li Niha", "Niger-Kordofanian languages", "ko e vagahau Niuē", "Nederlands; Vlaams", "norsk nynorsk", "norsk bokmål",
        "Ногай тили", "Dǫnsk tunga; Norrœnt mál", "norsk", "N'Ko", "Sesotho sa Leboa", "لغات نوبية", "पुलां भाय्; पुलाङु नेपाल भाय्",
        "Chichewa; Chinyanja", "Nyamwezi", "Nyankole", "Runyoro", "Nzima", "occitan; lenga d'òc", "Ojibwa", "ଓଡ଼ିଆ",
        "Afaan Oromoo", "Wazhazhe ie / 𐓏𐓘𐓻𐓘𐓻𐓟 𐒻𐓟", "Ирон ӕвзаг Iron ævzag", "لسان عثمانى / lisân-ı Osmânî", "Otomian languages",
        "Salitan Pangasinan", "Pārsīk; Pārsīg", "Amánung Kapampangan; Amánung Sísuan", "ਪੰਜਾਬੀ / پنجابی Pãjābī",
        "Papiamentu", "a tekoi er a Belau", "Persian, Old (ca. 600–400 B.C.)", "𐤃𐤁𐤓𐤉𐤌 𐤊𐤍𐤏𐤍𐤉𐤌 Dabariym Kana'aniym",
        "Pāli", "Język polski", "Pohnpeian", "português", "Provençal, Old (to 1500); Old Occitan (to 1500)", "پښتو Pax̌tow",
        "Runa simi; kichwa simi; Nuna shimi", "राजस्थानी", "Vananga rapa nui", "Māori Kūki 'Āirani", "Rumantsch; Rumàntsch; Romauntsch; Romontsch",
        "romani čhib", "limba română", "Ikirundi", "armãneashce; armãneashti; rrãmãneshti", "русский язык russkiĭ âzık",
        "Sandaweeki", "yângâ tî sängö", "Сахалыы", "ארמית", "संस्कृतम् Sąskŕtam; 𑌸𑌂𑌸𑍍𑌕𑍃𑌤𑌮𑍍", "Sasak", "ᱥᱟᱱᱛᱟᱲᱤ", "Sicilianu",
        "Braid Scots; Lallans", "Selkup", "Goídelc", "ၵႂၢမ်းတႆးယႂ်", "Sidaamu Afoo", "සිංහල Sĩhala", "slovenčina; slovenský jazyk",
        "slovenski jezik; slovenščina", "Åarjelsaemien gïele", "davvisámegiella", "julevsámegiella", "anarâškielâ",
        "Gagana faʻa Sāmoa", "sääʹmǩiõll", "chiShona", "سنڌي / सिन्धी / ਸਿੰਧੀ", "Sooninkanxanne", "Sogdian", "af Soomaali",
        "Songhai languages", "Sesotho [southern]", "español; castellano", "Shqip", "sardu; limba sarda; lingua sarda",
        "Sranan Tongo", "српски / srpski", "Seereer", "siSwati", "Kɪsukuma", "ᮘᮞ ᮞᮥᮔ᮪ᮓ / Basa Sunda", "Sosoxui", "𒅴𒂠",
        "Kiswahili", "svenska", "Classical Syriac", "ܠܫܢܐ ܣܘܪܝܝܐ Lešānā Suryāyā", "Reo Tahiti; Reo Mā'ohi", "ภาษาไท; ภาษาไต",
        "தமிழ் Tamił", "татар теле / tatar tele / تاتار", "తెలుగు Telugu", "KʌThemnɛ", "Terêna", "Lia-Tetun", "тоҷикӣ toçikī",
        "Wikang Tagalog", "ภาษาไทย Phasathay", "ትግረ; ትግሬ; ኻሳ; ትግራይት", "ትግርኛ", "Tiv", "Tokelau", "Klingon; tlhIngan-Hol",
        "Lingít", "Tamashek", "chiTonga", "lea faka-Tonga", "Tok Pisin", "Tsimshian", "Setswana", "Xitsonga", "Türkmençe / Түркменче / تورکمن تیلی تورکمنچ; türkmen dili / түркмен дили",
        "chiTumbuka", "Tupi languages", "Türkçe", "Te Ggana Tuuvalu; Te Gagana Tuuvalu", "Twi", "тыва дыл", "удмурт кыл",
        "Ugaritic", "ئۇيغۇرچە  ; ئۇيغۇر تىلى", "Українська мова; Українська", "Úmbúndú", "اُردُو Urduw", "Oʻzbekcha / Ózbekça / ўзбекча / ئوزبېچه; oʻzbek tili / ўзбек тили / ئوبېک تیلی",
        "ꕙꔤ", "Tshivenḓa", "Tiếng Việt", "Volapük", "vađđa ceeli", "Wolaitta; Wolaytta", "Winaray; Samareño; Lineyte-Samarnon; Binisayâ nga Winaray; Binisayâ nga Samar-Leyte; “Binisayâ nga Waray”",
        "wá:šiw ʔítlu", "Serbsce / Serbski", "Walon", "Wolof", "Хальмг келн / Xaľmg keln", "isiXhosa", "Yao", "Yapese",
        "ייִדיש; יידיש; אידיש Yidiš", "èdè Yorùbá", "Diidxazá/Dizhsa", "Blissymbols; Blissymbolics; Bliss", "Tuḍḍungiyya",
        "ⵜⴰⵎⴰⵣⵉⵖⵜ ⵜⴰⵏⴰⵡⴰⵢⵜ", "Vahcuengh / 話僮", "中文 Zhōngwén; 汉语; 漢語 Hànyǔ", "isiZulu", "Shiwi'ma", "kirmanckî; dimilkî; kirdkî; zazakî",
    )
}
