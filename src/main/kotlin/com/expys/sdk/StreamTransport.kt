package com.expys.sdk

import com.expys.sdk.models.Message
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/** The streaming engine: connects to an SSE endpoint, decodes each event into a
 * model, reconnects with full-jitter backoff on transient failures (network drop
 * / 5xx / 429, honoring Retry-After), refreshes once on a 401, and terminates on
 * a permanent 403/404. Mirrors [Transport]'s policy; time/randomness/transport
 * are injectable for testing. The returned flow is cold: collecting it opens the
 * connection, and cancelling collection tears it down. */
// The constructor injects time/randomness/transport collaborators so the retry
// and refresh behaviour is deterministic under test; the parameter count is
// intentional dependency injection, not a data clump.
@Suppress("LongParameterList")
internal class StreamTransport(
  private val baseUrl: String,
  private val session: ExpysSession,
  private val http: HttpClient,
  private val userAgent: String,
  private val json: Json,
  private val sleep: suspend (Long) -> Unit,
  private val now: () -> Long,
  private val random: () -> Double,
) {
  /** Streams decoded [Message]s from a conversation's SSE endpoint. */
  fun streamMessages(id: String): Flow<Message> = stream("/v1/conversations/${encodePathSegment(id)}/stream")

  // run() is a single cohesive reconnect/refresh state machine; splitting it would
  // scatter one control flow across helpers. The complexity/throws limits are
  // waived here exactly as on the buffered Transport.execute().
  @Suppress("CyclomaticComplexMethod", "ThrowsCount", "NestedBlockDepth")
  private fun stream(path: String): Flow<Message> = flow {
    val url = "$baseUrl$path"
    var attempt = 0
    var refreshedOn401 = false

    while (true) {
      currentCoroutineContext().ensureActive()
      refreshProactivelyIfNeeded()

      val token = session.currentToken()
      val request = HttpRequest("GET", url, buildHeaders(token), null)

      val response = try {
        http.stream(request)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // Transient connection failure: back off and reconnect.
        sleep(backoffDelayMs(attempt, random = random))
        attempt++
        continue
      }

      val status = response.status
      if (status !in 200..299) {
        val requestId = response.headers["x-request-id"]
        // Drain and close the connection BEFORE throwing or reconnecting: the
        // success path closes via the line-flow's `response.use {}`, but the error
        // paths never collect that flow, so we must read (and thereby close) here.
        // Reading the small error body also lets the typed error carry the
        // server's envelope code/message, matching the buffered Transport and TS.
        val errorBody = readErrorBody(response.lines)
        when {
          status == 403 || status == 404 -> throw mapApiError(status, errorBody, null, requestId)
          status == 401 && session.canRefresh && !refreshedOn401 -> {
            refreshedOn401 = true
            try {
              session.refreshToken()
            } catch (cancellation: CancellationException) {
              throw cancellation
            } catch (_: Exception) {
              throw mapApiError(401, errorBody, null, requestId)
            }
            continue // reconnect immediately with the refreshed token
          }
          status == 401 -> throw mapApiError(401, errorBody, null, requestId)
          isRetryableStatus(status) -> {
            val retryAfter = if (status == 429) parseRetryAfter(response.headers["retry-after"], now()) else null
            sleep(retryAfter ?: backoffDelayMs(attempt, random = random))
            attempt++
            continue
          }
          else -> throw mapApiError(status, errorBody, null, requestId)
        }
      }

      // A successful connection resets the backoff sequence and the 401 budget.
      attempt = 0
      refreshedOn401 = false
      parseSseEvents(response.lines).collect { payload -> emit(decode(json, payload)) }

      // The server closed the stream cleanly; back off and reconnect.
      sleep(backoffDelayMs(attempt, random = random))
      attempt++
    }
  }

  /** Drains the error response's line flow (a small JSON envelope) and rejoins it
   * into the raw body. Collecting the flow runs the engine's `response.use {}`,
   * which closes the underlying connection - so this both surfaces the server's
   * code/message and prevents a connection leak on the non-2xx paths. A drain
   * failure (the body never arrives) must not mask the API error, so it falls back
   * to an empty body and lets [mapApiError] use the status-derived defaults. */
  private suspend fun readErrorBody(lines: Flow<String>): String = try {
    lines.toList().joinToString("\n")
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (_: Exception) {
    ""
  }

  private suspend fun refreshProactivelyIfNeeded() {
    if (!session.shouldRefreshProactively()) return
    try {
      session.refreshToken()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      // swallow - recovered reactively on a 401
    }
  }

  private fun buildHeaders(token: String): Map<String, String> = buildMap {
    put("Authorization", "Bearer $token")
    put("Accept", "text/event-stream")
    put("User-Agent", userAgent)
  }

  private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
