package com.thor.core.streaming

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Awaits an OkHttp call as a suspending function.
 *
 * Cancelling the coroutine cancels the HTTP call, which matters here for the
 * same reason it matters to the scraper: leaving a section mid-poll must abort
 * the requests in flight rather than let them run to completion against a PC
 * that is no longer selected.
 *
 * `internal`, and deliberately a second copy of the twenty lines `:data` holds
 * for its own clients. Sharing it would mean putting it in `:core:common`, whose
 * dependants are otherwise pure Compose modules with no business carrying an
 * HTTP client on their classpath. The duplication is boilerplate around a
 * platform API rather than logic that can drift.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                // A failure caused by our own cancellation is not an error.
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        },
    )

    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
}
