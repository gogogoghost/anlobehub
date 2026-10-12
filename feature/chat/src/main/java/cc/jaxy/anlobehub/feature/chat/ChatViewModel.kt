package cc.jaxy.anlobehub.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.chat.AIModel
import cc.jaxy.anlobehub.core.data.chat.ChatMessage
import cc.jaxy.anlobehub.core.data.chat.ReasoningConfig
import cc.jaxy.anlobehub.core.data.chat.ChatTopic
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.toUiText
import cc.jaxy.anlobehub.core.data.agent.AgentRepository
import cc.jaxy.anlobehub.core.data.chat.MessageRepository
import cc.jaxy.anlobehub.core.data.chat.ModelRepository
import cc.jaxy.anlobehub.core.data.chat.TopicRepository
import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.files.UploadedFile
import cc.jaxy.anlobehub.core.data.session.ServerStore
import cc.jaxy.anlobehub.core.network.gateway.AgentApi
import cc.jaxy.anlobehub.core.network.gateway.GatewayEvent
import cc.jaxy.anlobehub.core.network.gateway.GatewaySocket
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val messageRepository: MessageRepository,
    private val topicRepository: TopicRepository,
    private val modelRepository: ModelRepository,
    private val agentRepository: AgentRepository,
    private val fileRepository: FileRepository,
    private val agentApi: AgentApi,
    private val gatewaySocket: GatewaySocket,
) : ViewModel() {

    // Agent-first identity. "agentId" is the source of truth; "topicId" is the
    // optional initial topic (deep link). "agentTitle" is the display name passed
    // by the parent route (no extra fetch for the TopBar title).
    val agentId: String = savedStateHandle.get<String>("agentId") ?: ""
    val agentTitle: String? = savedStateHandle.get<String>("agentTitle")?.takeIf { it.isNotBlank() }
    private val initialTopicId: String? = savedStateHandle.get<String>("topicId")?.takeIf { it.isNotBlank() }
    // Legacy route compat during cutover; never sent to any backend. Parent removes it.
    @Suppress("unused")
    val sessionId: String? = savedStateHandle.get<String>("sessionId")
    val isInboxAgent: Boolean get() = agentId.isBlank() || agentId.equals("inbox", ignoreCase = true)

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val streaming: Boolean = false,
        val streamingText: String = "",
        val streamingReasoning: String = "",
        val streamingReasoningSecs: Double? = null,
        /** Sealed prelude/tool segments ahead of the live answer. */
        val streamingSteps: List<StreamSegment> = emptyList(),
        val error: UiText? = null,
        val topics: List<ChatTopic> = emptyList(),
        val topicsLoading: Boolean = false,
        val activeTopicId: String? = null,
        val models: List<AIModel> = emptyList(),
        val activeModel: AIModel? = null,
        val modelsLoading: Boolean = false,
        val pendingFiles: List<UploadedFile> = emptyList(),
        val uploading: Boolean = false,
        val reasoningConfig: ReasoningConfig? = null,
        val reasoningLoading: Boolean = false,
        val reasoningSaving: Boolean = false,
        val reasoningSavedTick: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** A sealed stream segment: prelude text or a tool call. */
    data class StreamSegment(
        val text: String = "",
        val toolName: String? = null,
        val toolDone: Boolean = true,
    )

    private var streamJob: Job? = null
    private var activeBaseUrl: String? = null
    private var activeOperationId: String? = null
    private var pendingAssistantId: String? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val baseUrl = currentBaseUrl()
            if (baseUrl == null) {
                _uiState.value = _uiState.value.copy(error = UiText.Res(R.string.chat_no_server))
                return@launch
            }
            // Models + agent config in parallel; the agent's bound model wins
            // over "first enabled" so sends use the right model from the start.
            _uiState.value = _uiState.value.copy(modelsLoading = true)
            val modelsDeferred = async { modelRepository.listModels(baseUrl) }
            val configDeferred = async {
                val id = effectiveAgentId()
                if (id.isNullOrBlank()) null
                else (agentRepository.getConfig(baseUrl, id) as? AnResult.Ok)?.value
            }
            val models = when (val result = modelsDeferred.await()) {
                is AnResult.Ok -> result.value
                is AnResult.Err -> emptyList()
            }
            val bound = configDeferred.await()
            // Fresh topic by default: history loads lazily via loadTopics().
            // A deep-linked topicId still needs the list to validate against.
            val needTopics = initialTopicId != null || _uiState.value.activeTopicId != null
            val topics = if (needTopics) {
                when (val result = topicRepository.listTopics(baseUrl, agentId = effectiveAgentId())) {
                    is AnResult.Ok -> result.value
                    is AnResult.Err -> emptyList()
                }
            } else {
                emptyList()
            }
            val keptTopic = _uiState.value.activeTopicId
                ?.takeIf { id -> topics.any { it.id == id } }
            val deepLinkTopic = initialTopicId?.takeIf { id -> topics.any { it.id == id } }
            val activeTopicId = keptTopic ?: deepLinkTopic
            val keptModel = _uiState.value.activeModel
                ?.takeIf { current -> models.any { it.id == current.id } }
            val boundModel = bound?.model?.takeIf { it.isNotBlank() }?.let { modelId ->
                models.firstOrNull {
                    it.id == modelId &&
                        (bound.provider.isNullOrBlank() || it.providerId == bound.provider)
                } ?: AIModel(id = modelId, providerId = bound.provider)
            }
            val activeModel = keptModel
                ?: boundModel
                ?: models.firstOrNull { it.enabled != false }
                ?: models.firstOrNull()
            _uiState.value = _uiState.value.copy(
                topics = topics,
                activeTopicId = activeTopicId,
                models = models,
                activeModel = activeModel,
                modelsLoading = false,
            )
            loadMessages()
        }
    }

    /** Lazily loads topic history (called when the history panel opens). */
    fun loadTopics() {
        if (_uiState.value.topicsLoading) return
        viewModelScope.launch {
            val baseUrl = currentBaseUrl() ?: return@launch
            _uiState.value = _uiState.value.copy(topicsLoading = true)
            when (val result = topicRepository.listTopics(baseUrl, agentId = effectiveAgentId())) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    topics = result.value,
                    topicsLoading = false,
                )
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    topicsLoading = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    /** Starts a fresh conversation (clears active topic + messages). */
    fun newConversation() {
        if (_uiState.value.streaming) stop()
        _uiState.value = _uiState.value.copy(activeTopicId = null, messages = emptyList(), error = null)
    }

    fun selectTopic(id: String?) {
        if (_uiState.value.activeTopicId == id) return
        if (_uiState.value.streaming) stop()
        _uiState.value = _uiState.value.copy(activeTopicId = id)
        viewModelScope.launch { loadMessages() }
    }

    fun selectModel(model: AIModel) {
        _uiState.value = _uiState.value.copy(activeModel = model)
    }

    fun send(text: String, fileIds: List<String> = emptyList()) {
        if (text.isBlank()) return
        if (_uiState.value.streaming) return
        streamJob?.cancel()
        streamJob = viewModelScope.launch { runSend(text, fileIds) }
    }

    fun attach(bytes: ByteArray, name: String, mime: String) {
        viewModelScope.launch {
            val baseUrl = currentBaseUrl()
            if (baseUrl == null) {
                _uiState.value = _uiState.value.copy(error = UiText.Res(R.string.chat_no_server))
                return@launch
            }
            _uiState.value = _uiState.value.copy(uploading = true, error = null)
            when (val result = fileRepository.uploadFile(baseUrl, name, mime, bytes)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    pendingFiles = _uiState.value.pendingFiles + result.value,
                    uploading = false,
                )
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    uploading = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun removePendingFile(file: UploadedFile) {
        _uiState.value = _uiState.value.copy(pendingFiles = _uiState.value.pendingFiles - file)
    }

    fun stop() {
        val snapshot = _uiState.value
        if (!snapshot.streaming) return
        streamJob?.cancel()
        streamJob = null
        val baseUrl = activeBaseUrl
        val operationId = activeOperationId
        if (!baseUrl.isNullOrBlank() && !operationId.isNullOrBlank()) {
            viewModelScope.launch { runCatching { agentApi.interruptTask(baseUrl, operationId) } }
        }
        val acc = snapshot.streamingText
        val accReasoning = snapshot.streamingReasoning.trim().takeIf { it.isNotBlank() }
        _uiState.value =
            if (acc.isNotBlank()) {
                snapshot.copy(
                    messages = snapshot.messages + ChatMessage(
                        id = pendingAssistantId ?: "asst-$operationId",
                        role = "assistant",
                        content = acc,
                        reasoning = accReasoning,
                        createdAt = System.currentTimeMillis(),
                    ),
                    streaming = false,
                    streamingText = "",
                    streamingReasoning = "", streamingReasoningSecs = null,
                )
            } else {
                snapshot.copy(streaming = false, streamingText = "", streamingReasoning = "", streamingReasoningSecs = null)
            }
        activeOperationId = null
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Loads reasoning config for the detail sheet (chat long-press). */
    fun loadReasoning(model: AIModel) {
        if (_uiState.value.reasoningLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reasoningLoading = true)
            val baseUrl = currentBaseUrl()
            if (baseUrl == null) {
                _uiState.value = _uiState.value.copy(
                    reasoningLoading = false,
                    error = UiText.Res(R.string.chat_no_server),
                )
                return@launch
            }
            when (val result = modelRepository.getReasoningConfig(baseUrl, model.id, model.providerId.orEmpty())) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    reasoningConfig = result.value,
                    reasoningLoading = false,
                )
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    reasoningLoading = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    fun saveReasoning(model: AIModel, key: String, value: String?) {
        if (_uiState.value.reasoningSaving) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reasoningSaving = true)
            val baseUrl = currentBaseUrl()
            if (baseUrl == null) {
                _uiState.value = _uiState.value.copy(
                    reasoningSaving = false,
                    error = UiText.Res(R.string.chat_no_server),
                )
                return@launch
            }
            val current = _uiState.value.reasoningConfig ?: ReasoningConfig()
            val merged = current.raw.toMutableMap()
            if (value.isNullOrBlank()) merged.remove(key) else merged[key] = value
            when (val result = modelRepository.updateReasoningConfig(baseUrl, model.id, model.providerId.orEmpty(), merged)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                    reasoningConfig = current.copy(
                        effort = if (key == "effort") value else current.effort,
                        reasoningEffort = if (key == "reasoningEffort") value else current.reasoningEffort,
                        thinkingLevel = if (key == "thinkingLevel") value else current.thinkingLevel,
                        raw = merged.toMap(),
                    ),
                    reasoningSaving = false,
                    reasoningSavedTick = _uiState.value.reasoningSavedTick + 1,
                )
                is AnResult.Err -> _uiState.value = _uiState.value.copy(
                    reasoningSaving = false,
                    error = result.error.toUiText(),
                )
            }
        }
    }

    private suspend fun loadMessages() {
        // Fresh topic has no messages; don't query the server.
        if (_uiState.value.activeTopicId == null) {
            _uiState.value = _uiState.value.copy(messages = emptyList(), error = null)
            return
        }
        val baseUrl = currentBaseUrl()
        if (baseUrl == null) {
            _uiState.value = _uiState.value.copy(error = UiText.Res(R.string.chat_no_server))
            return
        }
        when (
            val result = messageRepository.listMessages(
                baseUrl,
                topicId = _uiState.value.activeTopicId,
                agentId = effectiveAgentId(),
            )
        ) {
            is AnResult.Ok -> _uiState.value = _uiState.value.copy(
                messages = result.value,
                error = null,
                streamingSteps = emptyList(),
            )
            is AnResult.Err -> _uiState.value = _uiState.value.copy(error = result.error.toUiText())
        }
    }

    private suspend fun runSend(text: String, fileIds: List<String>) {
        val baseUrl = currentBaseUrl()
        if (baseUrl == null) {
            _uiState.value = _uiState.value.copy(streaming = false, error = UiText.Res(R.string.chat_no_server))
            return
        }
        if (fileIds.isNotEmpty()) {
            // Gateway attachment shape TBD: log fileIds only, don't block sending.
            Log.d("ChatViewModel", "send with ${fileIds.size} file(s); gateway attachment shape TBD")
        }
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            id = "local-$now",
            role = "user",
            content = text,
            createdAt = now,
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMessage,
            streaming = true,
            streamingText = "",
            streamingReasoning = "", streamingReasoningSecs = null,
            error = null,
            pendingFiles = emptyList(),
        )
        activeOperationId = null
        pendingAssistantId = null

        val snapshot = _uiState.value
        val exec = agentApi.execAgent(
            baseUrl,
            prompt = text,
            topicId = snapshot.activeTopicId,
            agentId = execAgentIdOrNull(),
            slug = execSlugOrNull(),
            model = snapshot.activeModel?.id?.takeIf { it.isNotBlank() },
            provider = snapshot.activeModel?.providerId?.takeIf { it.isNotBlank() },
        )
        if (exec is AnResult.Err) {
            _uiState.value = _uiState.value.copy(streaming = false, streamingText = "",
                            streamingReasoning = "", streamingReasoningSecs = null, error = exec.error.toUiText())
            return
        }
        val execOk = (exec as AnResult.Ok).value
        val operationId = execOk.operationId?.takeIf { it.isNotBlank() }
        val token = execOk.token?.takeIf { it.isNotBlank() }
        if (operationId == null || token == null) {
            _uiState.value = _uiState.value.copy(streaming = false, streamingText = "",
                            streamingReasoning = "", streamingReasoningSecs = null, error = UiText.Res(R.string.chat_no_stream_task))
            return
        }
        activeOperationId = operationId
        if (!execOk.assistantMessageId.isNullOrBlank()) {
            pendingAssistantId = execOk.assistantMessageId
        }
        // Adopt server-minted topic so the post-stream reload hits history.
        if (!execOk.topicId.isNullOrBlank() && _uiState.value.activeTopicId == null) {
            _uiState.value = _uiState.value.copy(activeTopicId = execOk.topicId)
        }

        val endpoint =
            when (val cfg = agentApi.globalConfig(baseUrl)) {
                is AnResult.Ok -> cfg.value
                is AnResult.Err -> {
                    _uiState.value = _uiState.value.copy(streaming = false, streamingText = "",
                            streamingReasoning = "", streamingReasoningSecs = null, error = UiText.Res(R.string.chat_gateway_missing))
                    return
                }
            }

        val buffer = StringBuilder()
        val reasoningBuffer = StringBuilder()
        var lastEmit = 0L
        var reasoningStart = 0L
        var reasoningSecs: Double? = null
        fun emit(force: Boolean = false) {
            val now = System.currentTimeMillis()
            // Throttle markdown re-parse to ~12fps; force on completion.
            if (!force && now - lastEmit < 80) return
            lastEmit = now
            _uiState.value = _uiState.value.copy(
                streamingText = buffer.toString(),
                streamingReasoning = reasoningBuffer.toString(),
                streamingReasoningSecs = reasoningSecs,
            )
        }
        try {
            gatewaySocket.stream(endpoint, token, operationId).collect { event ->
                when (event) {
                    is GatewayEvent.StreamChunk -> {
                        if (event.reasoningDelta?.isNotEmpty() == true) {
                            if (reasoningStart == 0L) reasoningStart = System.currentTimeMillis()
                            reasoningBuffer.append(event.reasoningDelta)
                        }
                        if (event.textDelta.isNotEmpty()) {
                            // First answer token freezes the thinking clock.
                            if (reasoningStart != 0L && reasoningSecs == null) {
                                reasoningSecs = (System.currentTimeMillis() - reasoningStart) / 1000.0
                            }
                            buffer.append(event.textDelta)
                        }
                        emit()
                    }
                    is GatewayEvent.StreamStarted -> {
                        if (!event.assistantMessageId.isNullOrBlank()) {
                            pendingAssistantId = event.assistantMessageId
                        }
                        // New text segment: seal the previous buffer as a
                        // prelude step (transitional text ahead of tools).
                        if (buffer.isNotBlank()) {
                            val sealed = StreamSegment(text = buffer.toString().trim())
                            buffer.clear()
                            _uiState.value = _uiState.value.copy(
                                streamingSteps = _uiState.value.streamingSteps + sealed,
                                streamingText = "",
                            )
                        }
                    }
                    is GatewayEvent.StreamEnded -> Unit
                    is GatewayEvent.ToolStarted -> {
                        val sealed = StreamSegment(toolName = event.name, toolDone = false)
                        _uiState.value = _uiState.value.copy(
                            streamingSteps = _uiState.value.streamingSteps + sealed,
                        )
                    }
                    is GatewayEvent.ToolEnded -> {
                        val steps = _uiState.value.streamingSteps
                        val idx = steps.indexOfLast { it.toolName != null && !it.toolDone }
                        if (idx >= 0) {
                            _uiState.value = _uiState.value.copy(
                                streamingSteps = steps.toMutableList().also {
                                    it[idx] = it[idx].copy(toolDone = true)
                                },
                            )
                        }
                    }
                    is GatewayEvent.RunEnded -> {
                        if (reasoningStart != 0L && reasoningSecs == null) {
                            reasoningSecs = (System.currentTimeMillis() - reasoningStart) / 1000.0
                        }
                        emit(force = true)
                        val assistant = ChatMessage(
                            id = pendingAssistantId ?: "asst-$operationId",
                            role = "assistant",
                            content = buffer.toString().trim(),
                            reasoning = reasoningBuffer.toString().trim().takeIf { it.isNotBlank() },
                            reasoningDuration = reasoningSecs,
                            createdAt = System.currentTimeMillis(),
                        )
                        // Keep local steps visible until the server history
                        // reload lands (it carries grouped tool records).
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages + assistant,
                            streaming = false,
                            streamingText = "",
                            streamingReasoning = "", streamingReasoningSecs = null,
                        )
                        activeOperationId = null
                        loadMessages()
                    }
                    is GatewayEvent.RunError -> {
                        val msg =
                            if ("AUTH_EXPIRED" in event.message) {
                                UiText.Res(R.string.chat_session_expired_reenter)
                            } else {
                                UiText.rawOrNull(event.message)
                                    ?: UiText.Res(R.string.chat_stream_interrupted)
                            }
                        _uiState.value = _uiState.value.copy(streaming = false, streamingText = "",
                            streamingReasoning = "", streamingReasoningSecs = null, error = msg)
                        activeOperationId = null
                    }
                    is GatewayEvent.Unknown -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _uiState.value = _uiState.value.copy(
                streaming = false,
                streamingText = "",
                streamingReasoning = "", streamingReasoningSecs = null,
                error = UiText.rawOrNull(t.message)
                    ?: UiText.Res(R.string.chat_stream_interrupted),
            )
            activeOperationId = null
        }
    }

    // Contract §2: execAgent takes agentId when id-shaped, else slug.
    // Inbox chat passes slug "inbox".
    private fun execAgentIdOrNull(): String? =
        agentId.takeIf { it.isNotBlank() && !it.equals("inbox", ignoreCase = true) && looksIdShaped(it) }
    private fun execSlugOrNull(): String? =
        when {
            isInboxAgent -> "inbox"
            agentId.isNotBlank() && !looksIdShaped(agentId) -> agentId
            else -> null
        }
    private fun effectiveAgentId(): String? =
        agentId.takeIf { it.isNotBlank() } ?: "inbox"
    private fun looksIdShaped(value: String): Boolean {
        if (value.contains("/") || value.contains(" ")) return false
        if (value.length >= 16) return true
        val hexLike = value.count { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } >= value.length / 2
        return value.contains("-") || value.contains("_") || hexLike
    }
    private suspend fun currentBaseUrl(): String? {
        val baseUrl = runCatching { serverStore.baseUrl.first() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (baseUrl != null) {
            activeBaseUrl = baseUrl
        }
        return baseUrl
    }
}

