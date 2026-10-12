package cc.jaxy.anlobehub.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.jaxy.anlobehub.core.common.result.AnResult
import cc.jaxy.anlobehub.core.data.chat.ChatTopic
import cc.jaxy.anlobehub.core.data.chat.TopicRepository
import cc.jaxy.anlobehub.core.data.session.ServerStore
import cc.jaxy.anlobehub.core.designsystem.text.UiText
import cc.jaxy.anlobehub.core.designsystem.text.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class TopicHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val topicRepository: TopicRepository,
) : ViewModel() {

    val agentId: String = savedStateHandle.get<String>("agentId").orEmpty()

    data class UiState(
        val topics: List<ChatTopic> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val endReached: Boolean = false,
        val error: UiText? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var page = 1

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.value = UiState(loading = true)
            page = 1
            loadPage(1, initial = true)
        }
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.loading || s.loadingMore || s.endReached) return
        viewModelScope.launch {
            _uiState.value = s.copy(loadingMore = true, error = null)
            loadPage(page + 1, initial = false)
        }
    }

    fun deleteTopic(id: String) {
        viewModelScope.launch {
            val baseUrl = currentBaseUrl() ?: return@launch
            // Optimistic removal; failure restores via refresh.
            val kept = _uiState.value.topics
            _uiState.value = _uiState.value.copy(
                topics = kept.filterNot { it.id == id },
            )
            when (topicRepository.deleteTopic(baseUrl, id)) {
                is AnResult.Ok -> Unit
                is AnResult.Err -> {
                    _uiState.value = _uiState.value.copy(topics = kept)
                    refresh()
                }
            }
        }
    }

    private suspend fun loadPage(page: Int, initial: Boolean) {
        val baseUrl = currentBaseUrl()
        if (baseUrl == null) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                loadingMore = false,
                error = UiText.Res(R.string.chat_no_server),
            )
            return
        }
        when (
            val result = topicRepository.pageTopics(
                baseUrl = baseUrl,
                page = page,
                pageSize = PAGE_SIZE,
                agentId = agentId.takeIf { it.isNotBlank() },
            )
        ) {
            is AnResult.Ok -> {
                val items = result.value.items
                val total = result.value.total
                val merged = if (initial) items else _uiState.value.topics + items
                val end = items.size < PAGE_SIZE ||
                    (total != null && merged.size >= total)
                this.page = page
                _uiState.value = _uiState.value.copy(
                    topics = merged,
                    loading = false,
                    loadingMore = false,
                    endReached = end,
                    error = null,
                )
            }
            is AnResult.Err -> _uiState.value = _uiState.value.copy(
                loading = false,
                loadingMore = false,
                error = result.error.toUiText(),
            )
        }
    }

    private suspend fun currentBaseUrl(): String? =
        runCatching { serverStore.baseUrl.first() }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val PAGE_SIZE = 20
    }
}
