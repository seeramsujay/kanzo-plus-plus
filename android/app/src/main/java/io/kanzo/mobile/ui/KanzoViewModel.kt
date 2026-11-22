package io.kanzo.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.kanzo.mobile.data.KanzoApi
import io.kanzo.mobile.data.KanzoTunnel
import io.kanzo.mobile.data.SettingsStore
import io.kanzo.mobile.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

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

    private var sseSource: EventSource? = null

    init {
        viewModelScope.launch {
            combine(settingsStore.serverUrl, settingsStore.bunkerTicket, settingsStore.selectedProject) { url, ticket, proj ->
                Triple(url, ticket, proj)
            }.collectLatest { (url, ticket, proj) ->
                _activeProject.value = proj
                if (ticket.isNotBlank()) {
                    connectBunker(ticket)
                } else if (url.isNotBlank()) {
                    connectDirect(url)
                }
            }
        }
    }

    fun connectDirect(url: String) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = null
            try {
                api.updateEndpoint(url)
                settingsStore.saveServerUrl(url)
                settingsStore.saveBunkerTicket("")
                refreshBoard()
                _connectionState.value = ConnectionState.CONNECTED
                startSse()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Cannot reach board at $url: ${e.message}"
            }
        }
    }

    fun connectBunker(ticket: String) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = null
            try {
                val config = KanzoTunnel.parse(ticket)
                    ?: throw IllegalArgumentException("Invalid Bunker pairing ticket format")
                val endpoint = KanzoTunnel.resolveTunnelUrl(config)
                api.updateEndpoint(endpoint.url, endpoint.token)
                settingsStore.saveBunkerTicket(ticket)
                settingsStore.saveServerUrl(endpoint.url)
                refreshBoard()
                _connectionState.value = ConnectionState.CONNECTED
                startSse()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Failed to decrypt tunnel: ${e.message}"
            }
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

    fun refreshBoard() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val proj = _activeProject.value.takeIf { it != "all" }
                val query = _searchQuery.value.takeIf { it.isNotBlank() }
                val b = api.getBoard(proj, query)
                _board.value = b
                _connectionState.value = ConnectionState.CONNECTED
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun openTaskDetail(taskId: Long) {
        viewModelScope.launch {
            try {
                val detail = api.getTask(taskId)
                _selectedTaskDetail.value = detail
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load task #$taskId: ${e.message}"
            }
        }
    }

    fun closeTaskDetail() {
        _selectedTaskDetail.value = null
    }

    fun createTask(project: String, title: String, description: String? = null) {
        viewModelScope.launch {
            try {
                api.createTask(project, title, description)
                refreshBoard()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create task: ${e.message}"
            }
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

    fun addComment(taskId: Long, body: String) {
        viewModelScope.launch {
            try {
                api.addComment(taskId, body)
                openTaskDetail(taskId)
                refreshBoard()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to add comment: ${e.message}"
            }
        }
    }

    private fun startSse() {
        sseSource?.cancel()
        sseSource = api.startSseListener {
            refreshBoard()
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseSource?.cancel()
    }
}
