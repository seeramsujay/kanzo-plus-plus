package io.kanzo.mobile.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BunkerConfig(
    val ntfyTopic: String,
    val encryptionKeyBase64: String,
    val token: String,
    val port: Int = 4242
)

data class ResolvedEndpoint(
    val url: String,
    val token: String
)

data class ResolvedPublication(
    val endpoint: ResolvedEndpoint,
    val timestamp: Long
)

object KanzoTunnel {
    const val PREFIX = "KANZOBUNKER:"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    fun validateEndpointUrl(url: String, requireHttps: Boolean = true): String {
        val trimmed = url.trim().trimEnd('/')
        val uri = java.net.URI.create(trimmed)
        val scheme = uri.scheme?.lowercase()
        if (requireHttps) {
            if (scheme != "https") {
                throw SecurityException("Insecure tunnel endpoint: tunnel URLs must use HTTPS (received: $trimmed)")
            }
        } else {
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("Invalid URL scheme: $scheme (must be http or https)")
            }
        }
        return trimmed
    }

    fun parse(raw: String): BunkerConfig? {
        val trimmed = raw.trim()
        val jsonStr = when {
            trimmed.startsWith(PREFIX) -> {
                val encoded = trimmed.removePrefix(PREFIX).trim()
                try {
                    String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    try {
                        String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
                    } catch (_: Exception) {
                        return null
                    }
                }
            }
            trimmed.startsWith("{") && trimmed.contains("ntfy_topic") -> trimmed
            else -> return null
        }

        return try {
            val json = JsonParser.parseString(jsonStr).asJsonObject
            val topic = json.get("ntfy_topic")?.asString ?: return null
            val key = json.get("key")?.asString ?: return null
            val token = json.get("token")?.asString ?: ""
            val port = json.get("port")?.asInt ?: 4242
            BunkerConfig(topic, key, token, port)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveTunnelUrl(config: BunkerConfig, client: OkHttpClient = OkHttpClient()): ResolvedEndpoint =
        withContext(Dispatchers.IO) {
            val servers = listOf(
                "https://ntfy.sh",
                "https://ntfy.adminforge.de"
            )
            val validPublications = mutableListOf<ResolvedPublication>()
            var lastEx: Exception? = null

            for (server in servers) {
                try {
                    val ntfyUrl = "$server/${config.ntfyTopic}/raw?poll=1&since=all"
                    val request = Request.Builder()
                        .url(ntfyUrl)
                        .header("Accept", "text/plain")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val rawBody = response.body?.string() ?: return@use
                        val lines = rawBody.lines().filter { it.isNotBlank() }
                        if (lines.isEmpty()) return@use
                        val lastLine = lines.last()

                        val decryptedText = decryptPayload(lastLine.trim(), config.encryptionKeyBase64)
                        if (decryptedText.startsWith("{")) {
                            val json = JsonParser.parseString(decryptedText).asJsonObject
                            val rawUrl = json.get("url")?.asString ?: return@use
                            val token = json.get("token")?.asString ?: config.token
                            val ts = json.get("timestamp")?.asLong ?: json.get("ts")?.asLong ?: 0L
                            val validatedUrl = validateEndpointUrl(rawUrl, requireHttps = true)
                            validPublications.add(ResolvedPublication(ResolvedEndpoint(validatedUrl, token), ts))
                        } else if (decryptedText.startsWith("http")) {
                            val validatedUrl = validateEndpointUrl(decryptedText, requireHttps = true)
                            validPublications.add(ResolvedPublication(ResolvedEndpoint(validatedUrl, config.token), 0L))
                        }
                    }
                } catch (e: Exception) {
                    lastEx = e
                }
            }

            if (validPublications.isNotEmpty()) {
                // Select the newest publication across both rendezvous servers
                return@withContext validPublications.maxByOrNull { it.timestamp }!!.endpoint
            }
            throw lastEx ?: IllegalStateException("Failed to resolve dynamic tunnel from rendezvous")
        }

    private fun decryptPayload(payloadB64: String, keyB64: String): String {
        val payload = Base64.getDecoder().decode(payloadB64)
        val key = Base64.getDecoder().decode(keyB64)

        if (payload.size <= GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Payload too short for GCM IV")
        }

        val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, StandardCharsets.UTF_8)
    }
}
