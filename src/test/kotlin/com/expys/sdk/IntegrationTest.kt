package com.expys.sdk

import com.expys.sdk.models.CreateRedemptionRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-to-end coverage of the real OkHttp transport (OkHttpEngine + the create()
 * factory) against a local mock server: request shaping, the success and error
 * envelope cycle, and cursor pagination. */
class IntegrationTest {
  private lateinit var server: MockWebServer

  @BeforeTest
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @AfterTest
  fun tearDown() {
    server.shutdown()
  }

  private fun client() = ExpysClient.create(
    ExpysConfiguration(token = "t0", baseUrl = server.url("/").toString().trimEnd('/')),
  )

  /** A client whose OkHttp connection pool the test can inspect, to prove the
   * streaming connection is released (not leaked) on an error response. */
  private fun client(pool: ConnectionPool) = ExpysClient.create(
    ExpysConfiguration(token = "t0", baseUrl = server.url("/").toString().trimEnd('/')),
    httpClient = OkHttpEngine(OkHttpClient.Builder().connectionPool(pool).build()),
  )

  @Test
  fun listOffersOverRealTransport() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[],"nextCursor":null}"""))

    val result = client().listOffers(limit = 10)

    assertEquals(0, result.`data`.size)
    val recorded = server.takeRequest()
    assertEquals("GET", recorded.method)
    assertEquals("/v1/offers?limit=10", recorded.path)
    assertEquals("Bearer t0", recorded.getHeader("Authorization"))
    assertTrue(recorded.getHeader("User-Agent")!!.startsWith("expys-sdk-kotlin/"))
  }

  @Test
  fun createRedemptionOverRealTransport() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(201).setBody(
        """{"canceledNote":null,"canceledReason":null,"createdAt":"t","endAt":null,"id":"r1","offer":"off_1",""" +
          """"startAt":null,"status":"OPEN"}""",
      ),
    )

    val redemption = client().createRedemption(CreateRedemptionRequest(offer = "off_1"))

    assertEquals("r1", redemption.id)
    val recorded = server.takeRequest()
    assertEquals("POST", recorded.method)
    assertTrue(recorded.getHeader("Idempotency-Key")!!.isNotEmpty())
  }

  @Test
  fun mapsErrorEnvelopeAndRequestIdOverRealTransport() = runTest {
    server.enqueue(
      MockResponse()
        .setResponseCode(409)
        .setHeader("x-request-id", "req_int")
        .setBody("""{"error":{"code":"REDEMPTION_ALREADY_EXISTS","message":"dupe"}}"""),
    )

    val error = assertFailsWith<ExpysException.Api> {
      client().createRedemption(CreateRedemptionRequest(offer = "off_1"))
    }
    assertEquals(ApiErrorKind.CONFLICT, error.error.kind)
    assertEquals("REDEMPTION_ALREADY_EXISTS", error.error.code)
    assertEquals("req_int", error.error.requestId)
  }

  @Test
  fun streamMessagesOverRealTransport() = runTest {
    // An SSE body over the real OkHttp streaming engine: a heartbeat comment, one
    // message event, then the server closes the connection.
    val body = ": heartbeat\n\n" +
      """data: {"authorID":"a1","body":"hi","createdAt":"t","id":"m1","type":"member"}""" + "\n\n"
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body),
    )

    val message = client().streamMessages("c1").first()

    assertEquals("m1", message.id)
    assertEquals("hi", message.body)
    val recorded = server.takeRequest()
    assertEquals("GET", recorded.method)
    assertEquals("/v1/conversations/c1/stream", recorded.path)
    assertEquals("text/event-stream", recorded.getHeader("Accept"))
  }

  @Test
  fun streamMessagesReleasesTheConnectionOnAnErrorStatus() = runTest {
    // A 403 on the stream endpoint takes the permanent-error path, which never
    // collects the line flow. The transport must still drain and close the
    // response, so the connection returns to the pool idle (not leaked).
    val pool = ConnectionPool()
    server.enqueue(
      MockResponse()
        .setResponseCode(403)
        .setHeader("x-request-id", "req_stream_403")
        .setBody("""{"error":{"code":"CONVERSATION_FORBIDDEN","message":"not your conversation"}}"""),
    )

    val error = assertFailsWith<ExpysException.Api> { client(pool).streamMessages("c1").first() }

    assertEquals(ApiErrorKind.FORBIDDEN, error.error.kind)
    // The server's stable envelope code/message survive (read from the error body),
    // matching the buffered transport rather than a status-derived default.
    assertEquals("CONVERSATION_FORBIDDEN", error.error.code)
    assertEquals("not your conversation", error.error.message)
    assertEquals("req_stream_403", error.error.requestId)
    // The single connection was acquired then released: the pool holds it idle.
    assertEquals(1, pool.connectionCount())
    assertEquals(pool.connectionCount(), pool.idleConnectionCount())
  }

  @Test
  fun paginatesWithCursor() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[],"nextCursor":"c2"}"""))
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[],"nextCursor":null}"""))
    val client = client()

    val page1 = client.listOffers(limit = 2)
    assertEquals("c2", page1.nextCursor)
    val page2 = client.listOffers(limit = 2, cursor = page1.nextCursor)
    assertNull(page2.nextCursor)

    server.takeRequest() // page 1
    val second = server.takeRequest()
    assertTrue(second.path!!.contains("cursor=c2"))
  }
}
