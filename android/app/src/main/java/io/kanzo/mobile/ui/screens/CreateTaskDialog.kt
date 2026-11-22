package io.kanzo.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.kanzo.mobile.model.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskDialog(
    projects: List<Project>,
    defaultProject: String,
    onDismiss: () -> Unit,
    onCreate: (project: String, title: String, description: String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedProject by remember {
        mutableStateOf(if (defaultProject != "all") defaultProject else projects.firstOrNull()?.slug ?: "main")
    }
    var expandedProjectDropdown by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "New Task",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Project Selector
                ExposedDropdownMenuBox(
                    expanded = expandedProjectDropdown,
                    onExpandedChange = { expandedProjectDropdown = !expandedProjectDropdown }
                ) {
                    OutlinedTextField(
                        value = "Project: $selectedProject",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProjectDropdown) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedProjectDropdown,
                        onDismissRequest = { expandedProjectDropdown = false }
                    ) {
                        projects.forEach { proj ->
                            DropdownMenuItem(
                                text = { Text("${proj.name} (${proj.slug})") },
                                onClick = {
                                    selectedProject = proj.slug
                                    expandedProjectDropdown = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                onCreate(selectedProject, title.trim(), description.trim().takeIf { it.isNotEmpty() })
                            }
                        },
                        enabled = title.isNotBlank()
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}
