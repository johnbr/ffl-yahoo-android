package io.github.johnbr.fflyahoo.data

import io.github.johnbr.fflyahoo.core.client.ConditionalFetcher
import io.github.johnbr.fflyahoo.core.client.Fetcher
import io.github.johnbr.fflyahoo.core.client.HeaderFetcher
import io.github.johnbr.fflyahoo.core.client.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The network, for the engine: Yahoo's anonymous GameChannel endpoints, and for a player's season ESPN's
 * anonymous fantasy API — no cookies, no account, one identifiable User-Agent. OkHttp asks for gzip and
 * decodes it transparently.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .cache(null) // conditional requests are the engine's job, see RelayClient.plays
        .build(),
) : Fetcher, ConditionalFetcher, HeaderFetcher {
    override suspend fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            withContext(Dispatchers.IO) { response.body.string() }
        }
    }

    override suspend fun fetch(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        for ((name, value) in headers) builder.header(name, value)
        return client.newCall(builder.build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            withContext(Dispatchers.IO) { response.body.string() }
        }
    }

    override suspend fun fetch(url: String, since: String?): Pair<String?, String> {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (since != null) builder.header("If-Modified-Since", since)
        return client.newCall(builder.build()).await().use { response ->
            if (response.code == 304) return@use null to (since ?: "")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            withContext(Dispatchers.IO) { response.body.string() } to (response.header("Last-Modified") ?: "")
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
