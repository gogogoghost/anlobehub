package cc.jaxy.anlobehub.core.data.agent

import cc.jaxy.anlobehub.core.common.result.AnError
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.local.AgentRow
import cc.jaxy.anlobehub.core.data.local.LocalDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class Agent(val id: String = "", val title: String? = null, val name: String? = null, val description: String? = null, val avatar: String? = null, val backgroundColor: String? = null) {
    val displayName: String get() = name?.takeIf(String::isNotBlank) ?: title?.takeIf(String::isNotBlank) ?: id
}
@Serializable
data class AgentConfig(val title: String? = null, val description: String? = null, val systemRole: String? = null, val model: String? = null, val provider: String? = null, val temperature: Double? = null, val avatar: String? = null, val backgroundColor: String? = null, val openingMessage: String? = null, val openingQuestions: List<String> = emptyList())

interface AgentRepository {
    suspend fun listAgents(keyword: String? = null): AnResult<List<Agent>>
    suspend fun getConfig(agentId: String): AnResult<AgentConfig>
    suspend fun create(title: String, systemRole: String? = null, model: String? = null, provider: String? = null, description: String? = null, avatar: String? = null, openingMessage: String? = null, openingQuestions: List<String> = emptyList()): AnResult<String>
    suspend fun updateConfig(agentId: String, fields: Map<String, JsonElement?>): AnResult<Unit>
    suspend fun remove(agentId: String): AnResult<Unit>
    suspend fun duplicate(agentId: String): AnResult<String>
}

@Singleton
class AgentRepositoryImpl @Inject constructor(private val dao: LocalDao) : AgentRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class StoredAgent(val agent: Agent, val config: AgentConfig)

    override suspend fun listAgents(keyword: String?): AnResult<List<Agent>> = runCatching {
        dao.agents().map { json.decodeFromString<StoredAgent>(it.payload).agent }
            .filter { keyword.isNullOrBlank() || it.displayName.contains(keyword, true) || it.description.orEmpty().contains(keyword, true) }
            .sortedBy { it.displayName.lowercase() }
    }.toResult()
    override suspend fun getConfig(agentId: String): AnResult<AgentConfig> = runCatching {
        json.decodeFromString<StoredAgent>(dao.agent(agentId)?.payload ?: error("Agent not found")).config
    }.toResult()
    override suspend fun create(title: String, systemRole: String?, model: String?, provider: String?, description: String?, avatar: String?, openingMessage: String?, openingQuestions: List<String>): AnResult<String> {
        if (title.isBlank()) return AnResult.Err(AnError("INVALID_INPUT", "title is required"))
        val id = UUID.randomUUID().toString()
        val config = AgentConfig(title, description, systemRole, model, provider, avatar = avatar, openingMessage = openingMessage, openingQuestions = openingQuestions.filter(String::isNotBlank))
        val agent = Agent(id, title = title, description = description, avatar = avatar)
        return runCatching { dao.putAgent(AgentRow(id, json.encodeToString(StoredAgent(agent, config)))); id }.toResult()
    }
    override suspend fun updateConfig(agentId: String, fields: Map<String, JsonElement?>): AnResult<Unit> = runCatching {
        val stored = json.decodeFromString<StoredAgent>(dao.agent(agentId)?.payload ?: error("Agent not found"))
        val old = stored.config
        fun string(key: String, previous: String?): String? = if (key !in fields) previous else (fields[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String, previous: Double?): Double? = if (key !in fields) previous else (fields[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val questions = if ("openingQuestions" !in fields) old.openingQuestions else (fields["openingQuestions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val next = old.copy(title = string("title", old.title), description = string("description", old.description), systemRole = string("systemRole", old.systemRole), model = string("model", old.model), provider = string("provider", old.provider), temperature = number("temperature", old.temperature), avatar = string("avatar", old.avatar), backgroundColor = string("backgroundColor", old.backgroundColor), openingMessage = string("openingMessage", old.openingMessage), openingQuestions = questions)
        val agent = stored.agent.copy(title = next.title, description = next.description, avatar = next.avatar, backgroundColor = next.backgroundColor)
        dao.putAgent(AgentRow(agentId, json.encodeToString(StoredAgent(agent, next))))
    }.toResult()
    override suspend fun remove(agentId: String): AnResult<Unit> = runCatching { dao.removeAgent(agentId) }.toResult()
    override suspend fun duplicate(agentId: String): AnResult<String> = runCatching {
        val source = json.decodeFromString<StoredAgent>(dao.agent(agentId)?.payload ?: error("Agent not found"))
        val id = UUID.randomUUID().toString()
        val title = "${source.config.title ?: source.agent.displayName} (copy)"
        val config = source.config.copy(title = title)
        dao.putAgent(AgentRow(id, json.encodeToString(StoredAgent(source.agent.copy(id = id, title = title), config))))
        id
    }.toResult()
}
private fun <T> Result<T>.toResult(): AnResult<T> = fold({ AnResult.Ok(it) }, { if (it is kotlinx.coroutines.CancellationException) throw it else AnResult.Err(AnError("LOCAL_DATA", it.message ?: "Local data operation failed", it)) })
