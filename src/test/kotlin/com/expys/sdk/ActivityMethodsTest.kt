package com.expys.sdk

import com.expys.sdk.models.ActivityMonth
import com.expys.sdk.models.ActivityValue
import com.expys.sdk.models.ExperienceImpactRow
import com.expys.sdk.models.ExperienceImpactSummary
import com.expys.sdk.models.ImportMemberActivityRequest
import com.expys.sdk.models.MemberActivity
import com.expys.sdk.models.MemberActivityImportError
import com.expys.sdk.models.MemberActivityImportRow
import com.expys.sdk.models.MetricImpact
import com.expys.sdk.models.SetMemberActivityRequest
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The VIP activity and experience impact methods (plans/vip-activity-lift 6.3). */
class ActivityMethodsTest {
  private fun machineClient(http: HttpClient): ExpysClient =
    ExpysClient.create(ExpysConfiguration(token = "expys_live_machine", baseUrl = "https://api.test"), http)

  @Test
  fun setMemberActivityPutsMonthsToEncodedPathWithoutKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"created":1,"externalUserID":"u 1","unchanged":0,"updated":1}""")))
    val months = listOf(
      ActivityMonth(month = "2026-10", deposits = ActivityValue(index = 120.0), activeDays = 12),
      ActivityMonth(month = "2026-09", deposits = ActivityValue(amount = 240_000, currency = "USD")),
    )

    val result = machineClient(http).setMemberActivity("u 1", SetMemberActivityRequest(months = months))

    assertEquals(1, result.created)
    assertEquals(1, result.updated)
    assertEquals("PUT", http.requests[0].method)
    assertEquals("https://api.test/v1/members/u%201/activity", http.requests[0].url)
    assertEquals(
      """{"months":[{"month":"2026-10","activeDays":12,"deposits":{"index":120.0}},""" +
        """{"month":"2026-09","deposits":{"amount":240000,"currency":"USD"}}]}""",
      http.requests[0].body,
    )
    assertNull(http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun setMemberActivitySendsCustomKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"created":0,"externalUserID":"u1","unchanged":1,"updated":0}""")))

    machineClient(http).setMemberActivity(
      "u1",
      SetMemberActivityRequest(months = listOf(ActivityMonth(month = "2026-10"))),
      idempotencyKey = "act-1",
    )

    assertEquals("act-1", http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun getMemberActivityGetsMonthsInStoredForm() = runTest {
    val json = """{"externalUserID":"u1","form":"INDEX","months":[""" +
      """{"deposits":{"index":120},"month":"2026-10","spend":{"index":95}},""" +
      """{"deposits":{"index":100},"month":"2026-09"}]}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val activity = machineClient(http).getMemberActivity("u1")

    assertEquals(MemberActivity.Form.INDEX, activity.form)
    assertEquals(2, activity.months.size)
    assertEquals(120.0, activity.months[0].deposits?.index)
    assertEquals("GET", http.requests[0].method)
    assertEquals("https://api.test/v1/members/u1/activity", http.requests[0].url)
  }

  @Test
  fun importMemberActivityPostsRowsAndParsesReport() = runTest {
    val json = """{"accepted":1,"created":1,"errors":[{"code":"member_not_found",""" +
      """"externalUserID":"u2","field":"externalUserID","row":2}],"rejected":1,"unchanged":0,"updated":0}"""
    val http = FakeHttpClient(listOf(ok(json)))
    val rows = listOf(
      MemberActivityImportRow(
        externalUserID = "u1",
        months = listOf(ActivityMonth(month = "2026-10", deposits = ActivityValue(index = 120.0))),
      ),
    )

    val report = machineClient(http).importMemberActivity(ImportMemberActivityRequest(rows = rows))

    assertEquals(1, report.accepted)
    assertEquals(1, report.rejected)
    assertEquals(MemberActivityImportError.Code.member_not_found, report.errors[0].code)
    assertEquals(2, report.errors[0].row)
    assertEquals("POST", http.requests[0].method)
    assertEquals("https://api.test/v1/members/activity/batch", http.requests[0].url)
    assertEquals(
      """{"rows":[{"externalUserID":"u1","months":[{"month":"2026-10","deposits":{"index":120.0}}]}]}""",
      http.requests[0].body,
    )
    val key = http.requests[0].headers["Idempotency-Key"]
    assertNotNull(key)
    assertTrue(key.isNotEmpty())
  }

  @Test
  fun importMemberActivitySendsDryRunAndCustomKey() = runTest {
    val empty = """{"accepted":0,"created":0,"errors":[],"rejected":0,"unchanged":0,"updated":0}"""
    val http = FakeHttpClient(listOf(ok(empty)))

    machineClient(http).importMemberActivity(
      ImportMemberActivityRequest(
        rows = listOf(
          MemberActivityImportRow(externalUserID = "u1", months = listOf(ActivityMonth(month = "2026-10"))),
        ),
      ),
      dryRun = true,
      idempotencyKey = "activity-1",
    )

    assertEquals("https://api.test/v1/members/activity/batch?dryRun=true", http.requests[0].url)
    assertEquals("activity-1", http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun getExperienceImpactDecodesDatesAndSendsEveryFilter() = runTest {
    val row = """{"anchorMonth":"2026-03","deskLabel":"John D.","experienceAt":"2026-04-09T12:00:00.000Z",""" +
      """"externalUserID":"48213","offer":"offer_123","offerTitle":"The Masters","overlapping":false,""" +
      """"redeemedAt":"2026-03-14T18:02:11.000Z","redemptionID":"redemption_789","spend":null,""" +
      """"state":"COMPLETE","viaHostDesk":true,"window":3,"deposits":{"afterAmount":null,"afterMonths":3,""" +
      """"beforeAmount":null,"beforeMonths":3,"change":0.2,"comparison":{"medianChange":0.02,"vips":37},""" +
      """"comparisonReason":null,"currency":null,"net":0.18,"state":"COMPLETE"}}"""
    val http = FakeHttpClient(
      listOf(
        ok("""{"nextCursor":"c2","rows":[$row],"unlinked":2}"""),
        ok("""{"nextCursor":null,"rows":[],"unlinked":0}"""),
      ),
    )
    val client = machineClient(http)

    val first = client.getExperienceImpact(from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z")
    client.getExperienceImpact(
      from = "2026-01-01T00:00:00Z",
      to = "2026-07-01T00:00:00Z",
      window = 6,
      offer = "offer_123",
      status = ExperienceImpactRow.State.COMPLETE,
      limit = 100,
      cursor = first.nextCursor,
    )

    val reading = first.rows[0]
    assertEquals(OffsetDateTime.parse("2026-03-14T18:02:11Z"), reading.redeemedAt)
    assertEquals(OffsetDateTime.parse("2026-04-09T12:00:00Z"), reading.experienceAt)
    assertEquals(ExperienceImpactRow.State.COMPLETE, reading.state)
    assertEquals(0.2, reading.deposits?.change)
    assertEquals(37, reading.deposits?.comparison?.vips)
    assertEquals(MetricImpact.State.COMPLETE, reading.deposits?.state)
    assertNull(reading.spend)
    assertEquals(2, first.unlinked)
    assertEquals("GET", http.requests[0].method)
    assertEquals(
      "https://api.test/v1/analytics/impact?from=2026-01-01T00%3A00%3A00Z&to=2026-07-01T00%3A00%3A00Z",
      http.requests[0].url,
    )
    assertEquals(
      "https://api.test/v1/analytics/impact?cursor=c2&from=2026-01-01T00%3A00%3A00Z&limit=100" +
        "&offer=offer_123&status=COMPLETE&to=2026-07-01T00%3A00%3A00Z&window=6",
      http.requests[1].url,
    )
  }

  @Test
  fun getExperienceImpactSummaryDecodesWinBackAndSuppressedCell() = runTest {
    val hidden = """"compared":null,"medianChange":null,"medianComparisonChange":null,"netLift":null,""" +
      """"redeemers":null,"roseShare":null,"suppressed":true"""
    val json = """{"all":{"compared":39,"medianChange":0.17,"medianComparisonChange":0.02,"netLift":0.15,""" +
      """"redeemers":40,"roseShare":0.7,"suppressed":false},""" +
      """"experiences":[{$hidden,"offer":"offer_456","offerTitle":"F1"}],"metric":"spend","window":6,""" +
      """"winBack":{"notSent":{"measured":null,"medianChange":null,"redeemed":null,"redeemedShare":null,""" +
      """"suppressed":true,"vips":null},"sent":{"measured":38,"medianChange":0.31,"redeemed":14,""" +
      """"redeemedShare":0.35,"suppressed":false,"vips":40}}}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val summary = machineClient(http).getExperienceImpactSummary(
      from = "2026-01-01T00:00:00Z",
      to = "2026-07-01T00:00:00Z",
      window = 6,
      metric = ExperienceImpactSummary.Metric.spend,
    )

    assertEquals(ExperienceImpactSummary.Metric.spend, summary.metric)
    assertEquals(0.15, summary.all.netLift)
    assertTrue(summary.experiences[0].suppressed)
    assertNull(summary.experiences[0].netLift)
    assertEquals(0.31, summary.winBack.sent.medianChange)
    assertTrue(summary.winBack.notSent.suppressed)
    assertEquals(
      "https://api.test/v1/analytics/impact/summary?from=2026-01-01T00%3A00%3A00Z&metric=spend" +
        "&to=2026-07-01T00%3A00%3A00Z&window=6",
      http.requests[0].url,
    )
  }

  @Test
  fun activityMethodsWithMemberTokenThrowNotConfiguredBeforeAnyRequest() = runTest {
    val http = FakeHttpClient(emptyList())
    val client = ExpysClient.create(
      ExpysConfiguration(token = "v4.local.member", baseUrl = "https://api.test"),
      http,
    )

    val calls: List<suspend () -> Unit> = listOf(
      { client.setMemberActivity("u1", SetMemberActivityRequest(months = emptyList())) },
      { client.getMemberActivity("u1") },
      { client.importMemberActivity(ImportMemberActivityRequest(rows = emptyList())) },
      { client.getExperienceImpact(from = "a", to = "b") },
      { client.getExperienceImpactSummary(from = "a", to = "b") },
    )

    for (call in calls) {
      assertFailsWith<ExpysException.NotConfigured> { call() }
    }
    assertEquals(0, http.requests.size)
  }
}
