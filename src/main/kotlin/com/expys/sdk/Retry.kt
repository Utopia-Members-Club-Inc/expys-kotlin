package com.expys.sdk

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.pow

/** The honored Retry-After is bounded so a malformed/hostile value cannot become
 * an effectively unbounded delay. The server's rate-limit window is 60s, so this
 * ceiling never clips a legitimate value while capping pathological ones. The
 * three SDKs share this bound for behavioural parity. */
private const val MAX_RETRY_AFTER_SECONDS = 300L

/** Parses a Retry-After header (RFC 7231: delta-seconds or HTTP-date) into ms to
 * wait relative to [nowMs]. Returns null when absent/unparseable; clamps to
 * [0, MAX_RETRY_AFTER_SECONDS * 1000]. */
internal fun parseRetryAfter(value: String?, nowMs: Long): Long? {
  val trimmed = value?.trim()
  if (trimmed.isNullOrEmpty()) return null
  if (trimmed.all { it.isDigit() }) {
    // A value beyond Long range parses as null; treat it as the max bound rather
    // than dropping the header, then clamp so the *1000 can neither overflow nor
    // produce an unbounded delay.
    val seconds = trimmed.toLongOrNull() ?: MAX_RETRY_AFTER_SECONDS
    return seconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * 1000
  }
  return runCatching {
    val date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
    (date.toInstant().toEpochMilli() - nowMs).coerceIn(0, MAX_RETRY_AFTER_SECONDS * 1000)
  }.getOrNull()
}

/** Whether a status warrants a retry: 429 and any 5xx. */
internal fun isRetryableStatus(status: Int): Boolean = status == 429 || status >= 500

/** Full-jitter exponential backoff in ms: uniformly random in
 * [0, min(cap, base * 2^attempt)]. [random] returns [0, 1) and is injectable. */
internal fun backoffDelayMs(attempt: Int, baseMs: Long = 500, capMs: Long = 10_000, random: () -> Double): Long {
  val ceiling = min(capMs.toDouble(), baseMs.toDouble() * 2.0.pow(attempt))
  return (random() * ceiling).toLong()
}
