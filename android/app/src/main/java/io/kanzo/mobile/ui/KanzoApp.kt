package io.kanzo.mobile.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kanzo.mobile.theme.KanzoGreen
import io.kanzo.mobile.ui.screens.*
import kotlinx.coroutines.launch

enum class ViewMode {
    BOARD,
    LIST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanzoApp(viewModel: KanzoViewModel) {
    val board by viewModel.board.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()
    val selectedTaskDetail by viewModel.selectedTaskDetail.collectAsState()

    var viewMode by remember { mutableStateOf(ViewMode.BOARD) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.clearErrorMessage()
        }
    }

    val statuses = board?.statuses ?: listOf("todo", "doing", "review", "done")
    var selectedStatus by remember(statuses) {
        mutableStateOf(statuses.firstOrNull { it == "todo" } ?: statuses.firstOrNull() ?: "todo")
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(280.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Projects",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    NavigationDrawerItem(
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("All Projects", fontWeight = if (activeProject == "all") FontWeight.Bold else FontWeight.Normal)
                                val totalOpen = board?.projects?.sumOf { it.open } ?: 0
                                Text(
                                    text = "$totalOpen",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        selected = activeProject == "all",
                        onClick = {
                            viewModel.setProject("all")
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(10.dp)
                    )

                    board?.projects?.forEach { proj ->
                        NavigationDrawerItem(
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(proj.name, fontWeight = if (activeProject == proj.slug) FontWeight.Bold else FontWeight.Normal)
                                    Text(
                                        text = "${proj.open}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            selected = activeProject == proj.slug,
                            onClick = {
                                viewModel.setProject(proj.slug)
                                coroutineScope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        if (isSearchActive) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = {
                                    searchText = it
                                    viewModel.setSearchQuery(it)
                                },
                                placeholder = { Text("Filter (e.g. tag:bug, is:human)", fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        isSearchActive = false
                                        searchText = ""
                                        viewModel.setSearchQuery("")
                                    }) {
                                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close search")
                                    }
                                }
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { coroutineScope.launch { drawerState.open() } }
                            ) {
                                Text(
                                    text = "Kanzo",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = KanzoGreen
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                val currentProjectName = if (activeProject == "all") "All" else {
                                    board?.projects?.firstOrNull { it.slug == activeProject }?.name ?: activeProject
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = currentProjectName,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Projects",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        if (!isSearchActive) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = "Search & Filter")
                            }

                            // View Mode Toggle (Board vs List)
                            IconButton(onClick = {
                                viewMode = if (viewMode == ViewMode.BOARD) ViewMode.LIST else ViewMode.BOARD
                            }) {
                                Icon(
                                    imageVector = if (viewMode == ViewMode.BOARD) Icons.Default.ViewList else Icons.Default.ViewWeek,
                                    contentDescription = "Switch View"
                                )
                            }
                        }

                        // Fortinet / Pinggy Tunnel connection indicator pill
                        val (dotColor, statusText) = when (connectionState) {
                            ConnectionState.CONNECTED -> Pair(KanzoGreen, "Pinggy 443")
                            ConnectionState.CONNECTING -> Pair(Color(0xFFFCC419), "Tunneling...")
                            ConnectionState.ERROR -> Pair(MaterialTheme.colorScheme.error, "Offline")
                            ConnectionState.DISCONNECTED -> Pair(Color.Gray, "Offline")
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .clickable { showConnectionDialog = true }
                                .padding(end = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        IconButton(onClick = { viewModel.refreshBoard() }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Board")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = KanzoGreen,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "New Task")
                }
            }
        ) { padding ->
            AnimatedContent(
                targetState = viewMode,
                modifier = Modifier.padding(padding),
                label = "ViewModeTransition"
            ) { mode ->
                when (mode) {
                    ViewMode.BOARD -> {
                        BoardScreen(
                            board = board,
                            selectedStatus = selectedStatus,
                            onStatusSelected = { selectedStatus = it },
                            onTaskClick = { taskId -> viewModel.openTaskDetail(taskId) },
                            onMoveTask = { taskId, newStatus -> viewModel.moveTask(taskId, newStatus) }
                        )
                    }
                    ViewMode.LIST -> {
                        ListScreen(
                            board = board,
                            onTaskClick = { taskId -> viewModel.openTaskDetail(taskId) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTaskDialog(
            projects = board?.projects ?: emptyList(),
            defaultProject = activeProject,
            onDismiss = { showCreateDialog = false },
            onCreate = { proj, title, desc ->
                viewModel.createTaskAsync(proj, title, desc)
            }
        )
    }

    selectedTaskDetail?.let { detail ->
        TaskDetailDialog(
            taskDetail = detail,
            availableStatuses = statuses,
            onDismiss = { viewModel.closeTaskDetail() },
            onMove = { newStatus -> viewModel.moveTask(detail.task.id, newStatus) },
            onAddComment = { body -> viewModel.addCommentAsync(detail.task.id, body) }
        )
    }

    if (showConnectionDialog) {
        ConnectionDialog(
            connectionState = connectionState,
            errorMessage = errorMessage,
            currentUrl = viewModel.api.getBaseUrl(),
            onDismiss = { showConnectionDialog = false },
            onConnectDirect = { url ->
                viewModel.connectDirect(url)
                showConnectionDialog = false
            },
            onConnectBunker = { ticket ->
                viewModel.connectBunker(ticket)
                showConnectionDialog = false
            }
        )
    }
}
