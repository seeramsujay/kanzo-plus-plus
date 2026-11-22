package io.kanzo.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kanzo.mobile.model.Board
import io.kanzo.mobile.theme.KanzoGreen

@Composable
fun BoardScreen(
    board: Board?,
    selectedStatus: String,
    onStatusSelected: (String) -> Unit,
    onTaskClick: (Long) -> Unit,
    onMoveTask: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (board == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = KanzoGreen)
        }
        return
    }

    val statuses = board.statuses
    val tasksByStatus = board.tasks.groupBy { it.status }
    val currentTasks = tasksByStatus[selectedStatus] ?: emptyList()

    Column(modifier = modifier.fillMaxSize()) {
        // Status Column Tabs
        ScrollableTabRow(
            selectedTabIndex = statuses.indexOf(selectedStatus).coerceAtLeast(0),
            edgePadding = 16.dp,
            divider = {},
            indicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            statuses.forEach { status ->
                val count = tasksByStatus[status]?.size ?: 0
                val isSelected = status == selectedStatus
                Tab(
                    selected = isSelected,
                    onClick = { onStatusSelected(status) },
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface
                        ),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = status.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$count",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Column Cards Container
        if (currentTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No tasks in ${selectedStatus.replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp)
            ) {
                items(currentTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onClick = { onTaskClick(task.id) },
                        onMove = { newStatus -> onMoveTask(task.id, newStatus) },
                        availableStatuses = statuses
                    )
                }
            }
        }
    }
}
