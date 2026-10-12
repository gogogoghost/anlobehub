package cc.jaxy.anlobehub.core.data.files

import cc.jaxy.anlobehub.core.common.result.AnError
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.network.trpc.TrpcClient
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class UploadedFile(
    val id: String = "",
    val url: String? = null,
)

interface FileRepository {
    suspend fun uploadFile(
        baseUrl: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): AnResult<UploadedFile>
}

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val trpc: TrpcClient,
    private val httpClient: OkHttpClient,
    private val json: Json,
) : FileRepository {

    override suspend fun uploadFile(
        baseUrl: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): AnResult<UploadedFile> {
        if (bytes.isEmpty()) {
            return AnResult.Err(AnError(code = "INVALID_INPUT", message = "empty file content"))
        }
        val hash = sha256Hex(bytes)
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.isNotBlank() } ?: "file"
        val contentType = mime.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        // 1. 秒传检查：命中则跳过 PUT，但仍需 createFile 落库换正式 URL
        // （hash 接口回的是裸 pathname，Avatar 等场景要求完整 http(s)）。
        val checked = try {
            kotlinx.coroutines.withTimeout(15000L) { checkFileHash(baseUrl, hash) }
        } catch (e: Exception) {
            return AnResult.Err(AnError("NETWORK", "hash check timed out: " + e.message))
        }
        var dedupPathname: String? = null
        when (checked) {
            is AnResult.Ok -> {
                dedupPathname = checked.value?.takeIf { it.isNotBlank() }
            }
            is AnResult.Err -> return AnResult.Err(checked.error)
        }
        if (dedupPathname != null) {
            // 秒传：跳过 presign + PUT，直接落库换正式 url。
            val input = buildJsonObject {
                put("name", safeName)
                put("hash", hash)
                put("fileType", contentType)
                put("size", bytes.size)
                put("url", dedupPathname!!)
            }
            return trpc.mutate(baseUrl, "file.createFile", input) { it.toUploadedFile(dedupPathname) }
        }

        // 2. 取预签名 URL（pathname 透传给第 4 步的 url 字段）。
        val pathname = "files/${UUID.randomUUID()}/$safeName"
        val presigned = when (val r = createPresignedUrl(baseUrl, pathname, bytes.size)) {
            is AnResult.Ok -> r.value
            is AnResult.Err -> return AnResult.Err(r.error)
        }

        // 3. PUT 直传 S3。
        val putErr = putBytes(presigned, contentType, bytes)
        if (putErr != null) return AnResult.Err(putErr)

        // 4. 落库并取回 {id, url}。
        val input = buildJsonObject {
            put("name", safeName)
            put("hash", hash)
            put("fileType", contentType)
            put("size", bytes.size)
            put("url", pathname)
        }
        return trpc.mutate(baseUrl, "file.createFile", input) { it.toUploadedFile(pathname) }.also {
            }
    }

    private suspend fun checkFileHash(baseUrl: String, hash: String): AnResult<String?> {
        val input = buildJsonObject { put("hash", hash) }
        return trpc.mutate(baseUrl, "file.checkFileHash", input) { el ->
            val obj = el as? JsonObject ?: return@mutate null
            val isExist = (obj["isExist"] as? JsonPrimitive)?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false
            if (!isExist) return@mutate null
            (obj["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun createPresignedUrl(
        baseUrl: String,
        pathname: String,
        size: Int,
    ): AnResult<String> {
        val input = buildJsonObject {
            put("pathname", pathname)
            put("size", size)
        }
        return trpc.mutate(baseUrl, "upload.createS3PreSignedUrl", input) { el ->
            el.extractPresignedUrl()
                ?: throw IllegalStateException("missing presigned url in createS3PreSignedUrl response")
        }
    }

    private suspend fun putBytes(url: String, mime: String, bytes: ByteArray): AnError? {
        try {
            return withContext(Dispatchers.IO) {
                val request = try {
                    Request.Builder()
                        .url(url)
                        .put(bytes.toRequestBody(mime.toMediaTypeOrNull()))
                        .build()
                } catch (e: IllegalArgumentException) {
                    return@withContext AnError("INVALID_INPUT", e.message ?: "invalid presigned url")
                }
                try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            null
                        } else {
                            val snippet = try {
                                response.body.string().take(300).takeIf { it.isNotBlank() }
                            } catch (_: Exception) {
                                null
                            }
                            AnError(
                                code = "HTTP_${response.code}",
                                message = snippet ?: "upload failed: HTTP ${response.code}",
                            )
                        }
                    }
                } catch (e: IOException) {
                    AnError("NETWORK", e.message ?: "network error", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AnError("UNKNOWN", e.message ?: "unknown error", e)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) append("%02x".format(b))
        }
    }

    @Suppress("unused")
    private fun keepJson(): Json = json
}

/** 预签名 URL 宽容提取：纯字符串或 {url}/{signedUrl}/{uploadUrl} 任一。 */
private fun JsonElement.extractPresignedUrl(): String? {
    if (this is JsonPrimitive) return contentOrNull?.takeIf { it.isNotBlank() }
    val obj = this as? JsonObject ?: return null
    for (key in listOf("url", "signedUrl", "uploadUrl", "presignedUrl")) {
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/** {id, url} 宽容提取；url 缺失时回落 pathname，保证调用方总有可用引用。 */
private fun JsonElement.toUploadedFile(fallbackUrl: String?): UploadedFile {
    if (this is JsonPrimitive) {
        val s = contentOrNull?.takeIf { it.isNotBlank() }
        return UploadedFile(id = s ?: "", url = s ?: fallbackUrl)
    }
    val obj = this as? JsonObject ?: return UploadedFile(url = fallbackUrl)
    val id = obj.stringOrNull("id") ?: obj.stringOrNull("fileId") ?: ""
    val url = obj.stringOrNull("url") ?: obj.stringOrNull("pathname") ?: obj.stringOrNull("path")
        ?: fallbackUrl
    if (id.isBlank() && url == null) {
        // 兼容服务端把 id/url 包一层返回。
        for (key in listOf("result", "data", "file")) {
            val nested = obj[key] ?: continue
            val retry = nested.toUploadedFile(null)
            if (retry.id.isNotBlank() || retry.url != null) return retry
        }
        return UploadedFile(url = fallbackUrl)
    }
    return UploadedFile(id = id, url = url)
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
