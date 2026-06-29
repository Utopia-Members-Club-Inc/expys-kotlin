package com.expys.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorsTest {
  private fun api(error: ExpysException): ApiError = (error as ExpysException.Api).error

  @Test
  fun mapsStatusAndCode() {
    val error = api(mapApiError(401, envelope("UNAUTHORIZED"), null))
    assertEquals(401, error.status)
    assertEquals("UNAUTHORIZED", error.code)
    assertEquals(ApiErrorKind.UNAUTHORIZED, error.kind)
  }

  @Test
  fun conflictPreservesCode() {
    val error = api(mapApiError(409, envelope("REDEMPTION_ALREADY_EXISTS"), null))
    assertEquals(ApiErrorKind.CONFLICT, error.kind)
    assertEquals("REDEMPTION_ALREADY_EXISTS", error.code)
  }

  @Test
  fun rateLimitedCarriesRetryAfter() {
    val error = api(mapApiError(429, envelope("RATE_LIMITED"), 1500))
    assertEquals(ApiErrorKind.RATE_LIMITED, error.kind)
    assertEquals(1500L, error.retryAfterMs)
  }

  @Test
  fun serverForFivexx() {
    assertEquals(ApiErrorKind.SERVER, api(mapApiError(503, "", null)).kind)
  }

  @Test
  fun fallbackWhenNotEnvelope() {
    val error = api(mapApiError(404, "not json", null))
    assertEquals("NOT_FOUND", error.code)
    assertEquals(ApiErrorKind.NOT_FOUND, error.kind)
  }

  @Test
  fun forbiddenValidationAndOtherKinds() {
    assertEquals(ApiErrorKind.FORBIDDEN, api(mapApiError(403, envelope("FORBIDDEN"), null)).kind)
    assertEquals(ApiErrorKind.VALIDATION, api(mapApiError(422, envelope("UNPROCESSABLE_ENTITY"), null)).kind)
    // An unmapped status with no envelope falls back to OTHER + the generic "ERROR" code.
    val other = api(mapApiError(418, "", null))
    assertEquals(ApiErrorKind.OTHER, other.kind)
    assertEquals("ERROR", other.code)
  }
}
