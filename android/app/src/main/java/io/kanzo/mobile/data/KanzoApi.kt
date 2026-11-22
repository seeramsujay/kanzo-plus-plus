package io.kanzo.mobile.data

import com.google.gson.Gson
import io.kanzo.mobile.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

class KanzoApi(
    private var baseUrl: String = "http://10.0.2.2:4242",
    private var bearerToken: String = ""
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun updateEndpoint(url: String, token: String = "") {
        baseUrl = url.trimEnd('/')
        bearerToken = token
    }

    fun getBaseUrl(): String = baseUrl

    private fun newRequestBuilder(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path else "$baseUrl$path"
        val builder = Request.Builder().url(url)
        if (bearerToken.isNotBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        return builder
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = newRequestBuilder("/api/ping").get().build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful && resp.body?.string()?.trim() == "kanzo"
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getBoard(project: String? = null, query: String? = null): Board = withContext(Dispatchers.IO) {
        val params = mutableListOf<String>()
        if (!project.isNullOrBlank() && project != "all") {
            params.add("project=${project}")
        }
        if (!query.isNullOrBlank()) {
            params.add("q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        }
        val queryString = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
        val req = newRequestBuilder("/api/board$queryString").get().build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to load board: ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(body, Board::class.java)
        }
    }

    suspend fun getTask(id: Long): TaskDetail = withContext(Dispatchers.IO) {
        val req = newRequestBuilder("/api/tasks/$id").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to load task #$id: ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(body, TaskDetail::class.java)
        }
    }

    suspend fun createTask(project: String, title: String, description: String? = null): Task =
        withContext(Dispatchers.IO) {
            val requestBody = gson.toJson(NewTaskRequest(project, title, description))
                .toRequestBody(jsonMediaType)
            val req = newRequestBuilder("/api/tasks").post(requestBody).build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Failed to create task: ${resp.code}")
                val body = resp.body?.string() ?: throw IOException("Empty response")
                gson.fromJson(body, Task::class.java)
            }
        }

    suspend fun updateTask(id: Long, patch: UpdateTaskRequest): Task = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(patch).toRequestBody(jsonMediaType)
        val req = newRequestBuilder("/api/tasks/$id").patch(requestBody).build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to update task: ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(body, Task::class.java)
        }
    }

    suspend fun moveTask(id: Long, status: String, before: Long? = null): Task = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(MoveTaskRequest(status, before)).toRequestBody(jsonMediaType)
        val req = newRequestBuilder("/api/tasks/$id/move").post(requestBody).build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to move task: ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(body, Task::class.java)
        }
    }

    suspend fun addComment(taskId: Long, body: String): Comment = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(NewCommentRequest(body)).toRequestBody(jsonMediaType)
        val req = newRequestBuilder("/api/tasks/$taskId/comments").post(requestBody).build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Failed to add comment: ${resp.code}")
            val respBody = resp.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(respBody, Comment::class.java)
        }
    }

    fun startSseListener(onChanged: () -> Unit): EventSource {
        val req = newRequestBuilder("/api/changes").build()
        val factory = EventSources.createFactory(client)
        return factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                onChanged()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // Retry after brief delay
            }
        })
    }
}
