package com.expys.sdk

import kotlinx.coroutines.test.runTest
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransportTest {
  @Test
  fun successGetAttachesBearer() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"ok":true}""")))
    val transport = makeTransport(http, makeSession())

    val body = transport.execute("GET", "/v1/offers")

    assertEquals("""{"ok":true}""", body)
    assertEquals("Bearer t0", http.requests[0].headers["Authorization"])
    assertEquals("application/json", http.requests[0].headers["Accept"])
  }

  @Test
  fun querySortedSkipNull() = runTest {
    val http = FakeHttpClient(listOf(ok("{}")))
    val transport = makeTransport(http, makeSession())

    transport.execute(
      "GET",
      "/v1/offers",
      query = mapOf("limit" to "10", "cursor" to "c1", "extra" to null),
    )

    assertEquals("https://api.test/v1/offers?cursor=c1&limit=10", http.requests[0].url)
  }

  @Test
  fun postHeadersAndBody() = runTest {
    val http = FakeHttpClient(listOf(ok("{}")))
    val transport = makeTransport(http, makeSession())

    transport.execute(
      "POST",
      "/v1/redemptions",
      body = """{"offer":"off_1"}""",
      idempotencyKey = "key-123",
    )

    val request = http.requests[0]
    assertEquals("POST", request.method)
    assertEquals("application/json", request.headers["Content-Type"])
    assertEquals("key-123", request.headers["Idempotency-Key"])
    assertEquals("""{"offer":"off_1"}""", request.body)
  }

  @Test
  fun putSendsBodyAndContentType() = runTest {
    val http = FakeHttpClient(listOf(ok("{}")))
    val transport = makeTransport(http, makeSession())

    transport.execute("PUT", "/v1/members/u1", body = """{"tier":"gold"}""")

    val request = http.requests[0]
    assertEquals("PUT", request.method)
    assertEquals("application/json", request.headers["Content-Type"])
    assertEquals("""{"tier":"gold"}""", request.body)
  }

  @Test
  fun deleteSendsNoBodyOrContentType() = runTest {
    val http = FakeHttpClient(listOf(ok("{}")))
    val transport = makeTransport(http, makeSession())

    transport.execute("DELETE", "/v1/webhooks/wh1")

    val request = http.requests[0]
    assertEquals("DELETE", request.method)
    assertEquals(null, request.body)
    assertEquals(null, request.headers["Content-Type"])
  }

  @Test
  fun retries429HonoringRetryAfter() = runTest {
    val recorder = SleepRecorder()
    val http = FakeHttpClient(
      listOf(
        ok(envelope("RATE_LIMITED"), 429, mapOf("retry-after" to "2")),
        ok("""{"ok":true}"""),
      ),
    )
    val transport = makeTransport(http, makeSession(), recorder = recorder)

    transport.execute("GET", "/v1/offers")

    assertEquals(2, http.requests.size)
    assertEquals(listOf(2000L), recorder.delays)
  }

  @Test
  fun reusesIdempotencyKeyAcrossRetry() = runTest {
    // The server only replays (rather than double-booking) if the retried write
    // carries the same key; assert both attempts send the identical header.
    val http = FakeHttpClient(
      listOf(
        ok(envelope("RATE_LIMITED"), 429, mapOf("retry-after" to "0")),
        ok("{}"),
      ),
    )
    val transport = makeTransport(http, makeSession())

    transport.execute("POST", "/v1/redemptions", body = "{}", idempotencyKey = "key-reuse")

    assertEquals(2, http.requests.size)
    assertEquals("key-reuse", http.requests[0].headers["Idempotency-Key"])
    assertEquals("key-reuse", http.requests[1].headers["Idempotency-Key"])
  }

  @Test
  fun retries5xx() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("INTERNAL"), 503), ok("{}")))
    val transport = makeTransport(http, makeSession())

    transport.execute("GET", "/v1/offers")
    assertEquals(2, http.requests.size)
  }

  @Test
  fun refreshesOn401ThenRetriesWithNewToken() = runTest {
    val session = makeSession(refresh = { TokenRefresh("t1") })
    val http = FakeHttpClient(listOf(ok(envelope("UNAUTHORIZED"), 401), ok("{}")))
    val transport = makeTransport(http, session)

    transport.execute("GET", "/v1/offers")

    assertEquals("Bearer t0", http.requests[0].headers["Authorization"])
    assertEquals("Bearer t1", http.requests[1].headers["Authorization"])
  }

  @Test
  fun conflictThrowsTyped() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("REDEMPTION_ALREADY_EXISTS"), 409)))
    val transport = makeTransport(http, makeSession())

    val error = assertFailsWith<ExpysException.Api> {
      transport.execute("POST", "/v1/redemptions", body = "{}")
    }
    assertEquals(ApiErrorKind.CONFLICT, error.error.kind)
    assertEquals("REDEMPTION_ALREADY_EXISTS", error.error.code)
    assertEquals(1, http.requests.size)
  }

  @Test
  fun surfacesRequestIdHeaderOnError() = runTest {
    val http = FakeHttpClient(
      listOf(ok(envelope("CONFLICT"), 409, mapOf("x-request-id" to "req_kotlin"))),
    )
    val transport = makeTransport(http, makeSession())

    val error = assertFailsWith<ExpysException.Api> {
      transport.execute("POST", "/v1/redemptions", body = "{}")
    }
    assertEquals("req_kotlin", error.error.requestId)
  }

  @Test
  fun exhausted429ThrowsRateLimit() = runTest {
    val http = FakeHttpClient(
      listOf(
        ok(envelope("RATE_LIMITED"), 429, mapOf("retry-after" to "3")),
        ok(envelope("RATE_LIMITED"), 429, mapOf("retry-after" to "3")),
      ),
    )
    val transport = makeTransport(http, makeSession(), maxRetries = 1)

    val error = assertFailsWith<ExpysException.Api> { transport.execute("GET", "/v1/offers") }
    assertEquals(ApiErrorKind.RATE_LIMITED, error.error.kind)
    assertEquals(3000L, error.error.retryAfterMs)
  }

  @Test
  fun networkFailureThrowsAfterRetries() = runTest {
    val http = FakeHttpClient(listOf(fail(RuntimeException("reset")), fail(RuntimeException("reset"))))
    val transport = makeTransport(http, makeSession(), maxRetries = 1)

    assertFailsWith<ExpysException.Network> { transport.execute("GET", "/v1/offers") }
    assertEquals(2, http.requests.size)
  }

  @Test
  fun timeoutMapsToTimeoutError() = runTest {
    val http = FakeHttpClient(listOf(fail(SocketTimeoutException("t"))))
    val transport = makeTransport(http, makeSession(), maxRetries = 0)

    assertFailsWith<ExpysException.Timeout> { transport.execute("GET", "/v1/offers") }
  }

  @Test
  fun propagatesCancellationWithoutSwallowing() = runTest {
    // A CancellationException from the HTTP layer (caller withTimeout / scope
    // cancel) must propagate immediately, never be swallowed into a retry and
    // remapped to a spurious NetworkError. Three steps are queued so a regression
    // that retries would consume more than one before failing.
    val http = FakeHttpClient(
      listOf(
        fail(CancellationException("cancelled")),
        fail(CancellationException("cancelled")),
        fail(CancellationException("cancelled")),
      ),
    )
    val transport = makeTransport(http, makeSession())

    assertFailsWith<CancellationException> { transport.execute("GET", "/v1/offers") }
    assertEquals(1, http.requests.size)
  }

  @Test
  fun proactiveRefreshSwapsTokenBeforeFirstRequest() = runTest {
    // Expiry within the skew window triggers a proactive refresh before the send.
    val session = makeSession(refresh = { TokenRefresh("t1") }, expiresAtMs = FIXED_NOW + 10)
    val http = FakeHttpClient(listOf(ok("{}")))
    val transport = makeTransport(http, session)

    transport.execute("GET", "/v1/offers")

    assertEquals("Bearer t1", http.requests[0].headers["Authorization"])
    assertEquals(1, http.requests.size)
  }

  @Test
  fun reactiveRefreshFailurePropagatesWithoutRetry() = runTest {
    // A 401 triggers one reactive refresh; if it throws, the 401 surfaces and the
    // request is not retried (a failed refresh is never retried).
    val session = makeSession(refresh = { error("refresh down") })
    val http = FakeHttpClient(listOf(ok(envelope("UNAUTHORIZED"), 401)))
    val transport = makeTransport(http, session)

    val error = assertFailsWith<ExpysException.Api> { transport.execute("GET", "/v1/offers") }

    assertEquals(ApiErrorKind.UNAUTHORIZED, error.error.kind)
    assertEquals(1, http.requests.size)
  }

  @Test
  fun proactiveRefreshFailureIsSwallowedThenReactiveRecovers() = runTest {
    // A transient proactive-refresh failure is swallowed; the request 401s, the
    // reactive refresh succeeds, and the retry carries the new token.
    var calls = 0
    val session = makeSession(
      refresh = {
        calls++
        if (calls == 1) error("transient") else TokenRefresh("t2")
      },
      expiresAtMs = FIXED_NOW + 10,
    )
    val http = FakeHttpClient(listOf(ok(envelope("UNAUTHORIZED"), 401), ok("{}")))
    val transport = makeTransport(http, session)

    transport.execute("GET", "/v1/offers")

    assertEquals(2, http.requests.size)
    assertEquals("Bearer t0", http.requests[0].headers["Authorization"])
    assertEquals("Bearer t2", http.requests[1].headers["Authorization"])
  }

  @Test
  fun retries429WithBackoffWhenNoRetryAfterHeader() = runTest {
    // Without a Retry-After header, the 429 retry waits a full-jitter backoff (base 500ms).
    val recorder = SleepRecorder()
    val http = FakeHttpClient(listOf(ok(envelope("RATE_LIMITED"), 429), ok("{}")))
    val transport = makeTransport(http, makeSession(), recorder = recorder, random = 1.0)

    transport.execute("GET", "/v1/offers")

    assertEquals(2, http.requests.size)
    assertEquals(listOf(500L), recorder.delays)
  }
}
