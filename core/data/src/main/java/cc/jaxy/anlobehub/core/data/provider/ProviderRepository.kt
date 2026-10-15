package cc.jaxy.anlobehub.core.data.provider

import cc.jaxy.anlobehub.core.common.result.AnError
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.common.util.FieldType
import cc.jaxy.anlobehub.core.common.util.buildModelsUrl
import cc.jaxy.anlobehub.core.common.util.parseModelIds
import cc.jaxy.anlobehub.core.common.util.providerConfigSpec
import cc.jaxy.anlobehub.core.common.util.specialFieldsFor
import cc.jaxy.anlobehub.core.data.local.CatalogSeeder
import cc.jaxy.anlobehub.core.data.local.ACTIVATION_DEFAULTS_VERSION
import cc.jaxy.anlobehub.core.data.local.ACTIVATION_DEFAULTS_VERSION_KEY
import cc.jaxy.anlobehub.core.data.local.LocalDao
import cc.jaxy.anlobehub.core.data.local.ModelRow
import cc.jaxy.anlobehub.core.data.local.ProviderRow
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class AiProvider(
    val id: String = "",
    val name: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val source: String? = null,
    val chatSupported: Boolean = true,
)
data class ProviderDetail(
    val id: String = "",
    val name: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    val source: String? = null,
    val checkModel: String? = null,
    val keyVaults: Map<String, String> = emptyMap(),
    val websiteUrl: String? = null,
    val apiKeyUrl: String? = null,
    val modelsUrl: String? = null,
    val endpointPlaceholder: String? = null,
    val showApiKey: Boolean? = null,
    val authType: String? = null,
    val catalogProtocol: String? = null,
    val chatSupported: Boolean = true,
    val showChecker: Boolean = true,
    val showModelFetcher: Boolean = false,
    val modelCount: Int = 0,
) {
    val apiKey: String? get() = keyVaults["apiKey"]
    val baseURL: String? get() = keyVaults["baseURL"]
}
data class CheckResult(val ok: Boolean = false, val error: String? = null)

private val DIRECT_ADAPTER_TYPES = setOf("openai", "router", "anthropic", "google", "ollama")
private const val GENERIC_COMPAT_ENDPOINT_PLACEHOLDER = "https://your-proxy-url.com/v1"

 

interface ProviderRepository {
    suspend fun listProviders(): AnResult<List<AiProvider>>
    suspend fun toggleProvider(id: String, enabled: Boolean): AnResult<Unit>
    suspend fun getDetail(id: String): AnResult<ProviderDetail>
    suspend fun updateConfig(id: String, keyVaults: Map<String, String?> = emptyMap(), checkModel: String? = null): AnResult<Unit>
    suspend fun checkConnectivity(id: String, model: String? = null): AnResult<CheckResult>
    suspend fun fetchRemoteModels(providerId: String, baseURL: String, apiKey: String): AnResult<List<String>>
    suspend fun createCustom(name: String, baseURL: String): AnResult<String>
}

