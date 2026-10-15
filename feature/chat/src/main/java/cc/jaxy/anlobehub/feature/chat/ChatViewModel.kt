package cc.jaxy.anlobehub.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.agent.AgentRepository
import cc.jaxy.anlobehub.core.data.chat.AIModel
import cc.jaxy.anlobehub.core.data.chat.ChatAttachment
import cc.jaxy.anlobehub.core.data.chat.ChatCompletionRepository
import cc.jaxy.anlobehub.core.data.chat.ChatMessage
import cc.jaxy.anlobehub.core.data.chat.ChatTopic
import cc.jaxy.anlobehub.core.data.chat.ModelRepository
import cc.jaxy.anlobehub.core.data.chat.MessageRepository
import cc.jaxy.anlobehub.core.data.chat.ReasoningConfig
import cc.jaxy.anlobehub.core.data.chat.TopicRepository
import cc.jaxy.anlobehub.core.data.files.FileRepository
import cc.jaxy.anlobehub.core.data.files.UploadedFile
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val topicRepository: TopicRepository,
    private val modelRepository: ModelRepository,
    private val agentRepository: AgentRepository,
    private val fileRepository: FileRepository,
    private val completionRepository: ChatCompletionRepository,
) : ViewModel() {
    val agentId: String = savedStateHandle.get<String>("agentId") ?: ""
    val agentTitle: String? = savedStateHandle.get<String>("agentTitle")?.takeIf(String::isNotBlank)
    private val initialTopicId = savedStateHandle.get<String>("topicId")?.takeIf(String::isNotBlank)

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val messagesLoading: Boolean = false,
        val streaming: Boolean = false,
        val streamingText: String = "",
        val streamingReasoning: String = "",
        val streamingReasoningSecs: Double? = null,
        val error: UiText? = null,
        val topics: List<ChatTopic> = emptyList(),
        val topicsLoading: Boolean = false,
        val activeTopicId: String? = null,
        val models: List<AIModel> = emptyList(),
        val activeModel: AIModel? = null,
        val modelsLoading: Boolean = false,
        val agentAvatar: String? = null,
        val openingMessage: String? = null,
        val openingQuestions: List<String> = emptyList(),
        val pendingFiles: List<UploadedFile> = emptyList(),
        val uploading: Boolean = false,
        val reasoningConfig: ReasoningConfig? = null,
        val reasoningLoading: Boolean = false,
        val reasoningSaving: Boolean = false,
        val reasoningSavedTick: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    private var temperature: Double? = null
    private var systemPrompt = ""
    private var streamJob: Job? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(modelsLoading = true)
            val modelsDeferred = async { modelRepository.listModels() }
            val configDeferred = async {
                effectiveAgentId()?.let { id -> (agentRepository.getConfig(id) as? AnResult.Ok)?.value }
            }
            val models = (modelsDeferred.await() as? AnResult.Ok)?.value.orEmpty()
            val config = configDeferred.await()
            systemPrompt = config?.systemRole.orEmpty()
            val needTopics = initialTopicId != null || _uiState.value.activeTopicId != null
            val topics = if (needTopics) (topicRepository.listTopics(agentId = effectiveAgentId()) as? AnResult.Ok)?.value.orEmpty() else emptyList()
            val topicId = _uiState.value.activeTopicId?.takeIf { id -> topics.any { it.id == id } }
                ?: initialTopicId?.takeIf { id -> topics.any { it.id == id } }
            temperature = config?.temperature
            val chatModels = models.filter { it.chatSupported && it.enabled == true && it.providerEnabled }
            val model = _uiState.value.activeModel?.takeIf { chosen ->
                chosen.chatSupported && chosen.enabled == true && chosen.providerEnabled &&
                    chatModels.any { it.id == chosen.id && it.providerId == chosen.providerId }
            }
                ?: config?.let { cfg ->
                    cfg.model?.let { id ->
                        chatModels.firstOrNull { it.id == id && (cfg.provider.isNullOrBlank() || it.providerId == cfg.provider) }
                    }
                }
                ?: chatModels.firstOrNull()
            _uiState.value = _uiState.value.copy(topics = topics, activeTopicId = topicId, models = models,
                activeModel = model, modelsLoading = false, agentAvatar = config?.avatar?.takeIf(String::isNotBlank),
                openingMessage = config?.openingMessage?.takeIf(String::isNotBlank),
                openingQuestions = config?.openingQuestions.orEmpty().filter(String::isNotBlank))
            loadMessages()
        }
    }

    fun loadTopics() {
        if (_uiState.value.topicsLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(topicsLoading = true)
            when (val result = topicRepository.listTopics(agentId = effectiveAgentId())) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(topics = result.value, topicsLoading = false)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(topicsLoading = false, error = result.error.toUiText())
            }
        }
    }

    fun newConversation() {
        if (_uiState.value.streaming) stop()
        _uiState.value = _uiState.value.copy(activeTopicId = null, messages = emptyList(), error = null)
    }
    fun selectTopic(id: String?) {
        if (_uiState.value.activeTopicId == id) return
        if (_uiState.value.streaming) stop()
        _uiState.value = _uiState.value.copy(activeTopicId = id, messages = emptyList(), messagesLoading = true, error = null)
        viewModelScope.launch { loadMessages() }
    }
    fun selectModel(model: AIModel) { _uiState.value = _uiState.value.copy(activeModel = model) }
    fun send(text: String, fileIds: List<String> = emptyList()) {
        if (text.isBlank() || _uiState.value.streaming) return
        streamJob?.cancel()
        streamJob = viewModelScope.launch { runSend(text, fileIds) }
    }

    fun attach(bytes: ByteArray, name: String, mime: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(uploading = true, error = null)
            when (val result = fileRepository.uploadFile(name, mime, bytes)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(pendingFiles = _uiState.value.pendingFiles + result.value, uploading = false)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(uploading = false, error = result.error.toUiText())
            }
        }
    }
    fun removePendingFile(file: UploadedFile) { _uiState.value = _uiState.value.copy(pendingFiles = _uiState.value.pendingFiles - file) }

    fun stop() {
        val s = _uiState.value
        if (!s.streaming) return
        streamJob?.cancel()
        streamJob = null
        val text = s.streamingText.trim()
        val reasoning = s.streamingReasoning.trim().takeIf(String::isNotBlank)
        val assistant = text.takeIf(String::isNotBlank)?.let { ChatMessage(id = "assistant-${System.currentTimeMillis()}", role = "assistant", content = it, reasoning = reasoning, createdAt = System.currentTimeMillis()) }
        _uiState.value = s.copy(messages = if (assistant == null) s.messages else s.messages + assistant, streaming = false,
            streamingText = "", streamingReasoning = "", streamingReasoningSecs = null)
        val topicId = s.activeTopicId
        if (topicId != null && assistant != null) viewModelScope.launch { messageRepository.saveMessage(topicId, assistant) }
    }
    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    fun loadReasoning(model: AIModel) {
        if (_uiState.value.reasoningLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reasoningLoading = true)
            when (val r = modelRepository.getReasoningConfig(model.id, model.providerId.orEmpty())) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(reasoningConfig = r.value, reasoningLoading = false)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(reasoningLoading = false, error = r.error.toUiText())
            }
        }
    }
    fun saveReasoning(model: AIModel, key: String, value: String?) {
        if (_uiState.value.reasoningSaving) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reasoningSaving = true)
            val current = _uiState.value.reasoningConfig ?: ReasoningConfig()
            val merged = current.raw.toMutableMap().apply { if (value.isNullOrBlank()) remove(key) else put(key, value) }
            when (val r = modelRepository.updateReasoningConfig(model.id, model.providerId.orEmpty(), merged)) {
                is AnResult.Ok -> _uiState.value = _uiState.value.copy(reasoningConfig = current.copy(
                    effort = if (key == "effort") value else current.effort,
                    reasoningEffort = if (key == "reasoningEffort") value else current.reasoningEffort,
                    thinkingLevel = if (key == "thinkingLevel") value else current.thinkingLevel, raw = merged),
                    reasoningSaving = false, reasoningSavedTick = _uiState.value.reasoningSavedTick + 1)
                is AnResult.Err -> _uiState.value = _uiState.value.copy(reasoningSaving = false, error = r.error.toUiText())
            }
        }
    }

    private suspend fun loadMessages() {
        val topic = _uiState.value.activeTopicId
        if (topic == null) { _uiState.value = _uiState.value.copy(messages = emptyList(), messagesLoading = false); return }
        _uiState.value = _uiState.value.copy(messagesLoading = true)
        when (val r = messageRepository.listMessages(topicId = topic, agentId = effectiveAgentId())) {
            is AnResult.Ok -> _uiState.value = _uiState.value.copy(messages = r.value, messagesLoading = false, error = null)
            is AnResult.Err -> _uiState.value = _uiState.value.copy(messagesLoading = false, error = r.error.toUiText())
        }
    }

    private suspend fun runSend(text: String, fileIds: List<String>) {
        val files = _uiState.value.pendingFiles.filter { it.id in fileIds }
        val attachments = files.map { ChatAttachment(it.id, it.name, it.mime, it.url.orEmpty()) }
        var topicId = _uiState.value.activeTopicId
        if (topicId == null) {
            when (val created = topicRepository.createTopic(text.trim().take(80), agentId = effectiveAgentId())) {
                is AnResult.Ok -> { topicId = created.value; _uiState.value = _uiState.value.copy(activeTopicId = topicId) }
                is AnResult.Err -> { _uiState.value = _uiState.value.copy(error = created.error.toUiText()); return }
            }
        }
        val user = ChatMessage(id = "user-${System.currentTimeMillis()}", role = "user", content = text,
            createdAt = System.currentTimeMillis(), attachments = attachments)
        when (val saved = messageRepository.saveMessage(topicId!!, user)) {
            is AnResult.Err -> { _uiState.value = _uiState.value.copy(error = saved.error.toUiText()); return }
            is AnResult.Ok -> Unit
        }
        val snapshot = _uiState.value
        val history = snapshot.messages + user
        _uiState.value = snapshot.copy(messages = history, streaming = true, streamingText = "", streamingReasoning = "",
            streamingReasoningSecs = null, error = null, pendingFiles = snapshot.pendingFiles - files)
        val model = snapshot.activeModel
        if (model?.providerId.isNullOrBlank() || model?.id.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(streaming = false, error = UiText.Res(R.string.chat_model_required)); return
        }
        val textBuffer = StringBuilder(); val reasoningBuffer = StringBuilder()
        var reasoningStarted = 0L; var reasoningSecs: Double? = null; var lastEmit = 0L
        try {
            completionRepository.stream(model!!.providerId!!, model.id, systemPrompt, history, temperature).collect { chunk ->
                if (chunk.reasoningDelta?.isNotEmpty() == true) { if (reasoningStarted == 0L) reasoningStarted = System.currentTimeMillis(); reasoningBuffer.append(chunk.reasoningDelta) }
                if (chunk.textDelta.isNotEmpty()) {
                    if (reasoningStarted != 0L && reasoningSecs == null) reasoningSecs = (System.currentTimeMillis() - reasoningStarted) / 1000.0
                    textBuffer.append(chunk.textDelta)
                }
                val now = System.currentTimeMillis()
                if (now - lastEmit >= 80) {
                    lastEmit = now; _uiState.value = _uiState.value.copy(streamingText = textBuffer.toString(), streamingReasoning = reasoningBuffer.toString(), streamingReasoningSecs = reasoningSecs)
                }
            }
            if (reasoningStarted != 0L && reasoningSecs == null) reasoningSecs = (System.currentTimeMillis() - reasoningStarted) / 1000.0
            val assistant = ChatMessage(id = "assistant-${System.currentTimeMillis()}", role = "assistant", content = textBuffer.toString().trim(),
                reasoning = reasoningBuffer.toString().trim().takeIf(String::isNotBlank), reasoningDuration = reasoningSecs, createdAt = System.currentTimeMillis())
            if (assistant.content.isNotBlank()) {
                messageRepository.saveMessage(topicId!!, assistant)
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + assistant, streaming = false, streamingText = "", streamingReasoning = "", streamingReasoningSecs = null)
            } else _uiState.value = _uiState.value.copy(streaming = false, streamingText = "", streamingReasoning = "", streamingReasoningSecs = null)
            loadTopics()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val partial = textBuffer.toString().trim()
            if (partial.isNotBlank()) {
                val recoverable = ChatMessage(id = "assistant-${System.currentTimeMillis()}", role = "assistant", content = partial,
                    reasoning = reasoningBuffer.toString().trim().takeIf(String::isNotBlank), createdAt = System.currentTimeMillis(), error = e.message)
                messageRepository.saveMessage(topicId!!, recoverable)
                _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + recoverable)
            }
            _uiState.value = _uiState.value.copy(streaming = false, streamingText = "", streamingReasoning = "", streamingReasoningSecs = null,
                error = UiText.rawOrNull(e.message) ?: UiText.Res(R.string.chat_stream_interrupted))
        }
    }

    private fun effectiveAgentId(): String? = agentId.takeIf(String::isNotBlank)
}
