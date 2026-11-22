package io.kanzo.mobile.model

import com.google.gson.annotations.SerializedName

data class Board(
    val changes: String? = null,
    @SerializedName("done_hidden") val doneHidden: Long = 0,
    @SerializedName("hidden_properties") val hiddenProperties: List<String> = emptyList(),
    val projects: List<Project> = emptyList(),
    val statuses: List<String> = listOf("todo", "doing", "review", "done"),
    val tasks: List<Task> = emptyList(),
    val version: String = ""
)

data class Project(
    val id: Long = 0,
    val slug: String = "",
    val name: String = "",
    val repos: List<String> = emptyList(),
    val color: String? = null,
    @SerializedName("created_at") val createdAt: String = "",
    val open: Long = 0,
    val total: Long = 0
)

data class Task(
    val id: Long = 0,
    val project: String = "",
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val url: String = "",
    val human: Boolean = false,
    val status: String = "todo",
    val position: Double = 0.0,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    val comments: Long = 0,
    @SerializedName("waits_on") val waitsOn: List<TaskLink> = emptyList()
)

data class TaskLink(
    val id: Long = 0,
    val project: String = "",
    val title: String = "",
    val status: String = ""
)

data class Comment(
    val id: Long = 0,
    @SerializedName("task_id") val taskId: Long = 0,
    val author: String = "",
    val body: String = "",
    @SerializedName("created_at") val createdAt: String = ""
)

data class TaskDetail(
    val task: Task,
    val comments: List<Comment> = emptyList(),
    @SerializedName("waits_on") val waitsOn: List<TaskLink> = emptyList(),
    val blocks: List<TaskLink> = emptyList()
)

data class NewTaskRequest(
    val project: String,
    val title: String,
    val description: String? = null
)

data class UpdateTaskRequest(
    val title: String? = null,
    val description: String? = null,
    val status: String? = null,
    val project: String? = null,
    val tags: String? = null,
    val human: Boolean? = null,
    val url: String? = null
)

data class MoveTaskRequest(
    val status: String,
    val before: Long? = null
)

data class NewCommentRequest(
    val body: String,
    val images: List<String> = emptyList()
)
