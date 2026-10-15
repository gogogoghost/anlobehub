package cc.jaxy.anlobehub.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import cc.jaxy.anlobehub.core.common.util.providerConfigSpec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val ACTIVATION_DEFAULTS_VERSION_KEY = "__localActivationDefaultsVersion"
internal const val ACTIVATION_DEFAULTS_VERSION = "1"

@Serializable
private data class BundledCatalog(
    val source: JsonObject = JsonObject(emptyMap()),
    val providers: List<BundledProvider> = emptyList(),
    val models: List<JsonObject> = emptyList(),
)

@Serializable
private data class BundledProvider(
    val id: String,
    val name: String,
    val description: String? = null,
    val url: String? = null,
    val apiKeyUrl: String? = null,
    val modelsUrl: String? = null,
    val checkModel: String? = null,
    val proxyPlaceholder: String? = null,
    val showApiKey: Boolean = true,
    val authType: String? = null,
    val protocol: String? = null,
    val showChecker: Boolean = true,
    val showModelFetcher: Boolean = false,
    val chatSupported: Boolean = false,
    val adapterType: String? = null,
    val modelCount: Int = 0,
)

private const val CATALOG_ASSET = "provider_catalog.json"

@Module
@InstallIn(SingletonComponent::class)
object LocalDatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LocalDatabase {
        lateinit var database: LocalDatabase
        database = Room.databaseBuilder(context, LocalDatabase::class.java, "local_agent.db")
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        CatalogSeeder(database.dao()) { loadCatalog(context) }.ensureSeeded()
                    }
                }
            })
            .build()
        return database
    }

    @Provides
    fun localDao(database: LocalDatabase): LocalDao = database.dao()

    @Provides
    @Singleton
    fun seedCatalog(
        dao: LocalDao,
        @ApplicationContext context: Context,
    ): CatalogSeeder = CatalogSeeder(dao) { loadCatalog(context) }

    private fun loadCatalog(context: Context): String =
        context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
}