@Singleton
class ProviderRepositoryImpl @Inject constructor(private val dao: LocalDao, private val secrets: ProviderSecretStore, private val seeder: CatalogSeeder) : ProviderRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private suspend fun seed() = seeder.ensureSeeded()
    private fun secretKeys(id: String, source: String): Set<String> = buildSet {
        val spec = providerConfigSpec(id, source)
        add("apiKey")
        spec.extraFields.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
        spec.authModes.flatMap { it.fields }.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
        specialFieldsFor(id)?.let { fields ->
            fields.apiKeyItems.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
            fields.authModes.flatMap { it.fields }.filter { it.type == FieldType.SECRET }.forEach { add(it.key) }
        }
        add("accessKeyId")
        add("username")
    }
    override suspend fun listProviders(): AnResult<List<AiProvider>> = action {
        seed()
        dao.providers().mapNotNull { row ->
            if (row.id == "lobehub") return@mapNotNull null
            val config = json.decodeFromString<Map<String, String>>(row.config)
            AiProvider(
                id = row.id,
                name = row.name,
                description = config["__catalogDescription"]?.takeIf(String::isNotBlank),
                enabled = row.enabled,
                source = row.source,
                chatSupported = config["__catalogChatSupported"]?.toBoolean()
                    ?: (row.source == "custom" || config["__sdkType"]?.let { it in DIRECT_ADAPTER_TYPES } == true),
            )
        }
    }
    override suspend fun toggleProvider(id: String, enabled: Boolean): AnResult<Unit> = action { seed(); val row = dao.provider(id) ?: error("Provider not found"); dao.putProvider(row.copy(enabled = enabled)) }
    override suspend fun getDetail(id: String): AnResult<ProviderDetail> = action {
        seed(); val row = dao.provider(id) ?: error("Provider not found")
        val stored = json.decodeFromString<Map<String, String>>(row.config)
        val encrypted = secrets.read(id).toMutableMap()
        val sensitive = secretKeys(id, row.source)
        val legacySecrets = stored.filter { (key, value) -> key in sensitive && value.isNotBlank() }
        val plain = stored.toMutableMap()
        val hadLegacyFetchSetting = plain.remove("__fetchOnClient") != null
        val hadLegacyResponseSetting = plain.remove("__enableResponseApi") != null
        if (legacySecrets.isNotEmpty()) {
            legacySecrets.forEach { (key, value) -> encrypted.putIfAbsent(key, value) }
            secrets.write(id, encrypted)
            legacySecrets.keys.forEach(plain::remove)
        }
        if (legacySecrets.isNotEmpty() || hadLegacyFetchSetting || hadLegacyResponseSetting) {
            dao.putProvider(row.copy(config = json.encodeToString(plain)))
        }
        val config = plain.filterKeys { !it.startsWith("__") } + encrypted
        val spec = providerConfigSpec(id, row.source)
        ProviderDetail(
            id = id,
            name = row.name,
            description = stored["__catalogDescription"]?.takeIf(String::isNotBlank),
            enabled = row.enabled,
            source = row.source,
            checkModel = stored["__checkModel"]?.takeIf(String::isNotBlank)
                ?: stored["__catalogCheckModel"]?.takeIf(String::isNotBlank)
                ?: spec.checkModel,
            keyVaults = config,
            websiteUrl = stored["__catalogUrl"]?.takeIf(String::isNotBlank),
            apiKeyUrl = stored["__catalogApiKeyUrl"]?.takeIf(String::isNotBlank),
            modelsUrl = stored["__catalogModelsUrl"]?.takeIf(String::isNotBlank),
            endpointPlaceholder = stored["__catalogProxyPlaceholder"]?.takeIf(String::isNotBlank)
                ?: spec.endpoint?.placeholder
                ?: GENERIC_COMPAT_ENDPOINT_PLACEHOLDER.takeIf { stored["__sdkType"] in DIRECT_ADAPTER_TYPES },
            showApiKey = stored["__catalogShowApiKey"]?.toBoolean(),
            authType = stored["__catalogAuthType"]?.takeIf(String::isNotBlank),
            catalogProtocol = stored["__catalogProtocol"]?.takeIf(String::isNotBlank),
            chatSupported = stored["__catalogChatSupported"]?.toBoolean()
                ?: (row.source == "custom" || stored["__sdkType"]?.let { it in DIRECT_ADAPTER_TYPES } == true),
            showChecker = stored["__catalogShowChecker"]?.toBoolean() ?: spec.showChecker,
            showModelFetcher = stored["__catalogShowModelFetcher"]?.toBoolean() ?: (row.source == "custom"),
            modelCount = stored["__catalogModelCount"]?.toIntOrNull() ?: 0,
        )
    }
    override suspend fun updateConfig(id: String, keyVaults: Map<String, String?>, checkModel: String?): AnResult<Unit> = action {
        seed(); val row = dao.provider(id) ?: error("Provider not found")
        val plain = json.decodeFromString<Map<String, String>>(row.config).toMutableMap().apply {
            remove("__fetchOnClient")
            remove("__enableResponseApi")
        }
        val encrypted = secrets.read(id).toMutableMap(); val sensitive = secretKeys(id, row.source)
        keyVaults.forEach { (key, value) ->
            if (value.isNullOrBlank()) {
                plain.remove(key)
                encrypted.remove(key)
            } else if (key in sensitive) {
                plain.remove(key)
                encrypted[key] = value
            } else {
                plain[key] = value
            }
        }
        if (checkModel != null) plain["__checkModel"] = checkModel
        secrets.write(id, encrypted)
        dao.putProvider(row.copy(config = json.encodeToString(plain)))
    }
    override suspend fun checkConnectivity(id: String, model: String?): AnResult<CheckResult> = when (val detail = getDetail(id)) {
        is AnResult.Err -> AnResult.Err(detail.error)
        is AnResult.Ok -> when (val models = fetchRemoteModels(id, detail.value.baseURL.orEmpty(), detail.value.apiKey.orEmpty())) {
            is AnResult.Ok -> AnResult.Ok(CheckResult(true))
            is AnResult.Err -> AnResult.Ok(CheckResult(false, models.error.message))
        }
    }
    override suspend fun fetchRemoteModels(providerId: String, baseURL: String, apiKey: String): AnResult<List<String>> = try {
        seed()
        val row = dao.provider(providerId) ?: error("Provider not found")
        val config = json.decodeFromString<Map<String, String>>(row.config)
        val sdkType = config["__sdkType"]
        require(row.source == "custom" || sdkType in DIRECT_ADAPTER_TYPES) {
            "Model discovery is not implemented for this provider"
        }
        val base = baseURL.trimEnd('/')
        val request = when (sdkType) {
            "anthropic" -> Request.Builder().url((if (base.endsWith("/v1")) base else "$base/v1") + "/models").get().header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
            "google" -> Request.Builder().url((if (base.endsWith("/v1beta")) base else "$base/v1beta") + "/models").get().apply { if (apiKey.isNotBlank()) header("x-goog-api-key", apiKey) }.build()
            "ollama" -> Request.Builder().url(base.removeSuffix("/v1") + "/api/tags").get().build()
            else -> Request.Builder().url(buildModelsUrl(baseURL)).get().apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }.build()
        }
        val models = withContext(Dispatchers.IO) { client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Provider returned HTTP ${response.code}: ${body.take(500)}")
            val element = json.parseToJsonElement(body)
            when (sdkType) {
                "google" -> ((element as? kotlinx.serialization.json.JsonObject)?.get("models") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { model ->
                    val name = (model as? kotlinx.serialization.json.JsonObject)?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    name?.removePrefix("models/")?.takeIf(String::isNotBlank)
                }.orEmpty()
                "ollama" -> ((element as? kotlinx.serialization.json.JsonObject)?.get("models") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { model ->
                    (model as? kotlinx.serialization.json.JsonObject)?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                }.orEmpty()
                else -> parseModelIds(element)
            }
        } }
        val distinctModels = models.distinct()
        distinctModels.forEach { id ->
            val old = dao.model(providerId, id)
            val metadata = old?.metadata?.takeIf { it != "{}" }?.let {
                runCatching { json.decodeFromString<JsonObject>(it) }.getOrNull()
            } ?: JsonObject(mapOf("type" to JsonPrimitive("chat")))
            val stampedMetadata = JsonObject(
                metadata + (ACTIVATION_DEFAULTS_VERSION_KEY to JsonPrimitive(ACTIVATION_DEFAULTS_VERSION)),
            )
            dao.putModel(
                ModelRow(
                    id = id,
                    providerId = providerId,
                    displayName = id,
                    enabled = old?.enabled ?: false,
                    metadata = json.encodeToString(stampedMetadata),
                ),
            )
        }
        AnResult.Ok(distinctModels)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("PROVIDER", e.message ?: "Unable to fetch models", e)) }
    override suspend fun createCustom(name: String, baseURL: String): AnResult<String> = action {
        seed(); require(name.isNotBlank() && baseURL.isNotBlank()) { "Name and base URL are required" }
        val id = "custom-${UUID.randomUUID()}"
        dao.putProvider(
            ProviderRow(
                id = id,
                name = name.trim(),
                source = "custom",
                enabled = false,
                config = json.encodeToString(
                    mapOf(
                        "baseURL" to baseURL.trimEnd('/'),
                        "__sdkType" to "openai",
                        ACTIVATION_DEFAULTS_VERSION_KEY to ACTIVATION_DEFAULTS_VERSION,
                    ),
                ),
            ),
        )
        id
    }
}
private suspend fun <T> action(block: suspend () -> T): AnResult<T> = try { AnResult.Ok(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { AnResult.Err(AnError("LOCAL_PROVIDER", e.message ?: "Provider operation failed", e)) }
