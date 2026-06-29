package com.expys.sdk

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow

/** A fake HTTP layer that returns queued results in order and records requests. */
class FakeHttpClient(steps: List<Result<HttpResponse>>) : HttpClient {
  private val queue = steps.toMutableList()
  val requests = mutableListOf<HttpRequest>()

  override suspend fun send(request: HttpRequest): HttpResponse {
    requests.add(request)
    return queue.removeAt(0).getOrThrow()
  }
}

/** A scripted streaming step: either a connection failure, or a status + headers
 * + the SSE lines to emit. A 2xx step's line flow stays OPEN after emitting its
 * lines (suspends until cancelled), modelling a real SSE endpoint that holds the
 * connection between messages; set [keepOpen] = false for a clean server close. */
sealed interface StreamStep {
  data class Response(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val lines: List<String> = emptyList(),
    val keepOpen: Boolean = status in 200..299,
  ) : StreamStep

  data class Failure(val error: Throwable) : StreamStep
}

/** A fake streaming HTTP layer that returns scripted [StreamStep]s in order and
 * records requests. The [terminated] flag flips once a stream's flow collector is
 * cancelled or completes, proving teardown. */
class FakeStreamingHttpClient(steps: List<StreamStep>) : HttpClient {
  private val queue = steps.toMutableList()
  val requests = mutableListOf<HttpRequest>()
  var terminated: Boolean = false
    private set

  override suspend fun send(request: HttpRequest): HttpResponse = throw UnsupportedOperationException("streaming fake")

  override suspend fun stream(request: HttpRequest): StreamingResponse {
    requests.add(request)
    val step = if (queue.isEmpty()) StreamStep.Failure(IllegalStateException("exhausted")) else queue.removeAt(0)
    when (step) {
      is StreamStep.Failure -> throw step.error
      is StreamStep.Response -> {
        val lines = flow {
          try {
            step.lines.forEach { emit(it) }
            // A successful, kept-open stream suspends until the collector cancels
            // (real SSE). A closed step finishes so the engine reconnects.
            if (step.keepOpen) awaitCancellation()
          } finally {
            terminated = true
          }
        }
        return StreamingResponse(step.status, step.headers, lines)
      }
    }
  }
}

class SleepRecorder {
  val delays = mutableListOf<Long>()
}

const val FIXED_NOW = 1000L

fun ok(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()): Result<HttpResponse> =
  Result.success(HttpResponse(status, headers, body))

fun fail(error: Throwable): Result<HttpResponse> = Result.failure(error)

fun envelope(code: String, message: String = "msg"): String = """{"error":{"code":"$code","message":"$message"}}"""

internal fun makeSession(
  token: String = "t0",
  refresh: (suspend () -> TokenRefresh)? = null,
  expiresAtMs: Long? = null,
  skewMs: Long = 30_000,
): ExpysSession = ExpysSession(token, expiresAtMs, refresh, skewMs) { FIXED_NOW }

internal fun makeTransport(
  http: HttpClient,
  session: ExpysSession,
  maxRetries: Int = 2,
  recorder: SleepRecorder = SleepRecorder(),
  random: Double = 1.0,
): Transport = Transport(
  baseUrl = "https://api.test",
  session = session,
  http = http,
  maxRetries = maxRetries,
  userAgent = "expys-sdk-kotlin/test",
  sleep = { recorder.delays.add(it) },
  now = { FIXED_NOW },
  random = { random },
)

internal fun makeStreamTransport(
  http: HttpClient,
  session: ExpysSession,
  recorder: SleepRecorder = SleepRecorder(),
  random: Double = 1.0,
): StreamTransport = StreamTransport(
  baseUrl = "https://api.test",
  session = session,
  http = http,
  userAgent = "expys-sdk-kotlin/test",
  json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
  sleep = { recorder.delays.add(it) },
  now = { FIXED_NOW },
  random = { random },
)
