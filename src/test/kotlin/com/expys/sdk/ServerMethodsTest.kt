package com.expys.sdk

import com.expys.sdk.models.CreateWebhookRequest
import com.expys.sdk.models.CreditWalletRequest
import com.expys.sdk.models.GetBalanceResponse
import com.expys.sdk.models.SetMemberRequest
import com.expys.sdk.models.TokenExchangeRequest
import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerMethodsTest {
  // A client whose configured token is a machine credential (passes the guard).
  private fun machineClient(http: HttpClient): ExpysClient =
    ExpysClient.create(ExpysConfiguration(token = "expys_live_machine", baseUrl = "https://api.test"), http)

  @Test
  fun exchangeTokenPostsAndAutogeneratesKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"accessToken":"v4.local.m","expiresAt":"2026-01-01"}""")))

    val grant = machineClient(http).exchangeToken(TokenExchangeRequest(externalUserID = "u1"))

    assertEquals("v4.local.m", grant.accessToken)
    assertEquals("POST", http.requests[0].method)
    assertEquals("https://api.test/v1/auth/exchange", http.requests[0].url)
    val key = http.requests[0].headers["Idempotency-Key"]
    assertNotNull(key)
    assertTrue(key.isNotEmpty())
  }

  @Test
  fun creditPointsPostsEncodedRequest() = runTest {
    val json = """{"balance":150.0,"currency":{"name":"P","symbol":"P","unitsPerUSD":1.0}}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http)
      .creditPoints(CreditWalletRequest(amount = 100, externalUserID = "u1", reason = "bonus"))

    assertEquals(150.0, result.balance)
    assertEquals("POST", http.requests[0].method)
    assertEquals("https://api.test/v1/wallet/credit", http.requests[0].url)
    assertEquals("""{"amount":100,"externalUserID":"u1","reason":"bonus"}""", http.requests[0].body)
    val key = http.requests[0].headers["Idempotency-Key"]
    assertNotNull(key)
    assertTrue(key.isNotEmpty())
  }

  @Test
  fun creditPointsOmitsAbsentReason() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"balance":1.0,"currency":{"name":"P","symbol":"P","unitsPerUSD":1.0}}""")))

    machineClient(http).creditPoints(CreditWalletRequest(amount = 1, externalUserID = "u1"))

    assertEquals("""{"amount":1,"externalUserID":"u1"}""", http.requests[0].body)
  }

  @Test
  fun setMemberPutsEncodedPathWithBodyAndNoKey() = runTest {
    val json = """{"attributes":null,"displayName":"Ada","externalUserID":"u 1","tier":"gold"}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http).setMember("u 1", SetMemberRequest(tier = "gold"))

    assertEquals("gold", result.tier)
    assertEquals("PUT", http.requests[0].method)
    assertEquals("https://api.test/v1/members/u%201", http.requests[0].url)
    assertEquals("application/json", http.requests[0].headers["Content-Type"])
    assertEquals(null, http.requests[0].headers["Idempotency-Key"])
    assertEquals("""{"tier":"gold"}""", http.requests[0].body)
  }

  @Test
  fun getMemberGetsEncodedPath() = runTest {
    val json = """{"attributes":null,"displayName":null,"externalUserID":"u 1",""" +
      """"redemptionCounts":{"AWAITING_CUSTOMER":0,"AWAITING_VENDOR":0,"CANCELED":0,"COMPLETED":0,""" +
      """"OPEN":0,"PURCHASED":0,"SUBMITTED":0},"tier":"gold",""" +
      """"wallet":{"amountReceived":0.0,"amountReceivedDisplay":0,"amountReceivedUSD":0.0,"amountSpent":0.0,""" +
      """"amountSpentDisplay":0,"amountSpentUSD":0.0,"balance":0.0,"balanceDisplay":0,"balanceUSD":0.0,""" +
      """"currency":{"name":"P","symbol":"P","unitsPerUSD":1.0}}}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http).getMember("u 1")

    assertEquals("u 1", result.externalUserID)
    assertEquals("GET", http.requests[0].method)
    assertEquals("https://api.test/v1/members/u%201", http.requests[0].url)
  }

  @Test
  fun listMembersSendsPagingQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"members":[],"nextCursor":null}""")))

    val result = machineClient(http).listMembers(tier = "gold", limit = 2, cursor = "c1")

    assertTrue(result.members.isEmpty())
    assertEquals(null, result.nextCursor)
    assertEquals("GET", http.requests[0].method)
    assertTrue(http.requests[0].url.startsWith("https://api.test/v1/members?"))
    assertTrue(http.requests[0].url.contains("tier=gold"))
    assertTrue(http.requests[0].url.contains("limit=2"))
    assertTrue(http.requests[0].url.contains("cursor=c1"))
  }

  @Test
  fun removeMemberDeletesWithoutBodyOrKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"archived":true,"balanceRetained":false,"externalUserID":"u 1"}""")))

    val result = machineClient(http).removeMember("u 1")

    assertTrue(result.archived)
    assertEquals("DELETE", http.requests[0].method)
    assertEquals("https://api.test/v1/members/u%201", http.requests[0].url)
    assertEquals(null, http.requests[0].body)
    assertEquals(null, http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun removeMemberSerializesRetainBalanceTrue() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"archived":true,"balanceRetained":true,"externalUserID":"u1"}""")))

    machineClient(http).removeMember("u1", retainBalance = true)

    assertEquals("https://api.test/v1/members/u1?retainBalance=true", http.requests[0].url)
  }

  @Test
  fun removeMemberSerializesRetainBalanceFalse() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"archived":true,"balanceRetained":false,"externalUserID":"u1"}""")))

    machineClient(http).removeMember("u1", retainBalance = false)

    assertEquals("https://api.test/v1/members/u1?retainBalance=false", http.requests[0].url)
  }

  @Test
  fun analyticsSummaryGets() = runTest {
    val json = """{"completionRate":0.5,"memberCount":10.0,"pointsMinted":100.0,"pointsSpent":50.0,""" +
      """"redemptions":{"awaiting_customer":0.0,"awaiting_vendor":0.0,"canceled":0.0,"completed":0.0,""" +
      """"open":0.0,"purchased":0.0,"submitted":0.0,"total":0.0}}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http).analyticsSummary()

    assertEquals(10.0, result.memberCount)
    assertEquals("https://api.test/v1/analytics/summary", http.requests[0].url)
  }

  @Test
  fun analyticsOffersGets() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"offers":[]}""")))

    val result = machineClient(http).analyticsOffers()

    assertEquals(0, result.offers.size)
    assertEquals("https://api.test/v1/analytics/offers", http.requests[0].url)
  }

  @Test
  fun balanceGets() = runTest {
    val http = FakeHttpClient(
      listOf(
        ok(
          """{"balance":3800,"balanceDisplay":38,"balanceUSD":38.0,"creditLimit":1000,"creditLimitUSD":10.0,""" +
            """"lifetimeSpent":1200,"lifetimeSpentUSD":12.0,"ratePerPoint":0.01,"settlementMode":"ORG_POOL"}""",
        ),
      ),
    )

    val result = machineClient(http).balance()

    assertEquals(3800, result.balance)
    assertEquals(1000, result.creditLimit)
    assertEquals(1200, result.lifetimeSpent)
    assertEquals(GetBalanceResponse.SettlementMode.ORG_POOL, result.settlementMode)
    assertEquals("https://api.test/v1/balance", http.requests[0].url)
  }

  @Test
  fun analyticsTimeseriesGetsWithRequiredQuery() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"buckets":[]}""")))

    val result = machineClient(http).analyticsTimeseries(
      from = "2026-01-01T00:00:00Z",
      to = "2026-02-01T00:00:00Z",
      interval = "day",
    )

    assertEquals(0, result.buckets.size)
    val url = http.requests[0].url
    assertTrue(url.startsWith("https://api.test/v1/analytics/timeseries?"))
    assertTrue(url.contains("from=2026-01-01T00%3A00%3A00Z"))
    assertTrue(url.contains("to=2026-02-01T00%3A00%3A00Z"))
    assertTrue(url.contains("interval=day"))
  }

  @Test
  fun createWebhookPostsAndAutogeneratesKey() = runTest {
    val json = """{"createdAt":"t","environment":"LIVE","events":["redemption.created"],"id":"wh_1",""" +
      """"signingSecret":"whsec_x","url":"https://example.com/hook"}"""
    val http = FakeHttpClient(listOf(ok(json, status = 201)))

    val result = machineClient(http).createWebhook(
      CreateWebhookRequest(events = listOf("redemption.created"), url = URI("https://example.com/hook")),
    )

    assertEquals("whsec_x", result.signingSecret)
    assertEquals("POST", http.requests[0].method)
    assertEquals("https://api.test/v1/webhooks", http.requests[0].url)
    val key = http.requests[0].headers["Idempotency-Key"]
    assertNotNull(key)
    assertTrue(key.isNotEmpty())
  }

  @Test
  fun listWebhooksGets() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"data":[]}""")))

    val result = machineClient(http).listWebhooks()

    assertEquals(0, result.`data`.size)
    assertEquals("GET", http.requests[0].method)
    assertEquals("https://api.test/v1/webhooks", http.requests[0].url)
  }

  @Test
  fun deleteWebhookDeletesEncodedPathWithoutKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"id":"wh 1","ok":true}""")))

    val result = machineClient(http).deleteWebhook("wh 1")

    assertTrue(result.ok)
    assertEquals("DELETE", http.requests[0].method)
    assertEquals("https://api.test/v1/webhooks/wh%201", http.requests[0].url)
    assertEquals(null, http.requests[0].body)
    assertEquals(null, http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun serverMethodWithMemberTokenThrowsNotConfiguredBeforeAnyRequest() = runTest {
    // No steps queued: any reach-through to the transport would throw a different error.
    val http = FakeHttpClient(emptyList())
    val client = ExpysClient.create(
      ExpysConfiguration(token = "v4.local.member", baseUrl = "https://api.test"),
      http,
    )

    val calls: List<suspend () -> Unit> = listOf(
      { client.exchangeToken(TokenExchangeRequest(externalUserID = "u1")) },
      { client.creditPoints(CreditWalletRequest(amount = 1, externalUserID = "u1")) },
      { client.setMember("u1", SetMemberRequest(tier = "g")) },
      { client.getMember("u1") },
      { client.listMembers() },
      { client.removeMember("u1") },
      { client.analyticsSummary() },
      { client.analyticsOffers() },
      { client.analyticsTimeseries(from = "a", to = "b", interval = "day") },
      { client.balance() },
      { client.createWebhook(CreateWebhookRequest(events = emptyList(), url = URI("https://x"))) },
      { client.listWebhooks() },
      { client.deleteWebhook("wh_1") },
    )

    for (call in calls) {
      assertFailsWith<ExpysException.NotConfigured> { call() }
    }

    // Crucially, NOT ONE method reached the transport.
    assertEquals(0, http.requests.size)
  }
}
