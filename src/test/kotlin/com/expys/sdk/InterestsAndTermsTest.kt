package com.expys.sdk

import com.expys.sdk.models.AcceptTermsRequest
import com.expys.sdk.models.ConfirmPhoneVerificationRequest
import com.expys.sdk.models.CreateInterestRequest
import com.expys.sdk.models.SetInterestIntakeRequest
import com.expys.sdk.models.SetRedemptionFeedbackRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Phase 12-16 surface. These shipped as generated MODELS in 0.7.0 with no client
 * methods at all, so a consumer had types for endpoints they could not call.
 */
class InterestsAndTermsTest {
  private fun client(http: HttpClient): ExpysClient =
    ExpysClient.create(ExpysConfiguration(token = "t0", baseUrl = "https://api.test"), http)

  private val interestJson =
    """{"conversationId":"req_1","createdAt":"t","id":"req_1","intake":null,""" +
      """"offer":"off_1","status":"submitted"}"""

  @Test
  fun createInterestPostsAndSendsIdempotencyKey() = runTest {
    val http = FakeHttpClient(listOf(ok(interestJson)))

    val interest = client(http).createInterest(
      CreateInterestRequest(offer = "off_1", adults = 2, preferredDates = listOf("2099-11-06")),
    )

    assertEquals("req_1", interest.id)
    // The conversation is live immediately -- that is the point of an interest.
    assertEquals("req_1", interest.conversationId)
    assertEquals("https://api.test/v1/interests", http.requests[0].url)
    assertEquals("POST", http.requests[0].method)
    assertNotNull(http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun getInterestEncodesThePath() = runTest {
    val http = FakeHttpClient(listOf(ok(interestJson)))

    client(http).getInterest("req 1")

    assertEquals("https://api.test/v1/interests/req%201", http.requests[0].url)
    assertEquals("GET", http.requests[0].method)
  }

  @Test
  fun setInterestIntakeReplacesTheBlock() = runTest {
    val http = FakeHttpClient(listOf(ok(interestJson)))

    client(http).setInterestIntake("req_1", SetInterestIntakeRequest(adults = 2))

    assertEquals("https://api.test/v1/interests/req_1/intake", http.requests[0].url)
    assertEquals("PUT", http.requests[0].method)
  }

  @Test
  fun phoneVerificationUsesPostThenPut() = runTest {
    val http = FakeHttpClient(
      listOf(ok("""{"sent":true}"""), ok("""{"verified":true}""")),
    )
    val sdk = client(http)

    val started = sdk.startPhoneVerification("req_1")
    val confirmed = sdk.confirmPhoneVerification("req_1", ConfirmPhoneVerificationRequest(code = "123456"))

    assertTrue(started.sent)
    assertTrue(confirmed.verified)
    assertEquals("POST", http.requests[0].method)
    assertEquals("PUT", http.requests[1].method)
    assertEquals(
      "https://api.test/v1/interests/req_1/phone/verification",
      http.requests[0].url,
    )
  }

  @Test
  fun getTermsReportsOutstandingDocuments() = runTest {
    val json =
      """{"documents":[{"acceptedAt":null,"publishedAt":"t","type":"TERMS_OF_SERVICE",""" +
        """"version":"ldv_1"}]}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val terms = client(http).getTerms()

    // Null means outstanding -- including when the member accepted an EARLIER version,
    // which is the whole point of versioning.
    assertNull(terms.documents[0].acceptedAt)
    assertEquals("ldv_1", terms.documents[0].version)
  }

  @Test
  fun getTermsPassesExternalUserId() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"documents":[]}""")))

    client(http).getTerms(externalUserID = "u1")

    assertTrue(http.requests[0].url.contains("externalUserID=u1"))
  }

  @Test
  fun getTermsContentReturnsRenderedHtml() = runTest {
    val json =
      """{"contentHTML":"<h1>Terms</h1>","publishedAt":"t","renderedHash":"abc",""" +
        """"type":"TERMS_OF_SERVICE","version":"ldv_1"}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val content = client(http).getTermsContent("ldv_1")

    assertEquals("<h1>Terms</h1>", content.contentHTML)
    assertEquals("https://api.test/v1/terms/ldv_1/content", http.requests[0].url)
  }

  @Test
  fun acceptTermsPostsAndSendsIdempotencyKey() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"documents":[]}""")))

    client(http).acceptTerms(AcceptTermsRequest(versions = listOf("ldv_1")))

    assertEquals("https://api.test/v1/terms/acceptance", http.requests[0].url)
    assertEquals("POST", http.requests[0].method)
    assertNotNull(http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun setRedemptionFeedbackPutsAgainstTheRedemption() = runTest {
    val json =
      """{"canceledNote":null,"canceledReason":null,"conversationId":"cnv_1",""" +
        """"feedback":{"comment":"Superb.","rating":9,"submittedAt":"t"},""" +
        """"createdAt":"t","endAt":null,"id":"r1","offer":"off_1","startAt":null,""" +
        """"status":"COMPLETED"}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val redemption = client(http).setRedemptionFeedback(
      "r1",
      SetRedemptionFeedbackRequest(rating = 9, comment = "Superb."),
    )

    assertEquals(9, redemption.feedback?.rating)
    assertEquals("https://api.test/v1/redemptions/r1/feedback", http.requests[0].url)
    assertEquals("PUT", http.requests[0].method)
  }
}
