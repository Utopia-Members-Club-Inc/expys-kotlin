package com.expys.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Boom : Exception("boom")

private fun messageLines(id: String): List<String> = listOf(
  """data: {"attachments":[],"authorID":"a1","body":"hi",""" +
    """"createdAt":"2026-01-01T00:00:00Z","id":"$id","type":"member"}""",
  "",
)

class StreamTransportTest {
  @Test
  fun decodesADataEventIntoAMessageWithBearerAndAccept() = runTest {
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(200, lines = messageLines("m1"))))
    val transport = makeStreamTransport(http, makeSession())

    val message = transport.streamMessages("c1").first()

    assertEquals("m1", message.id)
    assertEquals("hi", message.body)
    assertEquals("Bearer t0", http.requests[0].headers["Authorization"])
    assertEquals("text/event-stream", http.requests[0].headers["Accept"])
    assertEquals("expys-sdk-kotlin/test", http.requests[0].headers["User-Agent"])
  }

  @Test
  fun skipsHeartbeatLinesAndYieldsOnlyMessages() = runTest {
    val lines = listOf(": heartbeat", "") + messageLines("m1") + listOf(": heartbeat", "")
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(200, lines = lines)))
    val transport = makeStreamTransport(http, makeSession())

    val ids = transport.streamMessages("c1").take(1).toList().map { it.id }
    assertEquals(listOf("m1"), ids)
  }

  @Test
  fun cancellationTearsDownTheSource() = runTest {
    val lines = messageLines("m1") + messageLines("m2")
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(200, lines = lines)))
    val transport = makeStreamTransport(http, makeSession())

    // `first()` cancels the upstream flow after the first message.
    val message = transport.streamMessages("c1").first()
    assertEquals("m1", message.id)
    assertTrue(http.terminated)
  }

  @Test
  fun reconnectsAfterATransientFailureWithBackoff() = runTest {
    val recorder = SleepRecorder()
    val http = FakeStreamingHttpClient(
      listOf(StreamStep.Failure(Boom()), StreamStep.Response(200, lines = messageLines("m1"))),
    )
    val transport = makeStreamTransport(http, makeSession(), recorder)

    val message = transport.streamMessages("c1").first()
    assertEquals("m1", message.id)
    assertEquals(listOf(500L), recorder.delays) // random()=1 -> full ceiling at attempt 0
  }

  @Test
  fun reconnectsOn5xxAndHonorsRetryAfterOn429() = runTest {
    val recorder = SleepRecorder()
    val http = FakeStreamingHttpClient(
      listOf(
        StreamStep.Response(503, lines = emptyList(), keepOpen = false),
        StreamStep.Response(429, headers = mapOf("retry-after" to "2"), keepOpen = false),
        StreamStep.Response(200, lines = messageLines("m1")),
      ),
    )
    val transport = makeStreamTransport(http, makeSession(), recorder)

    val message = transport.streamMessages("c1").first()
    assertEquals("m1", message.id)
    assertEquals(listOf(500L, 2000L), recorder.delays)
  }

  @Test
  fun reconnectsAfterTheServerClosesTheStreamCleanly() = runTest {
    val recorder = SleepRecorder()
    val http = FakeStreamingHttpClient(
      listOf(
        StreamStep.Response(200, lines = listOf(": heartbeat", ""), keepOpen = false),
        StreamStep.Response(200, lines = messageLines("m1")),
      ),
    )
    val transport = makeStreamTransport(http, makeSession(), recorder)

    val message = transport.streamMessages("c1").first()
    assertEquals("m1", message.id)
    assertEquals(listOf(500L), recorder.delays)
  }

  @Test
  fun throwsForbiddenOn403WithoutReconnecting() = runTest {
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(403, keepOpen = false)))
    val transport = makeStreamTransport(http, makeSession())

    val error = assertFailsWith<ExpysException.Api> { transport.streamMessages("c1").first() }
    assertEquals(ApiErrorKind.FORBIDDEN, error.error.kind)
    assertEquals(1, http.requests.size)
  }

  @Test
  fun closesTheResponseAndSurfacesTheEnvelopeOn403() = runTest {
    // The permanent-error path must drain (and thereby close) the response, and
    // the drained body must carry the server's stable code/message into the error.
    val errorLines = listOf("""{"error":{"code":"CONVERSATION_FORBIDDEN","message":"nope"}}""")
    val http = FakeStreamingHttpClient(
      listOf(StreamStep.Response(403, lines = errorLines, keepOpen = false)),
    )
    val transport = makeStreamTransport(http, makeSession())

    val error = assertFailsWith<ExpysException.Api> { transport.streamMessages("c1").first() }

    assertEquals(ApiErrorKind.FORBIDDEN, error.error.kind)
    assertEquals("CONVERSATION_FORBIDDEN", error.error.code)
    assertEquals("nope", error.error.message)
    // Draining the error body ran the line flow to completion, flipping `terminated`
    // (the fake's stand-in for the real engine's `response.use {}` close).
    assertTrue(http.terminated)
  }

  @Test
  fun throwsNotFoundOn404WithoutReconnecting() = runTest {
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(404, keepOpen = false)))
    val transport = makeStreamTransport(http, makeSession())

    val error = assertFailsWith<ExpysException.Api> { transport.streamMessages("c1").first() }
    assertEquals(ApiErrorKind.NOT_FOUND, error.error.kind)
    assertEquals(1, http.requests.size)
  }

  @Test
  fun refreshesOnceOn401ThenResumes() = runTest {
    var refreshes = 0
    val session = makeSession(token = "t0", refresh = {
      refreshes++
      TokenRefresh("t-refreshed")
    })
    val http = FakeStreamingHttpClient(
      listOf(
        StreamStep.Response(401, keepOpen = false),
        StreamStep.Response(200, lines = messageLines("m1")),
      ),
    )
    val transport = makeStreamTransport(http, session)

    val message = transport.streamMessages("c1").first()
    assertEquals("m1", message.id)
    assertEquals("Bearer t0", http.requests[0].headers["Authorization"])
    assertEquals("Bearer t-refreshed", http.requests[1].headers["Authorization"])
    assertEquals(1, refreshes)
  }

  @Test
  fun throwsUnauthorizedWhenRefreshedTokenStill401s() = runTest {
    val session = makeSession(token = "t0", refresh = { TokenRefresh("t-refreshed") })
    val http = FakeStreamingHttpClient(
      listOf(StreamStep.Response(401, keepOpen = false), StreamStep.Response(401, keepOpen = false)),
    )
    val transport = makeStreamTransport(http, session)

    val error = assertFailsWith<ExpysException.Api> { transport.streamMessages("c1").first() }
    assertEquals(ApiErrorKind.UNAUTHORIZED, error.error.kind)
  }

  @Test
  fun throwsUnauthorizedOn401WithNoRefreshConfigured() = runTest {
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(401, keepOpen = false)))
    val transport = makeStreamTransport(http, makeSession())

    val error = assertFailsWith<ExpysException.Api> { transport.streamMessages("c1").first() }
    assertEquals(ApiErrorKind.UNAUTHORIZED, error.error.kind)
  }

  @Test
  fun throwsUnauthorizedWhenTheReactiveRefreshItselfFails() = runTest {
    val session = makeSession(token = "t0", refresh = { throw Boom() })
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(401, keepOpen = false)))
    val transport = makeStreamTransport(http, session)

    val error = assertFailsWith<ExpysException.Api> { transport.streamMessages("c1").first() }
    assertEquals(ApiErrorKind.UNAUTHORIZED, error.error.kind)
  }

  @Test
  fun throwsOnAPermanentNonRetryableStatusWithoutReconnecting() = runTest {
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(400, keepOpen = false)))
    val transport = makeStreamTransport(http, makeSession())

    val error = assertFailsWith<ExpysException.Api> { transport.streamMessages("c1").first() }
    assertEquals(400, error.error.status)
    assertEquals(1, http.requests.size)
  }

  @Test
  fun surfacesADecodingErrorForAMalformedDataEvent() = runTest {
    val http = FakeStreamingHttpClient(
      listOf(StreamStep.Response(200, lines = listOf("data: {not json", ""), keepOpen = false)),
    )
    val transport = makeStreamTransport(http, makeSession())

    assertFailsWith<ExpysException.Decoding> { transport.streamMessages("c1").first() }
  }

  @Test
  fun proactivelyRefreshesBeforeConnecting() = runTest {
    // expiresAtMs = FIXED_NOW makes the token already due for a proactive refresh.
    val session = makeSession(
      token = "t0",
      expiresAtMs = FIXED_NOW,
      refresh = { TokenRefresh("t-proactive") },
    )
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(200, lines = messageLines("m1"))))
    val transport = makeStreamTransport(http, session)

    val message = transport.streamMessages("c1").first()
    assertEquals("m1", message.id)
    assertEquals("Bearer t-proactive", http.requests[0].headers["Authorization"])
  }

  @Test
  fun encodesTheConversationIdInThePath() = runTest {
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(200, lines = messageLines("m1"))))
    val transport = makeStreamTransport(http, makeSession())

    transport.streamMessages("c 1").first()
    assertEquals("https://api.test/v1/conversations/c%201/stream", http.requests[0].url)
  }
}
