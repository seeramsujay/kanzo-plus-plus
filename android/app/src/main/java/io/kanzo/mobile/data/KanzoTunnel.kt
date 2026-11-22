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

object KanzoTunnel {
    const val PREFIX = "KANZOBUNKER:"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

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
                        val lastLine = rawBody.lines().filter { it.isNotBlank() }.lastOrNull() ?: return@use

                        val decryptedText = decryptPayload(lastLine.trim(), config.encryptionKeyBase64)
                        if (decryptedText.startsWith("{")) {
                            val json = JsonParser.parseString(decryptedText).asJsonObject
                            val url = json.get("url")?.asString ?: return@use
                            val token = json.get("token")?.asString ?: config.token
                            return@withContext ResolvedEndpoint(url.trimEnd('/'), token)
                        } else if (decryptedText.startsWith("http")) {
                            return@withContext ResolvedEndpoint(decryptedText.trim().trimEnd('/'), config.token)
                        }
                    }
                } catch (e: Exception) {
                    lastEx = e
                }
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
