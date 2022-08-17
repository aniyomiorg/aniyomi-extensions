package eu.kanade.tachiyomi.animeextension.en.superstream

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import okhttp3.ConnectionSpec
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.Base64 as JavaBase64

const val TYPE_SERIES = 2
const val TYPE_MOVIES = 1

// Ported from CS3
class SuperStreamAPI(originalClient: OkHttpClient) {

    private val client = configureToIgnoreCertificate(
        originalClient.newBuilder()
            .connectionSpecs(
                arrayListOf( ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT)
            )
    ).build()

    // 0 to get nsfw
    private val hideNsfw = 1

    private val headers = Headers.headersOf(
        "Platform", "android",
        "Accept", "charset=utf-8"
    )

    // Random 32 length string
    private fun randomToken(): String {
        return (0..31).joinToString("") {
            (('0'..'9') + ('a'..'f')).random().toString()
        }
    }

    private val token = randomToken()

    private object CipherUtils {
        private const val ALGORITHM = "DESede"
        private const val TRANSFORMATION = "DESede/CBC/PKCS5Padding"
        fun encrypt(str: String, key: String, iv: String): String? {
            return try {
                val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
                val bArr = ByteArray(24)
                val bytes: ByteArray = key.toByteArray()
                var length = if (bytes.size <= 24) bytes.size else 24
                System.arraycopy(bytes, 0, bArr, 0, length)
                while (length < 24) {
                    bArr[length] = 0
                    length++
                }
                cipher.init(
                    1,
                    SecretKeySpec(bArr, ALGORITHM),
                    IvParameterSpec(iv.toByteArray())
                )

                String(Base64.encode(cipher.doFinal(str.toByteArray()), 2), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun md5(str: String): String? {
            return MD5Util.md5(str)?.let { HexDump.toHexString(it).lowercase() }
        }

        fun getVerify(str: String?, str2: String, str3: String): String? {
            if (str != null) {
                return md5(md5(str2) + str3 + str)
            }
            return null
        }
    }

    private object HexDump {
        private val HEX_DIGITS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
        )

        @JvmOverloads
        fun toHexString(bArr: ByteArray, i: Int = 0, i2: Int = bArr.size): String {
            val cArr = CharArray(i2 * 2)
            var i3 = 0
            for (i4 in i until i + i2) {
                val b = bArr[i4].toInt()
                val i5 = i3 + 1
                val cArr2 = HEX_DIGITS
                cArr[i3] = cArr2[b ushr 4 and 15]
                i3 = i5 + 1
                cArr[i5] = cArr2[b and 15]
            }
            return String(cArr)
        }
    }

    private object MD5Util {
        fun md5(str: String): ByteArray? {
            return this.md5(str.toByteArray())
        }

        fun md5(bArr: ByteArray?): ByteArray? {
            return try {
                val digest = MessageDigest.getInstance("MD5")
                digest.update(bArr ?: return null)
                digest.digest()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun queryApi(query: String): Response {
        val encryptedQuery = CipherUtils.encrypt(query, key, iv)!!
        val appKeyHash = CipherUtils.md5(appKey)!!
        val newBody =
            """{"app_key":"$appKeyHash","verify":"${
            CipherUtils.getVerify(
                encryptedQuery,
                appKey,
                key
            )
            }","encrypt_data":"$encryptedQuery"}"""
        val base64Body = String(Base64.encode(newBody.toByteArray(), Base64.DEFAULT))

        val formData: RequestBody = FormBody.Builder()
            .add("data", base64Body)
            .add("appid", "27")
            .add("platform", "android")
            .add("version", "129")
            // Probably best to randomize this
            .add("medium", "Website&token$token")
            .build()
        val req = POST(apiUrl, headers = headers, body = formData)
        try {
            val copy: Request = req.newBuilder().build()
            val buffer = okio.Buffer()
            copy.body!!.writeTo(buffer)
            Log.i("manualoutPut", buffer.readUtf8() + "\n$req")
        } catch (e: IOException) {
            Log.i("manualoutPut", "did not work")
        }

        try {
            return client.newCall(req).execute()
        } catch (e: Exception) {
            Log.i("manualoutPut", "Erroring OUT")
            throw Exception("Query Failed $e")
        }
    }

    private inline fun <reified T : Any> queryApiParsed(query: String): T {
        return parseJson(queryApi(query).body!!.toString())
    }

    private val unixTime: Long
        get() = System.currentTimeMillis() / 1000L

    private fun getExpiryDate(): Long {
        // Current time + 12 hours
        return unixTime + 60 * 60 * 12
    }

    // We do not want content scanners to notice this scraping going on so we've hidden all constants
    // The source has its origins in China so I added some extra security with banned words
    // Mayhaps a tiny bit unethical, but this source is just too good :)
    // If you are copying this code please use precautions so they do not change their api.

    // Free Tibet, The Tienanmen Square protests of 1989
    private val iv = base64Decode("d0VpcGhUbiE=")
    private val key = base64Decode("MTIzZDZjZWRmNjI2ZHk1NDIzM2FhMXc2")
    private val ip = base64Decode("aHR0cHM6Ly8xNTIuMzIuMTQ5LjE2MA==")
    val apiUrl = "$ip${base64Decode("L2FwaS9hcGlfY2xpZW50L2luZGV4Lw==")}"
    private val appKey = base64Decode("bW92aWVib3g=")
    private val appId = base64Decode("Y29tLnRkby5zaG93Ym94")

    fun getMainPage(page: Int): AnimesPage {
        val json = queryApi(
            """{"childmode":"$hideNsfw","app_version":"11.5","appid":"$appId","module":"Home_list_type_v2","channel":"Website","page":"$page","lang":"en","type":"all","pagelimit":"10","expired_date":"${getExpiryDate()}","platform":"android"}
            """.trimIndent()
        ).body!!.toString()
        val animes = mutableListOf<SAnime>()

        // Cut off the first row (featured)
        parseJson<DataJSON>(json).data.let { it.subList(minOf(it.size, 1), it.size) }
            .mapNotNull {
                it.list.mapNotNull second@{ post ->
                    animes.add(
                        SAnime.create().apply {
                            url = LoadData(post.id ?: return@mapNotNull null, post.boxType).toJson()
                            thumbnail_url = post.poster ?: post.poster2
                            title = post.title ?: return@second null
                        }
                    )
                }
            }
        return AnimesPage(animes, animes.isNotEmpty())
    }

    private fun Data.toSearchResponse(): SAnime? {
        val it = this
        return SAnime.create().apply {
            title = it.title ?: return null
            thumbnail_url = it.posterOrg ?: it.poster
            url = (
                it.id?.let { id -> LoadData(id, it.boxType ?: return@let null) }
                    ?: it.mid?.let { id ->
                        LoadData(
                            id,
                            TYPE_MOVIES
                        )
                    } ?: it.tid?.let { id -> LoadData(id, TYPE_SERIES) }
                )?.toJson() ?: return null
        }
    }

    fun search(page: Int, query: String): List<SAnime> {

        val apiQuery =
            // Originally 8 pagelimit
            """{"childmode":"$hideNsfw","app_version":"11.5","appid":"$appId","module":"Search3","channel":"Website","page":"$page","lang":"en","type":"all","keyword":"$query","pagelimit":"20","expired_date":"${getExpiryDate()}","platform":"android"}"""
        val searchResponse = parseJson<MainData>(queryApi(apiQuery).body!!.string()).data.mapNotNull {
            it.toSearchResponse()
        }
        return searchResponse
    }

    fun load(url: String): Pair<MovieData?, Pair<SeriesData?, List<SeriesEpisode>?>> {
        val loadData = parseJson<LoadData>(url)
        // val module = if(type === "TvType.Movie") "Movie_detail" else "*tv series module*"

        val isMovie = loadData.type == TYPE_MOVIES

        if (isMovie) { // 1 = Movie
            val apiQuery =
                """{"childmode":"$hideNsfw","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_detail","channel":"Website","mid":"${loadData.id}","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","oss":"","group":""}"""
            val data = (queryApiParsed<MovieDataProp>(apiQuery)).data
                ?: throw RuntimeException("API error")

            return Pair(data, Pair(null, null))
        } else { // 2 Series
            val apiQuery =
                """{"childmode":"$hideNsfw","uid":"","app_version":"11.5","appid":"$appId","module":"TV_detail_1","display_all":"1","channel":"Website","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","tid":"${loadData.id}"}"""
            val data = (queryApiParsed<SeriesDataProp>(apiQuery)).data
                ?: throw RuntimeException("API error")

            val episodes = data.season.mapNotNull {
                val seasonQuery =
                    """{"childmode":"$hideNsfw","app_version":"11.5","year":"0","appid":"$appId","module":"TV_episode","display_all":"1","channel":"Website","season":"$it","lang":"en","expired_date":"${getExpiryDate()}","platform":"android","tid":"${loadData.id}"}"""
                (queryApiParsed<SeriesSeasonProp>(seasonQuery)).data
            }.flatten()

            return Pair(null, Pair(data, episodes))
        }
    }

    fun loadLinks(data: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val subsList = mutableListOf<Track>()

        val parsed = parseJson<LinkData>(data)

        // No childmode when getting links
        val query = if (parsed.type == TYPE_MOVIES) {
            """{"childmode":"0","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_downloadurl_v3","channel":"Website","mid":"${parsed.id}","lang":"","expired_date":"${getExpiryDate()}","platform":"android","oss":"1","group":""}"""
        } else {
            val episode = parsed.episode ?: throw RuntimeException("No episode number!")
            val season = parsed.season ?: throw RuntimeException("No season number!")
            """{"childmode":"0","app_version":"11.5","module":"TV_downloadurl_v3","channel":"Website","episode":"$episode","expired_date":"${getExpiryDate()}","platform":"android","tid":"${parsed.id}","oss":"1","uid":"","appid":"$appId","season":"$season","lang":"en","group":""}"""
        }

        val linkData = queryApiParsed<LinkDataProp>(query)

        // Should really run this query for every link :(
        val fid = linkData.data?.list?.firstOrNull { it.fid != null }?.fid

        val subtitleQuery = if (parsed.type == TYPE_MOVIES) {
            """{"childmode":"0","fid":"$fid","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_srt_list_v2","channel":"Website","mid":"${parsed.id}","lang":"en","expired_date":"${getExpiryDate()}","platform":"android"}"""
        } else {
            """{"childmode":"0","fid":"$fid","app_version":"11.5","module":"TV_srt_list_v2","channel":"Website","episode":"${parsed.episode}","expired_date":"${getExpiryDate()}","platform":"android","tid":"${parsed.id}","uid":"","appid":"$appId","season":"${parsed.season}","lang":"en"}"""
        }

        val subtitles = queryApiParsed<SubtitleDataProp>(subtitleQuery).data
        subtitles?.list?.forEach {
            it.subtitles.forEach second@{ sub ->
                if (sub.filePath.isNullOrBlank().not()) {
                    subsList.add(
                        Track(
                            sub.filePath ?: return listOf(),
                            sub.language ?: sub.lang ?: ""
                        )
                    )
                }
            }
        }

        linkData.data?.list?.forEach {
            if (it.path.isNullOrBlank().not()) {
                val videoUrl = it.path?.replace("\\/", "") ?: ""
                try {
                    videoList.add(
                        Video(
                            videoUrl,
                            it.quality ?: it.realQuality ?: "quality",
                            videoUrl,
                            subtitleTracks = subsList,
                            headers = headers
                        )
                    )
                } catch (e: Error) {
                    videoList.add(
                        Video(
                            videoUrl,
                            it.quality ?: it.realQuality ?: "quality",
                            videoUrl,
                            headers = headers
                        )
                    )
                }
            }
        }
        return videoList
    }

    private fun Any.toJson(): String {
        if (this is String) return this
        return mapper.writeValueAsString(this)
    }

    private fun base64Decode(string: String): String {
        return String(base64DecodeArray(string), Charsets.ISO_8859_1)
    }

    @SuppressLint("NewApi")
    private fun base64DecodeArray(string: String): ByteArray {
        return try {
            Base64.decode(string, Base64.DEFAULT)
        } catch (e: Exception) {
            JavaBase64.getDecoder().decode(string)
        }
    }

    val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    private inline fun <reified T> parseJson(value: String): T {
        return mapper.readValue(value)
    }
}

private fun configureToIgnoreCertificate(builder: OkHttpClient.Builder): OkHttpClient.Builder {
    try {

        // Create a trust manager that does not validate certificate chains
        val trustAllCerts: Array<TrustManager> = arrayOf(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {

                override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?): Unit =
                    throw CertificateException()

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
                    throw CertificateException()

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return arrayOf()
                }
            }
        )

        // Install the all-trusting trust manager
        val sslContext: SSLContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        // Create an ssl socket factory with our all-trusting manager
        val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
        builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
        builder.hostnameVerifier { _, _ -> true }
    } catch (e: Exception) {
        throw Exception("Exception while configuring IgnoreSslCertificate: $e")
    }
    return builder
}
