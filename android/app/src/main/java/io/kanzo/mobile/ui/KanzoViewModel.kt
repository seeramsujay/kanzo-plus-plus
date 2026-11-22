package io.kanzo.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.kanzo.mobile.data.KanzoApi
import io.kanzo.mobile.data.KanzoTunnel
import io.kanzo.mobile.data.SettingsStore
import io.kanzo.mobile.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class KanzoViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    val api = KanzoApi()

    private val _board = MutableStateFlow<Board?>(null)
    val board: StateFlow<Board?> = _board.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _activeProject = MutableStateFlow("all")
    val activeProject: StateFlow<String> = _activeProject.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTaskDetail = MutableStateFlow<TaskDetail?>(null)
    val selectedTaskDetail: StateFlow<TaskDetail?> = _selectedTaskDetail.asStateFlow()

    private val _isCreatingTask = MutableStateFlow(false)
    val isCreatingTask: StateFlow<Boolean> = _isCreatingTask.asStateFlow()

    private val _isSubmittingComment = MutableStateFlow(false)
    val isSubmittingComment: StateFlow<Boolean> = _isSubmittingComment.asStateFlow()

    private var connectionJob: Job? = null
    private var boardRefreshJob: Job? = null
    private var pollingJob: Job? = null
    private var taskDetailJob: Job? = null
    private var lastChangeToken: String? = null

    init {
        // Observe connection settings separately from project selection
        viewModelScope.launch {
            combine(settingsStore.serverUrl, settingsStore.bunkerTicket) { url, ticket ->
                Pair(url, ticket)
            }.distinctUntilChanged().collectLatest { (url, ticket) ->
                connectionJob?.cancel()
                connectionJob = launch {
                    if (ticket.isNotBlank()) {
                        connectBunkerInternal(ticket)
                    } else if (url.isNotBlank()) {
                        connectDirectInternal(url)
                    }
                }
            }
        }

        viewModelScope.launch {
            settingsStore.selectedProject.distinctUntilChanged().collectLatest { proj ->
                _activeProject.value = proj
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    refreshBoard()
                }
            }
        }
    }

    private fun invalidateConnectionState() {
        pollingJob?.cancel()
        boardRefreshJob?.cancel()
        taskDetailJob?.cancel()
        _board.value = null
        _selectedTaskDetail.value = null
        lastChangeToken = null
    }

    fun connectDirect(url: String) {
        viewModelScope.launch {
            settingsStore.saveServerUrl(url)
            settingsStore.saveBunkerTicket("")
            connectDirectInternal(url)
        }
    }

    private suspend fun connectDirectInternal(url: String) {
        invalidateConnectionState()
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        try {
            api.updateEndpoint(url)
            refreshBoardSuspend()
            _connectionState.value = ConnectionState.CONNECTED
            startPolling()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Cannot reach board at $url: ${e.message}"
        }
    }

    fun connectBunker(ticket: String) {
        viewModelScope.launch {
            settingsStore.saveBunkerTicket(ticket)
            connectBunkerInternal(ticket)
        }
    }

    private suspend fun connectBunkerInternal(ticket: String) {
        invalidateConnectionState()
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        try {
            val config = KanzoTunnel.parse(ticket)
                ?: throw IllegalArgumentException("Invalid Bunker pairing ticket format")
            val endpoint = KanzoTunnel.resolveTunnelUrl(config)
            api.updateEndpoint(endpoint.url, endpoint.token)
            settingsStore.saveServerUrl(endpoint.url)
            refreshBoardSuspend()
            _connectionState.value = ConnectionState.CONNECTED
            startPolling()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Failed to connect tunnel: ${e.message}"
        }
    }

    fun setProject(slug: String) {
        viewModelScope.launch {
            _activeProject.value = slug
            settingsStore.saveSelectedProject(slug)
            refreshBoard()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            refreshBoard()
        }
    }

    suspend fun refreshBoardSuspend() {
        _isLoading.value = true
        try {
            val proj = _activeProject.value.takeIf { it != "all" }
            val query = _searchQuery.value.takeIf { it.isNotBlank() }
            val b = api.getBoard(proj, query)
            _board.value = b
            lastChangeToken = b.changes
        } finally {
            _isLoading.value = false
        }
    }

    fun refreshBoard() {
        boardRefreshJob?.cancel()
        boardRefreshJob = viewModelScope.launch {
            try {
                refreshBoardSuspend()
                _connectionState.value = ConnectionState.CONNECTED
            } catch (e: CancellationException) {
                // Cancelled for a newer refresh request
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    fun openTaskDetail(taskId: Long) {
        taskDetailJob?.cancel()
        taskDetailJob = viewModelScope.launch {
            try {
                val detail = api.getTask(taskId)
                _selectedTaskDetail.value = detail
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load task #$taskId: ${e.message}"
            }
        }
    }

    fun closeTaskDetail() {
        taskDetailJob?.cancel()
        _selectedTaskDetail.value = null
    }

    suspend fun createTaskAsync(project: String, title: String, description: String? = null): Boolean {
        return try {
            api.createTask(project, title, description)
            refreshBoard()
            true
        } catch (e: Exception) {
            _errorMessage.value = "Failed to create task: ${e.message}"
            false
        }
    }

    fun createTaskInScope(
        project: String,
        title: String,
        description: String? = null,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            _isCreatingTask.value = true
            try {
                api.createTask(project, title, description)
                refreshBoard()
                onComplete(true)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create task: ${e.message}"
                onComplete(false)
            } finally {
                _isCreatingTask.value = false
            }
        }
    }

    fun createTask(project: String, title: String, description: String? = null) {
        viewModelScope.launch {
            createTaskAsync(project, title, description)
        }
    }

    fun moveTask(id: Long, newStatus: String) {
        viewModelScope.launch {
            try {
                api.moveTask(id, newStatus)
                refreshBoard()
                if (_selectedTaskDetail.value?.task?.id == id) {
                    openTaskDetail(id)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to move task: ${e.message}"
            }
        }
    }

    suspend fun addCommentAsync(taskId: Long, body: String): Boolean {
        return try {
            val comment = api.addComment(taskId, body)
            taskDetailJob?.cancel()
            _selectedTaskDetail.value?.let { current ->
                if (current.task.id == taskId && current.comments.none { it.id == comment.id }) {
                    _selectedTaskDetail.value = current.copy(
                        comments = current.comments + comment,
                        task = current.task.copy(comments = current.task.comments + 1)
                    )
                }
            }
            refreshBoard()
            true
        } catch (e: Exception) {
            _errorMessage.value = "Failed to add comment: ${e.message}"
            false
        }
    }

    fun addCommentInScope(
        taskId: Long,
        body: String,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            _isSubmittingComment.value = true
            try {
                val comment = api.addComment(taskId, body)
                taskDetailJob?.cancel()
                _selectedTaskDetail.value?.let { current ->
                    if (current.task.id == taskId && current.comments.none { it.id == comment.id }) {
                        _selectedTaskDetail.value = current.copy(
                            comments = current.comments + comment,
                            task = current.task.copy(comments = current.task.comments + 1)
                        )
                    }
                }
                refreshBoard()
                onComplete(true)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to add comment: ${e.message}"
                onComplete(false)
            } finally {
                _isSubmittingComment.value = false
            }
        }
    }

    fun addComment(taskId: Long, body: String) {
        viewModelScope.launch {
            addCommentAsync(taskId, body)
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val changes = api.getChanges()
                    if (lastChangeToken != null && lastChangeToken != changes.changes && boardRefreshJob?.isActive != true) {
                        refreshBoard()
                    }
                    delay(2500)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(5000)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        boardRefreshJob?.cancel()
        taskDetailJob?.cancel()
        connectionJob?.cancel()
    }
}
