package cc.jaxy.anlobehub.core.data.provider

import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.local.CatalogSeeder
import cc.jaxy.anlobehub.core.data.local.LocalDao
import cc.jaxy.anlobehub.core.data.local.ModelRow
import cc.jaxy.anlobehub.core.data.local.ProviderRow
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRemoteModelDiscoveryTest {
    @Test
    fun `model discovery returns distinct ids and persists local models`() = runBlocking {
        val authorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/models") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                val body = """{"data":[{"id":"alpha"},{"id":"alpha"},{"id":"beta"},{"id":""}]}"""
                    .toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        try {
            val providerId = "custom-local"
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
            val providers = linkedMapOf(
                providerId to ProviderRow(
                    id = providerId,
                    name = "Local endpoint",
                    source = "custom",
                    enabled = true,
                    config = Json.encodeToString(mapOf("baseURL" to baseUrl, "__sdkType" to "openai")),
                ),
            )
            val models = linkedMapOf(
                (providerId to "alpha") to ModelRow(
                    id = "alpha",
                    providerId = providerId,
                    displayName = "Alpha",
                    enabled = false,
                    metadata = """{"type":"chat","chatSupported":true}""",
                ),
            )
            val dao = memoryDao(providers, models)
            val repository = ProviderRepositoryImpl(dao, EmptySecrets, CatalogSeeder(dao) { """{"providers":[],"models":[]}""" })

            val result = repository.fetchRemoteModels(providerId, baseUrl, "local-test-key")

            assertTrue(result is AnResult.Ok)
            assertEquals(listOf("alpha", "beta"), (result as AnResult.Ok).value)
            assertEquals("Bearer local-test-key", authorization.get())
            assertEquals(false, models.getValue(providerId to "alpha").enabled)
            assertEquals(false, models.getValue(providerId to "beta").enabled)
            assertEquals(
                "chat",
                Json.parseToJsonElement(models.getValue(providerId to "beta").metadata)
                    .jsonObject.getValue("type").jsonPrimitive.content,
            )
        } finally {
            server.stop(0)
        }
    }

    private fun memoryDao(
        providers: MutableMap<String, ProviderRow>,
        models: MutableMap<Pair<String, String>, ModelRow>,
    ): LocalDao = java.lang.reflect.Proxy.newProxyInstance(
        LocalDao::class.java.classLoader,
        arrayOf(LocalDao::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "providers" -> providers.values.toList()
            "provider" -> providers[args?.firstOrNull() as String]
            "models" -> models.values.toList()
            "model" -> models[args?.get(0) as String to args[1] as String]
            "putProvider" -> {
                val row = args?.firstOrNull() as ProviderRow
                providers[row.id] = row
                Unit
            }
            "putModels" -> {
                @Suppress("UNCHECKED_CAST")
                val rows = args?.firstOrNull() as List<ModelRow>
                rows.forEach { models[it.providerId to it.id] = it }
                Unit
            }
            "putModel" -> {
                val row = args?.firstOrNull() as ModelRow
                models[row.providerId to row.id] = row
                Unit
            }
            "toString" -> "ProviderDiscoveryDaoTestProxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected local DAO call: ${method.name}")
        }
    } as LocalDao

    private object EmptySecrets : ProviderSecretStore {
        override suspend fun read(providerId: String): Map<String, String> = emptyMap()
        override suspend fun write(providerId: String, secrets: Map<String, String>) = Unit
    }
}
