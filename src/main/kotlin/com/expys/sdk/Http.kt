package com.expys.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A single HTTP request as seen by an [HttpClient]. */
public data class HttpRequest(
  /** HTTP method (`GET`, `POST`, ...). */
  val method: String,
  /** Fully-qualified request URL, including any query string. */
  val url: String,
  /** Request headers (Authorization, User-Agent, Idempotency-Key, ...). */
  val headers: Map<String, String>,
  /** Request body, or null for a bodyless method. */
  val body: String?,
)

/** A single HTTP response as returned by an [HttpClient]. */
public data class HttpResponse(
  /** HTTP status code. */
  val status: Int,
  /** Response headers, keyed by lowercased name. */
  val headers: Map<String, String>,
  /** Raw response body. */
  val body: String,
)

/** A streaming HTTP response: the status, headers, and a cold flow of body lines.
 * The body is delivered as already-split UTF-8 lines (the SSE byte framing is the
 * transport's concern); cancelling collection of [lines] closes the connection. */
public data class StreamingResponse(
  /** HTTP status code. */
  val status: Int,
  /** Response headers, keyed by lowercased name. */
  val headers: Map<String, String>,
  /** Cold flow of body lines; collect it to read the stream. */
  val lines: Flow<String>,
)

/** Abstraction over the HTTP layer so the request engine is testable with a fake.
 * Inject a custom implementation via [ExpysClient.create] to add instrumentation,
 * metrics, or a different transport. */
public interface HttpClient {
  /** Sends [request] and returns the response. */
  public suspend fun send(request: HttpRequest): HttpResponse

  /** Opens [request] as a streaming connection, exposing the body as a line flow.
   * The default throws, so existing [HttpClient]s keep compiling; the OkHttp
   * engine overrides it. Only `streamMessages` uses this path. */
  public suspend fun stream(request: HttpRequest): StreamingResponse =
    throw ExpysException.NotConfigured("This HttpClient does not support streaming")
}

private val jsonMediaType = "application/json".toMediaType()

/** Default HTTP layer backed by OkHttp, executed on the IO dispatcher. */
public class OkHttpEngine(private val client: OkHttpClient = OkHttpClient()) : HttpClient {
  override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(request.url)
    request.headers.forEach { (name, value) -> builder.header(name, value) }
    builder.method(request.method, request.body?.toRequestBody(jsonMediaType))
    client.newCall(builder.build()).execute().use { response ->
      HttpResponse(response.code, lowercasedHeaders(response), response.body?.string() ?: "")
    }
  }

  override suspend fun stream(request: HttpRequest): StreamingResponse = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(request.url)
    request.headers.forEach { (name, value) -> builder.header(name, value) }
    builder.method(request.method, request.body?.toRequestBody(jsonMediaType))
    // The response (and its body source) is held open and read lazily by the
    // returned flow; it is closed when collection completes or is cancelled.
    val response = client.newCall(builder.build()).execute()
    val lines = flow {
      response.use {
        val source = it.body?.source()
        while (source != null) {
          val line = source.readUtf8Line() ?: break
          emit(line)
        }
      }
    }.flowOn(Dispatchers.IO)
    StreamingResponse(response.code, lowercasedHeaders(response), lines)
  }

  private fun lowercasedHeaders(response: okhttp3.Response): Map<String, String> =
    response.headers.names().associate { name -> name.lowercase() to (response.header(name) ?: "") }
}
