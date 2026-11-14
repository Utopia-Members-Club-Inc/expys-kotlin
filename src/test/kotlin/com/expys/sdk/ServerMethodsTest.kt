package com.expys.sdk

import com.expys.sdk.models.CreateWebhookRequest
import com.expys.sdk.models.CreditWalletRequest
import com.expys.sdk.models.GetAnalyticsSegmentsResponse
import com.expys.sdk.models.GetBalanceResponse
import com.expys.sdk.models.ImportMembersRequest
import com.expys.sdk.models.MemberImportRejection
import com.expys.sdk.models.MemberImportRow
import com.expys.sdk.models.MemberSignals
import com.expys.sdk.models.MemberSignalsInput
import com.expys.sdk.models.MemberSummary
import com.expys.sdk.models.SetMemberRequest
import com.expys.sdk.models.SignalLiftGroup
import com.expys.sdk.models.SignalSegmentKey
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
    val json = """{"attributes":null,"displayName":"Ada","externalUserID":"u 1","signals":null,"tier":"gold"}"""
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
      """"OPEN":0,"PURCHASED":0,"SUBMITTED":0},"signals":null,"signalsUpdatedAt":null,""" +
      """"tier":"gold","vipStatus":"UNKNOWN","vipStatusAt":"2026-11-01T04:00:00.000Z",""" +
      """"wallet":{"amountReceived":0.0,"amountReceivedDisplay":0,"amountReceivedUSD":0.0,"amountSpent":0.0,""" +
      """"amountSpentDisplay":0,"amountSpentUSD":0.0,"balance":0.0,"balanceDisplay":0,"balanceUSD":0.0,""" +
      """"currency":{"name":"P","symbol":"P","unitsPerUSD":1.0}}}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http).getMember("u 1")

    assertEquals("u 1", result.externalUserID)
    assertEquals(java.time.OffsetDateTime.parse("2026-11-01T04:00:00Z"), result.vipStatusAt)
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
  fun importMembersPostsRowsAndParsesReport() = runTest {
    val json = """{"accepted":1,"rejected":[""" +
      """{"code":"signal_not_enabled","externalUserID":"u2","field":"valueBand","row":2}]}"""
    val http = FakeHttpClient(listOf(ok(json)))
    val rows = listOf(
      MemberImportRow(
        externalUserID = "u1",
        signals = MemberSignalsInput(activityBand = MemberSignalsInput.ActivityBand.HIGH),
      ),
      MemberImportRow(externalUserID = "u2", tier = "gold"),
    )

    val report = machineClient(http).importMembers(ImportMembersRequest(rows = rows))

    assertEquals(1, report.accepted)
    assertEquals(MemberImportRejection.Code.signal_not_enabled, report.rejected[0].code)
    assertEquals(2, report.rejected[0].row)
    assertEquals("POST", http.requests[0].method)
    assertEquals("https://api.test/v1/members/batch", http.requests[0].url)
    assertEquals(
      """{"rows":[{"externalUserID":"u1","signals":{"activityBand":"HIGH"}},""" +
        """{"externalUserID":"u2","tier":"gold"}]}""",
      http.requests[0].body,
    )
    val key = http.requests[0].headers["Idempotency-Key"]
    assertNotNull(key)
    assertTrue(key.isNotEmpty())
  }

  @Test
  fun importMembersSendsDryRunAndCustomKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"accepted":0,"rejected":[]}""")))

    machineClient(http).importMembers(
      ImportMembersRequest(rows = listOf(MemberImportRow(externalUserID = "u1"))),
      dryRun = true,
      idempotencyKey = "batch-1",
    )

    assertEquals("https://api.test/v1/members/batch?dryRun=true", http.requests[0].url)
    assertEquals("batch-1", http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun getSignalHistoryPages() = runTest {
    val page = """{"nextCursor":"c2","snapshots":[{"environment":"LIVE",""" +
      """"observedAt":"2026-11-01T00:00:00Z","receivedAt":"2026-11-01T00:00:01Z",""" +
      """"signals":{"activityBand":"HIGH"},"source":"BATCH"}]}"""
    val http = FakeHttpClient(listOf(ok(page), ok("""{"nextCursor":null,"snapshots":[]}""")))
    val client = machineClient(http)

    val first = client.getSignalHistory("u 1", limit = 1)
    val second = client.getSignalHistory("u 1", limit = 1, cursor = first.nextCursor)

    assertEquals(MemberSignals.ActivityBand.HIGH, first.snapshots[0].signals?.activityBand)
    assertEquals(null, second.nextCursor)
    assertEquals("GET", http.requests[0].method)
    assertEquals("https://api.test/v1/members/u%201/signals/history?limit=1", http.requests[0].url)
    assertTrue(http.requests[1].url.contains("cursor=c2"))
  }

  @Test
  fun importMembersEncodesAsOfAsIsoTimestamp() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"accepted":1,"rejected":[]}""")))
    val asOf = java.time.OffsetDateTime.parse("2026-10-31T23:59:59Z")

    machineClient(http).importMembers(
      ImportMembersRequest(rows = listOf(MemberImportRow(externalUserID = "u1", asOf = asOf))),
    )

    assertEquals("""{"rows":[{"externalUserID":"u1","asOf":"2026-10-31T23:59:59Z"}]}""", http.requests[0].body)
  }

  @Test
  fun listMembersSendsSignalFilters() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"members":[],"nextCursor":null}""")))

    machineClient(http).listMembers(
      valueBand = MemberSignals.ValueBand.TOP_1,
      activityBand = MemberSignals.ActivityBand.LOW,
      vipStatus = MemberSummary.VipStatus.COOLING,
    )

    assertTrue(http.requests[0].url.contains("valueBand=TOP_1"))
    assertTrue(http.requests[0].url.contains("activityBand=LOW"))
    assertTrue(http.requests[0].url.contains("vipStatus=COOLING"))
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
  fun getAnalyticsSegmentsGetsWithGroupByAndDecodesSuppressedCell() = runTest {
    val json = """{"groupBy":["valueBand","vipStatus"],"segments":[""" +
      """{"bookings":9,"key":{"valueBand":"TOP_1","vipStatus":"ACTIVE"},"members":12,""" +
      """"membersWhoBooked":7,"suppressed":false,""" +
      """"topCategories":[{"members":5,"name":"NBA","slug":"nba"}]},""" +
      """{"bookings":null,"key":{"valueBand":"TOP_5","vipStatus":"LAPSED"},"members":null,""" +
      """"membersWhoBooked":null,"suppressed":true,"topCategories":[]}]}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http).getAnalyticsSegments(
      from = "2026-10-01T00:00:00Z",
      to = "2026-11-01T00:00:00Z",
      groupBy = listOf(
        GetAnalyticsSegmentsResponse.GroupBy.valueBand,
        GetAnalyticsSegmentsResponse.GroupBy.vipStatus,
      ),
    )

    assertEquals(
      listOf(GetAnalyticsSegmentsResponse.GroupBy.valueBand, GetAnalyticsSegmentsResponse.GroupBy.vipStatus),
      result.groupBy,
    )
    assertEquals(12, result.segments[0].members)
    assertEquals(SignalSegmentKey.ValueBand.TOP_1, result.segments[0].key.valueBand)
    assertEquals("nba", result.segments[0].topCategories[0].slug)
    val hidden = result.segments[1]
    assertTrue(hidden.suppressed)
    assertEquals(null, hidden.members)
    assertEquals(null, hidden.bookings)
    assertEquals(null, hidden.membersWhoBooked)
    assertEquals(SignalSegmentKey.VipStatus.LAPSED, hidden.key.vipStatus)
    assertEquals("GET", http.requests[0].method)
    val url = http.requests[0].url
    assertTrue(url.startsWith("https://api.test/v1/analytics/segments?"))
    assertTrue(url.contains("from=2026-10-01T00%3A00%3A00Z"))
    assertTrue(url.contains("to=2026-11-01T00%3A00%3A00Z"))
    assertTrue(url.contains("groupBy=valueBand%2CvipStatus"))
  }

  @Test
  fun getAnalyticsLiftGetsWithPeriodAndDecodesSuppressedCohort() = runTest {
    val json = """{"groups":[{"booked":{"fell":0.1,"held":0.5,"members":20,"rose":0.4,"suppressed":false},""" +
      """"notBooked":{"fell":null,"held":null,"members":null,"rose":null,"suppressed":true},""" +
      """"valueBand":"TOP_1","vipStatus":"ACTIVE"}]}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http).getAnalyticsLift(from = "2026-10-01T00:00:00Z", to = "2026-11-01T00:00:00Z")

    val group = result.groups[0]
    assertEquals(SignalLiftGroup.ValueBand.TOP_1, group.valueBand)
    assertEquals(SignalLiftGroup.VipStatus.ACTIVE, group.vipStatus)
    assertEquals(20, group.booked.members)
    assertEquals(0.4, group.booked.rose)
    assertTrue(group.notBooked.suppressed)
    assertEquals(null, group.notBooked.members)
    assertEquals(null, group.notBooked.rose)
    assertEquals("GET", http.requests[0].method)
    assertEquals(
      "https://api.test/v1/analytics/lift?from=2026-10-01T00%3A00%3A00Z&to=2026-11-01T00%3A00%3A00Z",
      http.requests[0].url,
    )
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
      { client.importMembers(ImportMembersRequest(rows = emptyList())) },
      { client.getSignalHistory("u1") },
      {
        client.getAnalyticsSegments(
          from = "a",
          to = "b",
          groupBy = listOf(GetAnalyticsSegmentsResponse.GroupBy.valueBand),
        )
      },
      { client.getAnalyticsLift(from = "a", to = "b") },
    )

    for (call in calls) {
      assertFailsWith<ExpysException.NotConfigured> { call() }
    }

    // Crucially, NOT ONE method reached the transport.
    assertEquals(0, http.requests.size)
  }
}
