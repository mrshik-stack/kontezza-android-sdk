package com.kontezza.sdk

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Lightweight HTTP client with retry and rate-limit handling.
 */
internal class HttpClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val debug: Boolean,
) {
    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 300L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>): T {
        val request = buildRequest(path, "GET", null)
        return executeWithRetry(request, deserializer)
    }

    suspend inline fun <reified B, T> post(
        path: String,
        body: B,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val bodyJson = json.encodeToString(body)
        val request = buildRequest(path, "POST", bodyJson)
        return executeWithRetry(request, deserializer)
    }

    inline fun <reified B> postFireAndForget(path: String, body: B) {
        try {
            val bodyJson = json.encodeToString(body)
            val request = buildRequest(path, "POST", bodyJson)
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    log("Fire-and-forget failed: ${e.message}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            log("Fire-and-forget encode error: ${e.message}")
        }
    }

    // ── Private ──────────────────────────────────────────────

    private fun buildRequest(path: String, method: String, bodyJson: String?): Request {
        val builder = Request.Builder()
            .url("$baseUrl/v1$path")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-Key", apiKey)

        when (method) {
            "GET"  -> builder.get()
            "POST" -> builder.post((bodyJson ?: "").toRequestBody(JSON_MEDIA))
        }
        return builder.build()
    }

    private suspend fun <T> executeWithRetry(
        request: Request,
        deserializer: DeserializationStrategy<T>,
    ): T {
        var lastError: Exception = IOException("Request failed")

        repeat(MAX_RETRIES) { attempt ->
            try {
                val response = client.newCall(request).await()

                if (response.code == 429) {
                    val wait = RETRY_BASE_MS * 2.0.pow(attempt).toLong()
                    log("Rate limited, retry in ${wait}ms")
                    response.close()
                    delay(wait)
                    return@repeat
                }

                val body = response.body?.string() ?: ""
                response.close()

                if (response.code !in 200..299) {
                    throw KontezzaException.HttpError(response.code, body)
                }

                return json.decodeFromString(deserializer, body)
            } catch (e: KontezzaException.HttpError) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    val wait = RETRY_BASE_MS * 2.0.pow(attempt).toLong()
                    log("Request failed (attempt ${attempt + 1}), retry in ${wait}ms")
                    delay(wait)
                }
            }
        }
        throw lastError
    }

    private fun log(message: String) {
        if (debug) android.util.Log.d("KontezzaSDK", message)
    }
}

/**
 * Bridge OkHttp synchronous `execute()` to a suspending call.
 */
private suspend fun okhttp3.Call.await(): okhttp3.Response {
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                cont.resumeWith(Result.success(response))
            }
        })
    }
}

// ──────────────────────────────────────────────────────────────
// Exceptions
// ──────────────────────────────────────────────────────────────

sealed class KontezzaException(message: String) : Exception(message) {
    class HttpError(val statusCode: Int, val body: String) :
        KontezzaException("HTTP $statusCode: $body")

    class NoActiveCampaign :
        KontezzaException("No active campaign of the requested type")

    class NoAdFound :
        KontezzaException("No ad matched the current context")
}