@Singleton
class CatalogSeeder(
    private val dao: LocalDao,
    private val loadCatalog: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val bundledCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        json.decodeFromString<BundledCatalog>(loadCatalog())
    }

    @Volatile
    private var seeded = false

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        seedMutex.withLock {
            if (seeded) return@withLock

            val catalog = bundledCatalog
            val revision = catalog.source.string("commit") ?: "unknown"
            val catalogProviderIds = catalog.providers.mapTo(mutableSetOf()) { it.id }
            val existingProviders = dao.providers()
            val oldProviders = existingProviders.associateBy { it.id }
            val oldProviderConfigs = existingProviders.associate { row ->
                row.id to runCatching { json.decodeFromString<Map<String, String>>(row.config) }
                    .getOrDefault(emptyMap())
            }
            val existingModels = dao.models()
            val originalModels = existingModels.associateBy { it.providerId to it.id }
            val resetActivationDefaults = existingProviders.any { row ->
                (row.id in catalogProviderIds || row.source == "custom") &&
                    oldProviderConfigs[row.id]?.get(ACTIVATION_DEFAULTS_VERSION_KEY) != ACTIVATION_DEFAULTS_VERSION
            } || existingModels.any { row ->
                modelActivationVersion(row.metadata) != ACTIVATION_DEFAULTS_VERSION
            }

            val providers = catalog.providers.mapNotNull { provider ->
                if (provider.id == "lobehub") return@mapNotNull null
                val old = oldProviders[provider.id]
                val config = oldProviderConfigs[provider.id].orEmpty().toMutableMap()
                if (old?.source == "custom") {
                    config[ACTIVATION_DEFAULTS_VERSION_KEY] = ACTIVATION_DEFAULTS_VERSION
                    return@mapNotNull old.copy(
                        enabled = if (resetActivationDefaults) false else old.enabled,
                        config = json.encodeToString(config),
                    )
                }

                if (config["baseURL"].isNullOrBlank()) {
                    defaultEndpoint(provider)?.let { config["baseURL"] = it }
                }
                config["__catalogRevision"] = revision
                config["__catalogDescription"] = provider.description.orEmpty()
                config["__catalogUrl"] = provider.url.orEmpty()
                config["__catalogApiKeyUrl"] = provider.apiKeyUrl.orEmpty()
                config["__catalogModelsUrl"] = provider.modelsUrl.orEmpty()
                config["__catalogProxyPlaceholder"] = provider.proxyPlaceholder.orEmpty()
                config["__catalogShowApiKey"] = provider.showApiKey.toString()
                config["__catalogAuthType"] = provider.authType.orEmpty()
                config["__catalogProtocol"] = provider.protocol.orEmpty()
                config["__catalogShowChecker"] = provider.showChecker.toString()
                config["__catalogShowModelFetcher"] = provider.showModelFetcher.toString()
                config["__catalogChatSupported"] = (localAdapterType(provider) != null).toString()
                config["__catalogModelCount"] = provider.modelCount.toString()
                config["__catalogCheckModel"] = provider.checkModel.orEmpty()
                config[ACTIVATION_DEFAULTS_VERSION_KEY] = ACTIVATION_DEFAULTS_VERSION
                localAdapterType(provider)?.let { config["__sdkType"] = it } ?: config.remove("__sdkType")

                ProviderRow(
                    id = provider.id,
                    name = provider.name,
                    source = "builtin",
                    enabled = if (resetActivationDefaults) false else old?.enabled ?: false,
                    config = json.encodeToString(config),
                )
            }
            for (provider in providers) {
                if (oldProviders[provider.id] != provider) dao.putProvider(provider)
            }
            val seededProviderIds = providers.mapTo(mutableSetOf()) { it.id }
            for (old in existingProviders) {
                if (old.source != "custom" || old.id in seededProviderIds) continue
                val config = oldProviderConfigs[old.id].orEmpty().toMutableMap()
                config[ACTIVATION_DEFAULTS_VERSION_KEY] = ACTIVATION_DEFAULTS_VERSION
                val updated = old.copy(
                    enabled = if (resetActivationDefaults) false else old.enabled,
                    config = json.encodeToString(config),
                )
                if (updated != old) dao.putProvider(updated)
            }

            val normalizedModels = existingModels.map { row ->
                val metadata = runCatching { json.decodeFromString<JsonObject>(row.metadata) }
                    .getOrDefault(JsonObject(emptyMap()))
                val activationVersion = metadata.string(ACTIVATION_DEFAULTS_VERSION_KEY)
                val updatedMetadata = if (activationVersion == ACTIVATION_DEFAULTS_VERSION) {
                    row.metadata
                } else {
                    json.encodeToString(
                        JsonObject(metadata + (ACTIVATION_DEFAULTS_VERSION_KEY to JsonPrimitive(ACTIVATION_DEFAULTS_VERSION))),
                    )
                }
                row.copy(
                    enabled = if (resetActivationDefaults) false else row.enabled,
                    metadata = updatedMetadata,
                )
            }
            val oldModels = normalizedModels.associateBy { it.providerId to it.id }
            val providerSupport = catalog.providers.associate { it.id to (localAdapterType(it) != null) }
            val models = catalog.models.mapNotNull { model ->
                val id = model.string("id") ?: return@mapNotNull null
                val providerId = model.string("providerId") ?: return@mapNotNull null
                if (providerId == "lobehub") return@mapNotNull null
                val chatSupported = providerSupport[providerId] == true && model.string("type") == "chat"
                val old = oldModels[providerId to id]
                val metadata = JsonObject(
                    model +
                        ("chatSupported" to JsonPrimitive(chatSupported)) +
                        ("catalogRevision" to JsonPrimitive(revision)) +
                        (ACTIVATION_DEFAULTS_VERSION_KEY to JsonPrimitive(ACTIVATION_DEFAULTS_VERSION)),
                )
                ModelRow(
                    id = id,
                    providerId = providerId,
                    displayName = model.string("displayName")?.takeIf(String::isNotBlank) ?: id,
                    enabled = if (chatSupported) old?.enabled ?: false else false,
                    metadata = json.encodeToString(metadata),
                )
            }
            val catalogModelKeys = models.mapTo(mutableSetOf()) { it.providerId to it.id }
            val updatedModels = models + normalizedModels.filter { (it.providerId to it.id) !in catalogModelKeys }
            val changedModels = updatedModels.filter { originalModels[it.providerId to it.id] != it }
            if (changedModels.isNotEmpty()) dao.putModels(changedModels)
            seeded = true
        }
    }

    private fun localAdapterType(provider: BundledProvider): String? {
        if (provider.authType.equals("oauthDeviceFlow", ignoreCase = true)) return null
        return when (provider.protocol?.lowercase()) {
            "openai" -> "openai"
            "router" -> "router"
            "anthropic" -> "anthropic"
            else -> provider.adapterType?.takeIf { it in DIRECT_ADAPTER_TYPES }
        }
    }

    private fun modelActivationVersion(metadata: String): String? = runCatching {
        json.decodeFromString<JsonObject>(metadata).string(ACTIVATION_DEFAULTS_VERSION_KEY)
    }.getOrNull()


    private fun defaultEndpoint(provider: BundledProvider): String? {
        provider.proxyPlaceholder?.takeIf(String::isNotBlank)?.let { return it }
        providerConfigSpec(provider.id, "builtin").endpoint?.placeholder
            ?.takeUnless { it == GENERIC_ENDPOINT_PLACEHOLDER }
            ?.let { return it }
        return when (provider.id) {
            "openai" -> "https://api.openai.com/v1"
            "ollama" -> "http://localhost:11434"
            "ollamacloud" -> "https://ollama.com"
            "google" -> "https://generativelanguage.googleapis.com"
            "bedrock" -> "https://bedrock-runtime.us-east-1.amazonaws.com"
            "vertexai" -> "https://aiplatform.googleapis.com"
            else -> null
        }
    }

    private companion object {
        const val GENERIC_ENDPOINT_PLACEHOLDER = "https://your-proxy-url.com/v1"
        val seedMutex = Mutex()
        val DIRECT_ADAPTER_TYPES = setOf("openai", "router", "anthropic", "google", "ollama")
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

