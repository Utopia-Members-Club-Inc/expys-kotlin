package com.expys.sdk

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/** The request engine: token attach, proactive + reactive (401) refresh,
 * retry/backoff on 429/5xx honoring Retry-After, idempotency-key passthrough,
 * and typed-error mapping. Time/randomness is injectable for testing. */
// The constructor injects time/randomness/transport collaborators so retry and
// refresh behaviour is deterministic under test; the parameter count is intentional
// dependency injection, not a data clump.
@Suppress("LongParameterList")
internal class Transport(
  private val baseUrl: String,
  private val session: ExpysSession,
  private val http: HttpClient,
  private val maxRetries: Int,
  private val userAgent: String,
  private val sleep: suspend (Long) -> Unit,
  private val now: () -> Long,
  private val random: () -> Double,
) {
  // execute() is a single cohesive retry/refresh state machine: proactive refresh,
  // send-with-retry on transport failure, reactive 401 refresh, and Retry-After
  // backoff. Splitting it would scatter one control flow across helpers and obscure
  // it, so the cyclomatic-complexity and throws-count limits are waived here.
  @Suppress("CyclomaticComplexMethod", "ThrowsCount")
  suspend fun execute(
    method: String,
    path: String,
    query: Map<String, String?> = emptyMap(),
    body: String? = null,
    idempotencyKey: String? = null,
  ): String {
    if (session.shouldRefreshProactively()) {
      // Best effort: a transient refresh failure must not block a possibly-valid
      // token; the reactive 401 path recovers otherwise. Cancellation still
      // propagates -- it is never swallowed.
      try {
        session.refreshToken()
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // swallow - handled reactively
      }
    }

    val url = buildUrl(path, query)
    var attempt = 0
    var refreshedOn401 = false

    while (true) {
      val token = session.currentToken()
      val request = HttpRequest(method, url, buildHeaders(token, body, idempotencyKey), body)

      val response = try {
        http.send(request)
      } catch (cancellation: CancellationException) {
        // Structured-concurrency cancellation must propagate, not be retried or
        // remapped to a NetworkError.
        throw cancellation
      } catch (error: Exception) {
        if (attempt < maxRetries) {
          sleep(backoffDelayMs(attempt, random = random))
          attempt++
          continue
        }
        if (isTimeout(error)) throw ExpysException.Timeout
        throw ExpysException.Network(error.message ?: error.toString())
      }

      val status = response.status
      if (status in 200..299) return response.body

      val requestId = response.headers["x-request-id"]

      if (status == 401 && session.canRefresh && !refreshedOn401) {
        refreshedOn401 = true
        try {
          session.refreshToken()
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (_: Exception) {
          throw mapApiError(401, response.body, null, requestId)
        }
        continue
      }

      if (isRetryableStatus(status) && attempt < maxRetries) {
        val retryAfter = parseRetryAfter(response.headers["retry-after"], now())
        sleep(retryAfter ?: backoffDelayMs(attempt, random = random))
        attempt++
        continue
      }

      val retryAfter = if (status == 429) parseRetryAfter(response.headers["retry-after"], now()) else null
      throw mapApiError(status, response.body, retryAfter, requestId)
    }
  }

  private fun buildUrl(path: String, query: Map<String, String?>): String {
    val params = query.entries
      .filter { it.value != null }
      .sortedBy { it.key }
      .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    return if (params.isEmpty()) "$baseUrl$path" else "$baseUrl$path?$params"
  }

  private fun buildHeaders(token: String, body: String?, idempotencyKey: String?): Map<String, String> = buildMap {
    put("Authorization", "Bearer $token")
    put("Accept", "application/json")
    put("User-Agent", userAgent)
    if (body != null) put("Content-Type", "application/json")
    if (idempotencyKey != null) put("Idempotency-Key", idempotencyKey)
  }

  private fun isTimeout(error: Throwable): Boolean = error is SocketTimeoutException || error is InterruptedIOException
}
