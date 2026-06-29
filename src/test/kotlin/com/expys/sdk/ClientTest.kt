package com.expys.sdk

import com.expys.sdk.models.CreateRedemptionRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientTest {
  private fun client(http: HttpClient): ExpysClient =
    ExpysClient.create(ExpysConfiguration(token = "t0", baseUrl = "https://api.test"), http)

  @Test
  fun listOffersIssuesGet() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"data":[],"nextCursor":null}""")))

    val result = client(http).listOffers(limit = 10)

    assertEquals(0, result.`data`.size)
    assertEquals("https://api.test/v1/offers?limit=10", http.requests[0].url)
    assertEquals("GET", http.requests[0].method)
  }

  @Test
  fun createRedemptionAutogeneratesIdempotencyKey() = runTest {
    val json = """{"createdAt":"t","endAt":null,"id":"r1","offer":"off_1","startAt":null,"status":"OPEN"}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val redemption = client(http).createRedemption(CreateRedemptionRequest(offer = "off_1"))

    assertEquals("r1", redemption.id)
    assertEquals("POST", http.requests[0].method)
    val key = http.requests[0].headers["Idempotency-Key"]
    assertNotNull(key)
    assertTrue(key.isNotEmpty())
  }

  @Test
  fun getRedemptionEncodesPath() = runTest {
    val json = """{"createdAt":"t","endAt":null,"id":"r 1","offer":"o","startAt":null,"status":"OPEN"}"""
    val http = FakeHttpClient(listOf(ok(json)))

    client(http).getRedemption("r 1")

    assertEquals("https://api.test/v1/redemptions/r%201", http.requests[0].url)
  }

  @Test
  fun walletIssuesGet() = runTest {
    val json = """{"amountReceived":1.0,"amountSpent":0.0,"balance":1.0,"currency":{"name":"USD","symbol":"X"}}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val wallet = client(http).wallet()

    assertEquals(1.0, wallet.balance)
    assertEquals("https://api.test/v1/wallet", http.requests[0].url)
  }

  @Test
  fun eligibilityIssuesGetWithExternalUser() = runTest {
    val json = """{"tier":"GOLD","wallet":{"amountReceived":0.0,"amountSpent":0.0,""" +
      """"balance":0.0,"currency":{"name":"USD","symbol":"X"}}}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = client(http).eligibility(externalUserID = "u1")

    assertEquals("GOLD", result.tier)
    assertEquals("https://api.test/v1/eligibility?externalUserID=u1", http.requests[0].url)
  }

  @Test
  fun createRedemptionRespectsIdempotencyOverride() = runTest {
    val json = """{"createdAt":"t","endAt":null,"id":"r1","offer":"off_1","startAt":null,"status":"OPEN"}"""
    val http = FakeHttpClient(listOf(ok(json)))

    client(http).createRedemption(CreateRedemptionRequest(offer = "off_1"), idempotencyKey = "my-key")

    assertEquals("my-key", http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun decodingFailureThrowsDecoding() = runTest {
    val http = FakeHttpClient(listOf(ok("not json")))

    assertFailsWith<ExpysException.Decoding> { client(http).listOffers() }
  }

  @Test
  fun createRedemptionSurfacesConflict() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("REDEMPTION_ALREADY_EXISTS"), status = 409)))

    val error = assertFailsWith<ExpysException.Api> {
      client(http).createRedemption(CreateRedemptionRequest(offer = "off_1"))
    }
    assertEquals(ApiErrorKind.CONFLICT, error.error.kind)
    assertEquals("REDEMPTION_ALREADY_EXISTS", error.error.code)
    assertEquals(409, error.error.status)
  }

  @Test
  fun createRedemptionSurfacesInsufficientPoints() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("INSUFFICIENT_POINTS"), status = 422)))

    val error = assertFailsWith<ExpysException.Api> {
      client(http).createRedemption(CreateRedemptionRequest(offer = "off_1"))
    }
    assertEquals(ApiErrorKind.VALIDATION, error.error.kind)
    assertEquals("INSUFFICIENT_POINTS", error.error.code)
    assertEquals(422, error.error.status)
  }

  @Test
  fun listRedemptionsIssuesGetWithQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"nextCursor":null,"redemptions":[]}""")))

    val result = client(http).listRedemptions(
      status = "OPEN",
      limit = 25,
      cursor = "c1",
      externalUserID = "u1",
    )

    assertEquals(0, result.redemptions.size)
    val url = http.requests[0].url
    assertTrue(url.startsWith("https://api.test/v1/redemptions?"))
    assertTrue(url.contains("status=OPEN"))
    assertTrue(url.contains("limit=25"))
    assertTrue(url.contains("cursor=c1"))
    assertTrue(url.contains("externalUserID=u1"))
    assertEquals("GET", http.requests[0].method)
  }

  @Test
  fun listRedemptionsIssuesGetWithoutQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"nextCursor":null,"redemptions":[]}""")))

    client(http).listRedemptions()

    assertEquals("https://api.test/v1/redemptions", http.requests[0].url)
  }

  @Test
  fun walletTransactionsIssuesGetWithQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"nextCursor":"c2","transactions":[]}""")))

    val result = client(http).walletTransactions(limit = 5, cursor = "c1", externalUserID = "u1")

    assertEquals("c2", result.nextCursor)
    val url = http.requests[0].url
    assertTrue(url.startsWith("https://api.test/v1/wallet/transactions?"))
    assertTrue(url.contains("limit=5"))
    assertTrue(url.contains("cursor=c1"))
    assertTrue(url.contains("externalUserID=u1"))
  }

  @Test
  fun walletTransactionsIssuesGetWithoutQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"nextCursor":null,"transactions":[]}""")))

    client(http).walletTransactions()

    assertEquals("https://api.test/v1/wallet/transactions", http.requests[0].url)
  }

  @Test
  fun listConversationsIssuesGetWithExternalUser() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"conversations":[]}""")))

    val result = client(http).listConversations(externalUserID = "u1")

    assertEquals(0, result.conversations.size)
    assertEquals("https://api.test/v1/conversations?externalUserID=u1", http.requests[0].url)
  }

  @Test
  fun listConversationsIssuesGetWithoutQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"conversations":[]}""")))

    client(http).listConversations()

    assertEquals("https://api.test/v1/conversations", http.requests[0].url)
  }

  @Test
  fun listMessagesEncodesPathAndQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"messages":[],"nextCursor":null}""")))

    val result = client(http).listMessages("c 1", limit = 50, cursor = "cur", externalUserID = "u1")

    assertEquals(0, result.messages.size)
    val url = http.requests[0].url
    assertTrue(url.startsWith("https://api.test/v1/conversations/c%201/messages?"))
    assertTrue(url.contains("limit=50"))
    assertTrue(url.contains("cursor=cur"))
    assertTrue(url.contains("externalUserID=u1"))
  }

  @Test
  fun listMessagesIssuesGetWithoutQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"messages":[],"nextCursor":null}""")))

    client(http).listMessages("c1")

    assertEquals("https://api.test/v1/conversations/c1/messages", http.requests[0].url)
  }

  @Test
  fun sendMessageAutogeneratesIdempotencyKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"ok":true}""")))

    val result = client(http).sendMessage("c 1", "hello")

    assertTrue(result.ok)
    assertEquals("POST", http.requests[0].method)
    assertEquals("https://api.test/v1/conversations/c%201/messages", http.requests[0].url)
    assertEquals("""{"message":"hello"}""", http.requests[0].body)
    val key = http.requests[0].headers["Idempotency-Key"]
    assertNotNull(key)
    assertTrue(key.isNotEmpty())
  }

  @Test
  fun sendMessageRespectsIdempotencyOverride() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"ok":true}""")))

    client(http).sendMessage("c1", "hi", idempotencyKey = "my-key")

    assertEquals("my-key", http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun streamMessagesEncodesPathAndDecodes() = runTest {
    val lines = listOf(
      """data: {"authorID":"a1","body":"hi","createdAt":"t","id":"m1","type":"member"}""",
      "",
    )
    val http = FakeStreamingHttpClient(listOf(StreamStep.Response(200, lines = lines)))

    val message = client(http).streamMessages("c 1").first()

    assertEquals("m1", message.id)
    assertEquals("https://api.test/v1/conversations/c%201/stream", http.requests[0].url)
    assertEquals("text/event-stream", http.requests[0].headers["Accept"])
  }
}
