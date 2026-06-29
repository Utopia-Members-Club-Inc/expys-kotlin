package com.expys.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Coarse category derived from the HTTP status, for ergonomic handling. */
public enum class ApiErrorKind {
  /** HTTP 401: the member token is missing, invalid, or expired. */
  UNAUTHORIZED,

  /** HTTP 403: the token is valid but not allowed to perform the request. */
  FORBIDDEN,

  /** HTTP 404: the requested resource does not exist. */
  NOT_FOUND,

  /** HTTP 409: the request conflicts with current state (e.g. already redeemed). */
  CONFLICT,

  /** HTTP 422: the request was well-formed but failed validation. */
  VALIDATION,

  /** HTTP 429: rate limited; see [ApiError.retryAfterMs]. */
  RATE_LIMITED,

  /** HTTP 5xx: a server-side error. */
  SERVER,

  /** Any other non-2xx status without a more specific category. */
  OTHER,
}

/** A non-2xx API response, carrying the stable envelope [code]. */
public data class ApiError(
  /** HTTP status code of the response. */
  val status: Int,
  /** Stable, machine-readable envelope code (e.g. `REDEMPTION_ALREADY_EXISTS`). */
  val code: String,
  /** Human-readable message from the error envelope, or a generated fallback. */
  val message: String,
  /** Milliseconds to wait before retrying, parsed from `Retry-After` on a 429. */
  val retryAfterMs: Long?,
  /** Server-assigned correlation id from the `x-request-id` response header, when
   * present. Quote it to support to trace the failure in the server logs. */
  val requestId: String? = null,
) {
  /** Coarse [ApiErrorKind] derived from [status] for ergonomic `when` handling. */
  public val kind: ApiErrorKind
    get() = when {
      status == 401 -> ApiErrorKind.UNAUTHORIZED
      status == 403 -> ApiErrorKind.FORBIDDEN
      status == 404 -> ApiErrorKind.NOT_FOUND
      status == 409 -> ApiErrorKind.CONFLICT
      status == 422 -> ApiErrorKind.VALIDATION
      status == 429 -> ApiErrorKind.RATE_LIMITED
      status >= 500 -> ApiErrorKind.SERVER
      else -> ApiErrorKind.OTHER
    }
}

/** Every error thrown by the SDK. */
public sealed class ExpysException(message: String) : Exception(message) {
  /**
   * A non-2xx API response; inspect [error] for the status, code, and kind.
   * @property error the structured API error returned by the server.
   */
  public data class Api(val error: ApiError) : ExpysException(error.message)

  /**
   * A transport-level failure (connection reset, DNS, TLS, ...) after retries.
   * @property detail a description of the underlying transport failure.
   */
  public data class Network(val detail: String) : ExpysException(detail)

  /** The request exceeded the configured timeout. */
  public data object Timeout : ExpysException("Request timed out")

  /**
   * The response body could not be decoded into the expected model.
   * @property detail a description of the decoding failure.
   */
  public data class Decoding(val detail: String) : ExpysException(detail)

  /**
   * An operation required configuration that was not provided (e.g. a refresh hook).
   * @property detail a description of the missing configuration.
   */
  public data class NotConfigured(val detail: String) : ExpysException(detail)
}

@Serializable
private data class ErrorEnvelope(val error: Body) {
  @Serializable data class Body(val code: String, val message: String)
}

private val envelopeJson = Json { ignoreUnknownKeys = true }

private val statusCodeDefaults = mapOf(
  400 to "BAD_REQUEST",
  401 to "UNAUTHORIZED",
  403 to "FORBIDDEN",
  404 to "NOT_FOUND",
  409 to "CONFLICT",
  413 to "PAYLOAD_TOO_LARGE",
  422 to "UNPROCESSABLE_ENTITY",
  429 to "RATE_LIMITED",
  500 to "INTERNAL",
)

/** Maps an HTTP status + response body to a typed [ExpysException.Api],
 * preserving the envelope code when present. [requestId] is the `x-request-id`
 * response header when present. */
internal fun mapApiError(status: Int, body: String, retryAfterMs: Long?, requestId: String? = null): ExpysException {
  val envelope = runCatching { envelopeJson.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
  val code = envelope?.error?.code ?: statusCodeDefaults[status] ?: "ERROR"
  val message = envelope?.error?.message ?: "Request failed with status $status"
  return ExpysException.Api(ApiError(status, code, message, retryAfterMs, requestId))
}
