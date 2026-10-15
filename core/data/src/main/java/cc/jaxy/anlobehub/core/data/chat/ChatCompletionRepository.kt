package cc.jaxy.anlobehub.core.data.chat

import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.local.LocalDao
import cc.jaxy.anlobehub.core.data.provider.ProviderSecretStore
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext

data class ChatStreamChunk(val textDelta: String = "", val reasoningDelta: String? = null)
interface ChatCompletionRepository {
    fun stream(providerId: String, modelId: String, systemPrompt: String, history: List<ChatMessage>, temperature: Double? = null): Flow<ChatStreamChunk>
}

@Singleton
class DirectChatCompletionRepository @Inject constructor(private val dao: LocalDao, private val secrets: ProviderSecretStore, private val files: FileRepository) : ChatCompletionRepository {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    override fun stream(providerId: String, modelId: String, systemPrompt: String, history: List<ChatMessage>, temperature: Double?): Flow<ChatStreamChunk> = channelFlow {
        require(modelId.isNotBlank()) { "Model is required" }
        val provider = dao.provider(providerId) ?: error("Provider not found")
        check(provider.enabled) { "Provider is disabled" }
        val model = dao.model(providerId, modelId) ?: error("Model not found")
        check(model.enabled) { "Model is disabled" }
        val config = json.decodeFromString<Map<String, String>>(provider.config) + secrets.read(providerId)
        val base = (config["baseURL"] ?: if (provider.source == "builtin") cc.jaxy.anlobehub.core.common.util.providerConfigSpec(providerId, provider.source).endpoint?.placeholder else null)
            ?.trimEnd('/')?.takeIf(String::isNotBlank) ?: error("Provider endpoint is not configured")
        val apiKey = config["apiKey"].orEmpty()
        val sdkType = config["__sdkType"]
        val protocol = if (provider.source == "custom") {
            "openai"
        } else {
            when (sdkType) {
                "openai", "router" -> "openai"
                "anthropic" -> "anthropic"
                "google" -> "gemini"
                "ollama" -> "ollama"
                else -> throw UnsupportedOperationException("No direct chat adapter is implemented for ${provider.name}.")
            }
        }
        val req = when (protocol) {
            "anthropic" -> anthropicRequest(base, apiKey, modelId, systemPrompt, history, temperature)
            "gemini" -> geminiRequest(base, apiKey, modelId, systemPrompt, history, temperature)
            "ollama" -> ollamaRequest(base, modelId, systemPrompt, history, temperature)
            else -> openAiRequest(base, apiKey, modelId, systemPrompt, history, temperature)
        }
        val call = http.newCall(req)
        val job = coroutineContext[kotlinx.coroutines.Job]
        val cancellation = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val body = response.body ?: throw IOException("Provider returned an empty response")
                    if (!response.isSuccessful) throw IOException("Provider returned HTTP ${response.code}: ${body.string().take(1500)}")
                    val source = body.source()
                    when (protocol) {
                        "ollama" -> while (!source.exhausted() && coroutineContext.isActive) {
                            val line = source.readUtf8Line()?.trim().orEmpty(); if (line.isBlank()) continue
                            val obj = json.parseToJsonElement(line) as? JsonObject ?: continue
                            obj["error"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { throw IOException(it) }
                            val message = obj["message"] as? JsonObject
                            val text = message?.get("content")?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
                            val reasoning = message?.get("thinking")?.jsonPrimitiveOrNull()?.contentOrNull
                            if (text.isNotEmpty() || !reasoning.isNullOrEmpty()) send(ChatStreamChunk(text, reasoning))
                        }
                        else -> {
                            var eventType = ""
                            while (!source.exhausted() && coroutineContext.isActive) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("event:")) { eventType = line.substringAfter(':').trim(); continue }
                                if (line.isBlank()) { eventType = ""; continue }
                                if (!line.startsWith("data:")) continue
                                val data = line.substringAfter(':').trim()
                                if (data == "[DONE]") break
                                val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
                                obj["error"]?.let { throw IOException(it.toString()) }
                                val chunk = when (protocol) {
                                    "anthropic" -> {
                                        val delta = obj["delta"] as? JsonObject
                                        val type = delta?.get("type")?.jsonPrimitiveOrNull()?.contentOrNull ?: eventType
                                        when (type) { "text_delta" -> ChatStreamChunk(delta?.get("text")?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()); "thinking_delta" -> ChatStreamChunk(reasoningDelta = delta?.get("thinking")?.jsonPrimitiveOrNull()?.contentOrNull); else -> null }
                                    }
                                    "gemini" -> {
                                        val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                                        val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                                        val text = StringBuilder(); val reasoning = StringBuilder()
                                        parts?.forEach { item -> val part = item as? JsonObject ?: return@forEach; val value = part["text"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty(); if ((part["thought"] as? JsonPrimitive)?.content == "true") reasoning.append(value) else text.append(value) }
                                        if (text.isEmpty() && reasoning.isEmpty()) null else ChatStreamChunk(text.toString(), reasoning.toString().takeIf(String::isNotEmpty))
                                    }
                                    else -> {
                                        val delta = (((obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject)
                                        val text = delta?.get("content")?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty()
                                        val reasoning = delta?.get("reasoning_content")?.jsonPrimitiveOrNull()?.contentOrNull ?: delta?.get("reasoning")?.jsonPrimitiveOrNull()?.contentOrNull
                                        if (text.isEmpty() && reasoning.isNullOrEmpty()) null else ChatStreamChunk(text, reasoning)
                                    }
                                }
                                if (chunk != null) send(chunk)
                            }
                        }
                    }
                }
            }
        } finally { cancellation?.dispose() }
    }

    private suspend fun openAiRequest(base: String, key: String, model: String, system: String, history: List<ChatMessage>, temperature: Double?): Request {
        val messages = mutableListOf<JsonObject>()
        if (system.isNotBlank()) messages += buildJsonObject { put("role", "system"); put("content", system) }
        for (message in history.filter { it.role != "system" }) {
            val parts = mutableListOf<kotlinx.serialization.json.JsonElement>()
            if (message.content.isNotEmpty()) parts += buildJsonObject { put("type", "text"); put("text", message.content) }
            for (attachment in message.attachments) {
                val bytes = readAttachment(attachment.uri)
                val data = "data:${attachment.mimeType};base64,${Base64.getEncoder().encodeToString(bytes)}"
                parts += buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", data) } }
            }
            messages += buildJsonObject {
                put("role", if (message.role == "assistant") "assistant" else "user")
                if (message.attachments.isEmpty()) put("content", message.content) else put("content", JsonArray(parts))
            }
        }
        val body = buildJsonObject { put("model", model); put("stream", true); put("messages", JsonArray(messages)); temperature?.let { put("temperature", it) } }
        return Request.Builder().url(chatUrl(base, "/chat/completions")).post(body.toString().toRequestBody("application/json".toMediaType())).apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.build()
    }
    private suspend fun anthropicRequest(base: String, key: String, model: String, system: String, history: List<ChatMessage>, temperature: Double?): Request {
        val messages = mutableListOf<JsonObject>()
        for (message in history.filter { it.role != "system" }) {
            val parts = mutableListOf<kotlinx.serialization.json.JsonElement>()
            if (message.content.isNotEmpty()) parts += buildJsonObject { put("type", "text"); put("text", message.content) }
            for (attachment in message.attachments) {
                val b64 = Base64.getEncoder().encodeToString(readAttachment(attachment.uri))
                parts += buildJsonObject { put("type", "image"); putJsonObject("source") { put("type", "base64"); put("media_type", attachment.mimeType); put("data", b64) } }
            }
            messages += buildJsonObject { put("role", if (message.role == "assistant") "assistant" else "user"); put("content", JsonArray(parts)) }
        }
        val body = buildJsonObject { put("model", model); put("max_tokens", 4096); put("stream", true); if (system.isNotBlank()) put("system", system); put("messages", JsonArray(messages)); temperature?.let { put("temperature", it) } }
        return Request.Builder().url(endpoint(base, "/v1/messages")).post(body.toString().toRequestBody("application/json".toMediaType())).header("x-api-key", key).header("anthropic-version", "2023-06-01").build()
    }
    private suspend fun geminiRequest(base: String, key: String, model: String, system: String, history: List<ChatMessage>, temperature: Double?): Request {
        val contents = mutableListOf<JsonObject>()
        for (message in history.filter { it.role != "system" }) {
            val parts = mutableListOf<kotlinx.serialization.json.JsonElement>()
            if (message.content.isNotEmpty()) parts += buildJsonObject { put("text", message.content) }
            for (attachment in message.attachments) {
                val b64 = Base64.getEncoder().encodeToString(readAttachment(attachment.uri))
                parts += buildJsonObject { putJsonObject("inlineData") { put("mimeType", attachment.mimeType); put("data", b64) } }
            }
            contents += buildJsonObject { put("role", if (message.role == "assistant") "model" else "user"); put("parts", JsonArray(parts)) }
        }
        val body = buildJsonObject { put("contents", JsonArray(contents)); if (system.isNotBlank()) putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }; temperature?.let { putJsonObject("generationConfig") { put("temperature", it) } } }
        val path = "/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:streamGenerateContent?alt=sse"
        return Request.Builder().url(endpoint(base, path)).post(body.toString().toRequestBody("application/json".toMediaType())).apply { if (key.isNotBlank()) header("x-goog-api-key", key) }.build()
    }
    private suspend fun ollamaRequest(base: String, model: String, system: String, history: List<ChatMessage>, temperature: Double?): Request {
        val items = mutableListOf<JsonObject>()
        if (system.isNotBlank()) items += buildJsonObject { put("role", "system"); put("content", system) }
        for (message in history) {
            val images = message.attachments.map { Base64.getEncoder().encodeToString(readAttachment(it.uri)) }
            items += buildJsonObject {
                put("role", if (message.role == "assistant") "assistant" else "user")
                put("content", message.content)
                if (images.isNotEmpty()) putJsonArray("images") { images.forEach { add(JsonPrimitive(it)) } }
            }
        }
        val body = buildJsonObject { put("model", model); put("stream", true); put("messages", JsonArray(items)); temperature?.let { putJsonObject("options") { put("temperature", it) } } }
        val url = endpoint(base, "/api/chat")
        return Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
    private suspend fun readAttachment(uri: String): ByteArray {
        val id = uri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: uri
        return when (val file = files.readFile(id)) { is AnResult.Ok -> file.value; is AnResult.Err -> throw IOException(file.error.message, file.error.cause) }
    }
    private fun endpoint(base: String, path: String): String {
        val root = base.trimEnd('/')
        return if (root.endsWith("/v1") && path.startsWith("/v1/")) root + path.removePrefix("/v1") else root + path
    }
    private fun chatUrl(base: String, path: String): String {
        val root = base.trimEnd('/')
        val authorityStart = root.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val hasPathPrefix = root.indexOf('/', authorityStart) >= 0
        return if (hasPathPrefix) root + path else "$root/v1$path"
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull() = this as? kotlinx.serialization.json.JsonPrimitive
