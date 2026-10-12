package cc.jaxy.anlobehub.core.data.chat

import cc.jaxy.anlobehub.core.common.result.AnError
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.network.trpc.TrpcClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface TopicRepository {
    suspend fun listTopics(
        baseUrl: String,
        sessionId: String? = null,
        agentId: String? = null,
    ): AnResult<List<ChatTopic>>

    suspend fun pageTopics(
        baseUrl: String,
        page: Int,
        pageSize: Int,
        sessionId: String? = null,
        agentId: String? = null,
    ): AnResult<TopicPage>
    suspend fun createTopic(
        baseUrl: String,
        title: String,
        sessionId: String? = null,
        agentId: String? = null,
    ): AnResult<String>
    suspend fun deleteTopic(baseUrl: String, id: String): AnResult<Unit>
}

@Singleton
class TopicRepositoryImpl @Inject constructor(
    private val trpc: TrpcClient,
) : TopicRepository {

    override suspend fun listTopics(
        baseUrl: String,
        sessionId: String?,
        agentId: String?,
    ): AnResult<List<ChatTopic>> {
        val input = buildJsonObject {
            if (!agentId.isNullOrBlank()) put("agentId", agentId)
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
        }.takeIf { it.isNotEmpty() }
        return trpc.query(baseUrl, "topic.getTopics", input) { it.asTopicList() }
    }

    override suspend fun pageTopics(
        baseUrl: String,
        page: Int,
        pageSize: Int,
        sessionId: String?,
        agentId: String?,
    ): AnResult<TopicPage> {
        val input = buildJsonObject {
            if (!agentId.isNullOrBlank()) put("agentId", agentId)
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
            // Server paging params are `current` + `pageSize` (`page` is ignored).
            put("current", page)
            put("pageSize", pageSize)
        }
        return trpc.query(baseUrl, "topic.getTopics", input) { it.asTopicPage() }
    }

    override suspend fun createTopic(
        baseUrl: String,
        title: String,
        sessionId: String?,
        agentId: String?,
    ): AnResult<String> {
        if (title.isBlank()) {
            return AnResult.Err(AnError(code = "INVALID_INPUT", message = "empty title"))
        }
        val input = buildJsonObject {
            put("title", title)
            if (!agentId.isNullOrBlank()) put("agentId", agentId)
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
        }
        return trpc.mutate(baseUrl, "topic.createTopic", input) { el ->
            el.extractId()
                ?: throw IllegalStateException("missing id in createTopic response")
        }
    }

    override suspend fun deleteTopic(baseUrl: String, id: String): AnResult<Unit> {
        val input = buildJsonObject { put("id", id) }
        return trpc.mutate(baseUrl, "topic.removeTopic", input) { }
    }
}
