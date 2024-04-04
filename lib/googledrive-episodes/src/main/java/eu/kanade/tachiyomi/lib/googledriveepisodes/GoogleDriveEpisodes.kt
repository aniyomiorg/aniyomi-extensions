package eu.kanade.tachiyomi.lib.googledriveepisodes

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

class GoogleDriveEpisodes(private val client: OkHttpClient, private val headers: Headers) {
    // Lots of code borrowed from https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/googledrive.py under the `GoogleDriveFolderIE` class
    fun getEpisodesFromFolder(folderId: String, path: String, maxRecDepth: Int, trimNames: Boolean): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()

        fun traverseFolder(folderId: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecDepth) return

            val driveHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Connection", "keep-alive")
                .add("Cookie", getCookie("https://drive.google.com"))
                .add("Host", "drive.google.com")
                .build()

            val driveDocument = client.newCall(
                GET("https://drive.google.com/drive/folders/$folderId", headers = driveHeaders),
            ).execute().asJsoup()
            if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

            val keyScript = driveDocument.select("script").first { script ->
                KEY_REGEX.find(script.data()) != null
            }.data()
            val key = KEY_REGEX.find(keyScript)?.groupValues?.get(1) ?: ""

            val versionScript = driveDocument.select("script").first { script ->
                KEY_REGEX.find(script.data()) != null
            }.data()
            val driveVersion = VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""
            val sapisid = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
            }?.value ?: ""

            var pageToken: String? = ""
            while (pageToken != null) {
                val requestUrl = "/drive/v2internal/files?openDrive=false&reason=102&syncType=0&errorRecovery=false&q=trashed%20%3D%20false%20and%20'$folderId'%20in%20parents&fields=kind%2CnextPageToken%2Citems(kind%2CmodifiedDate%2ChasVisitorPermissions%2CcontainsUnsubscribedChildren%2CmodifiedByMeDate%2ClastViewedByMeDate%2CalternateLink%2CfileSize%2Cowners(kind%2CpermissionId%2CemailAddressFromAccount%2Cdomain%2Cid)%2ClastModifyingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CcustomerId%2CancestorHasAugmentedPermissions%2ChasThumbnail%2CthumbnailVersion%2Ctitle%2Cid%2CresourceKey%2CabuseIsAppealable%2CabuseNoticeReason%2Cshared%2CaccessRequestsCount%2CsharedWithMeDate%2CuserPermission(role)%2CexplicitlyTrashed%2CmimeType%2CquotaBytesUsed%2Ccopyable%2Csubscribed%2CfolderColor%2ChasChildFolders%2CfileExtension%2CprimarySyncParentId%2CsharingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CflaggedForAbuse%2CfolderFeatures%2Cspaces%2CsourceAppId%2Crecency%2CrecencyReason%2Cversion%2CactionItems%2CteamDriveId%2ChasAugmentedPermissions%2CcreatedDate%2CprimaryDomainName%2CorganizationDisplayName%2CpassivelySubscribed%2CtrashingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CtrashedDate%2Cparents(id)%2Ccapabilities(canMoveItemIntoTeamDrive%2CcanUntrash%2CcanMoveItemWithinTeamDrive%2CcanMoveItemOutOfTeamDrive%2CcanDeleteChildren%2CcanTrashChildren%2CcanRequestApproval%2CcanReadCategoryMetadata%2CcanEditCategoryMetadata%2CcanAddMyDriveParent%2CcanRemoveMyDriveParent%2CcanShareChildFiles%2CcanShareChildFolders%2CcanRead%2CcanMoveItemWithinDrive%2CcanMoveChildrenWithinDrive%2CcanAddFolderFromAnotherDrive%2CcanChangeSecurityUpdateEnabled%2CcanBlockOwner%2CcanReportSpamOrAbuse%2CcanCopy%2CcanDownload%2CcanEdit%2CcanAddChildren%2CcanDelete%2CcanRemoveChildren%2CcanShare%2CcanTrash%2CcanRename%2CcanReadTeamDrive%2CcanMoveTeamDriveItem)%2CcontentRestrictions(readOnly)%2CapprovalMetadata(approvalVersion%2CapprovalSummaries%2ChasIncomingApproval)%2CshortcutDetails(targetId%2CtargetMimeType%2CtargetLookupStatus%2CtargetFile%2CcanRequestAccessToTarget)%2CspamMetadata(markedAsSpamDate%2CinSpamView)%2Clabels(starred%2Ctrashed%2Crestricted%2Cviewed))%2CincompleteSearch&appDataFilter=NO_APP_DATA&spaces=drive&pageToken=$pageToken&maxResults=100&supportsTeamDrives=true&includeItemsFromAllDrives=true&corpora=default&orderBy=folder%2Ctitle_natural%20asc&retryCount=0&key=$key HTTP/1.1"
                val body = """--$BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$BOUNDARY--""".trimMargin("|").toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

                val postUrl = buildString {
                    append("https://clients6.google.com/batch/drive/v2internal")
                    append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
                    append("&key=$key")
                }

                val postHeaders = headers.newBuilder()
                    .add("Content-Type", "text/plain; charset=UTF-8")
                    .add("Origin", "https://drive.google.com")
                    .add("Cookie", getCookie("https://drive.google.com"))
                    .build()

                val response = client.newCall(
                    POST(postUrl, body = body, headers = postHeaders),
                ).execute()

                val parsed = response.parseAs<GDrivePostResponse> {
                    JSON_REGEX.find(it)!!.groupValues[1]
                }

                if (parsed.items == null) throw Exception("Failed to load items, please log in to google drive through webview")
                parsed.items.forEachIndexed { index, it ->
                    if (it.mimeType.startsWith("video")) {
                        val size = it.fileSize?.toLongOrNull()?.let { formatBytes(it) }
                        val pathName = path.trimInfo()

                        episodeList.add(
                            SEpisode.create().apply {
                                name = if (trimNames) it.title.trimInfo() else it.title
                                this.url = "https://drive.google.com/uc?id=${it.id}"
                                episode_number = ITEM_NUMBER_REGEX.find(it.title.trimInfo())?.groupValues?.get(1)?.toFloatOrNull() ?: index.toFloat()
                                date_upload = -1L
                                scanlator = "$size • /$pathName"
                            },
                        )
                    }
                    if (it.mimeType.endsWith(".folder")) {
                        traverseFolder(it.id, "$path/${it.title}", recursionDepth + 1)
                    }
                }

                pageToken = parsed.nextPageToken
            }
        }

        traverseFolder(folderId, path)

        return episodeList
    }

    // https://github.com/yt-dlp/yt-dlp/blob/8f0be90ecb3b8d862397177bb226f17b245ef933/yt_dlp/extractor/youtube.py#L573
    private fun generateSapisidhashHeader(SAPISID: String, origin: String = "https://drive.google.com"): String {
        val timeNow = System.currentTimeMillis() / 1000
        // SAPISIDHASH algorithm from https://stackoverflow.com/a/32065323
        val sapisidhash = MessageDigest
            .getInstance("SHA-1")
            .digest("$timeNow $SAPISID $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timeNow}_$sapisidhash"
    }

    @Serializable
    data class GDrivePostResponse(
        val nextPageToken: String? = null,
        val items: List<ResponseItem>? = null,
    ) {
        @Serializable
        data class ResponseItem(
            val id: String,
            val title: String,
            val mimeType: String,
            val fileSize: String? = null,
        )
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    companion object {
        private val ITEM_NUMBER_REGEX = """ - (?:S\d+E)?(\d+)""".toRegex()
        private val KEY_REGEX = """"(\w{39})"""".toRegex()
        private val VERSION_REGEX = """"([^"]+web-frontend[^"]+)"""".toRegex()
        private val JSON_REGEX = """(?:)\s*(\{(.+)\})\s*(?:)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="
    }
}
