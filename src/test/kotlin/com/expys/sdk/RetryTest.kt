package com.expys.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryTest {
  @Test
  fun parsesSeconds() {
    assertEquals(2000L, parseRetryAfter("2", FIXED_NOW))
    assertEquals(0L, parseRetryAfter("0", FIXED_NOW))
  }

  @Test
  fun missingOrUnparseable() {
    assertNull(parseRetryAfter(null, FIXED_NOW))
    assertNull(parseRetryAfter("garbage", FIXED_NOW))
  }

  @Test
  fun hugeNumericValueClampsToCeiling() {
    // A value beyond Long range must neither throw nor become an unbounded delay;
    // it clamps to the 300s ceiling (parity with the Swift and TS SDKs).
    assertEquals(300_000L, parseRetryAfter("99999999999999999999", FIXED_NOW))
  }

  @Test
  fun parsesHttpDate() {
    // FIXED_NOW is epoch 1000ms (1970-01-01T00:00:01Z).
    assertEquals(5_000L, parseRetryAfter("Thu, 01 Jan 1970 00:00:06 GMT", FIXED_NOW))
    // A past date clamps to 0.
    assertEquals(0L, parseRetryAfter("Thu, 01 Jan 1970 00:00:00 GMT", FIXED_NOW))
    // A far-future date clamps to the 300s ceiling.
    assertEquals(300_000L, parseRetryAfter("Sat, 01 Jan 2050 00:00:00 GMT", FIXED_NOW))
  }

  @Test
  fun retryableStatuses() {
    assertTrue(isRetryableStatus(429))
    assertTrue(isRetryableStatus(500))
    assertTrue(isRetryableStatus(503))
    assertFalse(isRetryableStatus(400))
    assertFalse(isRetryableStatus(404))
    assertFalse(isRetryableStatus(200))
  }

  @Test
  fun backoffGrowsAndCaps() {
    assertEquals(500L, backoffDelayMs(0, random = { 1.0 }))
    assertEquals(1000L, backoffDelayMs(1, random = { 1.0 }))
    assertEquals(2000L, backoffDelayMs(2, random = { 1.0 }))
    assertEquals(10_000L, backoffDelayMs(20, random = { 1.0 }))
    assertEquals(0L, backoffDelayMs(2, random = { 0.0 }))
  }
}
