package com.expys.sdk

import com.expys.sdk.models.Display
import com.expys.sdk.models.SetOfferPriceRequest
import com.expys.sdk.models.UpdateConfigurationRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The offer read, the authored-price writes, the configuration write and the
 * `available` filter on the offer list. */
class OffersMethodsTest {
  private fun machineClient(http: HttpClient): ExpysClient =
    ExpysClient.create(ExpysConfiguration(token = "expys_live_machine", baseUrl = "https://api.test"), http)

  private fun memberClient(http: HttpClient): ExpysClient =
    ExpysClient.create(ExpysConfiguration(token = "v4.local.member", baseUrl = "https://api.test"), http)

  private fun offerJson(source: String = "AUTHORED", amount: String = "39.5"): String =
    """{"addOns":[],"availability":{"isAvailable":true,"quantityRemaining":4},""" +
      """"description":"Two courtside seats.","disclaimer":null,""" +
      """"display":{"amount":$amount,"currency":{"name":"Credits","symbol":"C","unitsPerUSD":1},""" +
      """"source":"$source"},"endAt":null,"exclusions":[],"expiresAt":null,"id":"off 1",""" +
      """"images":[],"inclusions":[],"kind":"EXPERIENCE","location":null,"maxGuests":2,""" +
      """"pointsPrice":3950,"shortDescription":"Courtside","startAt":null,"title":"Courtside",""" +
      """"type":"SPORTS","valueUSD":null}"""

  @Test
  fun listOffersSendsAvailableTrue() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"data":[],"nextCursor":null}""")))

    machineClient(http).listOffers(limit = 5, available = true)

    val url = http.requests[0].url
    assertTrue(url.startsWith("https://api.test/v1/offers?"))
    assertTrue(url.contains("available=true"))
    assertTrue(url.contains("limit=5"))
  }

  @Test
  fun listOffersSendsAvailableFalse() = runTest {
    val http = FakeHttpClient(listOf(ok("""{"data":[],"nextCursor":null}""")))

    machineClient(http).listOffers(available = false)

    assertEquals("https://api.test/v1/offers?available=false", http.requests[0].url)
  }

  @Test
  fun getOfferGetsEncodedPathWithMemberToken() = runTest {
    val http = FakeHttpClient(listOf(ok(offerJson())))

    val offer = memberClient(http).getOffer("off 1")

    assertEquals("off 1", offer.id)
    assertEquals(39.5, offer.display?.amount)
    assertEquals(Display.Source.AUTHORED, offer.display?.source)
    assertEquals("GET", http.requests[0].method)
    assertEquals("https://api.test/v1/offers/off%201", http.requests[0].url)
    assertEquals(null, http.requests[0].body)
    assertEquals(null, http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun getOfferSurfacesNotFound() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("NOT_FOUND"), status = 404)))

    val error = assertFailsWith<ExpysException.Api> { memberClient(http).getOffer("off_gone") }

    assertEquals(ApiErrorKind.NOT_FOUND, error.error.kind)
    assertEquals(404, error.error.status)
  }

  @Test
  fun setOfferPricePutsAmountWithoutKey() = runTest {
    val http = FakeHttpClient(listOf(ok(offerJson())))

    val offer = machineClient(http).setOfferPrice("off 1", SetOfferPriceRequest(amount = 39.5))

    assertEquals(Display.Source.AUTHORED, offer.display?.source)
    assertEquals("PUT", http.requests[0].method)
    assertEquals("https://api.test/v1/offers/off%201/price", http.requests[0].url)
    assertEquals("""{"amount":39.5}""", http.requests[0].body)
    assertEquals("application/json", http.requests[0].headers["Content-Type"])
    assertEquals(null, http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun setOfferPriceSurfacesValidation() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("VALIDATION"), status = 422)))

    val error = assertFailsWith<ExpysException.Api> {
      machineClient(http).setOfferPrice("off_1", SetOfferPriceRequest(amount = -1.0))
    }

    assertEquals(ApiErrorKind.VALIDATION, error.error.kind)
    assertEquals("VALIDATION", error.error.code)
  }

  @Test
  fun deleteOfferPriceDeletesWithoutBodyOrKey() = runTest {
    val http = FakeHttpClient(listOf(ok(offerJson(source = "DERIVED", amount = "40"))))

    val offer = machineClient(http).deleteOfferPrice("off 1")

    assertEquals(Display.Source.DERIVED, offer.display?.source)
    assertEquals("DELETE", http.requests[0].method)
    assertEquals("https://api.test/v1/offers/off%201/price", http.requests[0].url)
    assertEquals(null, http.requests[0].body)
    assertEquals(null, http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun deleteOfferPriceSurfacesForbidden() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("FORBIDDEN"), status = 403)))

    val error = assertFailsWith<ExpysException.Api> { machineClient(http).deleteOfferPrice("off_1") }

    assertEquals(ApiErrorKind.FORBIDDEN, error.error.kind)
  }

  @Test
  fun updateConfigurationPutsBodyAndDecodesCurrency() = runTest {
    val json = """{"currencyName":"Credits","currencySymbol":"C","currencyUnitsPerUSD":100}"""
    val http = FakeHttpClient(listOf(ok(json)))

    val result = machineClient(http).updateConfiguration(
      UpdateConfigurationRequest(
        confirmReprice = true,
        currencyName = "Credits",
        currencySymbol = "C",
        currencyUnitsPerUSD = 100.0,
      ),
    )

    assertEquals("Credits", result.currencyName)
    assertEquals(100.0, result.currencyUnitsPerUSD)
    assertEquals("PUT", http.requests[0].method)
    assertEquals("https://api.test/v1/configuration", http.requests[0].url)
    assertEquals(
      """{"confirmReprice":true,"currencyName":"Credits","currencySymbol":"C","currencyUnitsPerUSD":100.0}""",
      http.requests[0].body,
    )
    assertEquals(null, http.requests[0].headers["Idempotency-Key"])
  }

  @Test
  fun updateConfigurationSurfacesFieldNotWritable() = runTest {
    val http = FakeHttpClient(listOf(ok(envelope("FIELD_NOT_WRITABLE"), status = 400)))

    val error = assertFailsWith<ExpysException.Api> {
      machineClient(http).updateConfiguration(UpdateConfigurationRequest(currencyName = "Credits"))
    }

    assertEquals(ApiErrorKind.OTHER, error.error.kind)
    assertEquals(400, error.error.status)
    assertEquals("FIELD_NOT_WRITABLE", error.error.code)
  }

  @Test
  fun machineOnlyWritesWithMemberTokenThrowNotConfiguredBeforeAnyRequest() = runTest {
    val http = FakeHttpClient(emptyList())
    val client = memberClient(http)

    val calls: List<suspend () -> Unit> = listOf(
      { client.setOfferPrice("off_1", SetOfferPriceRequest(amount = 1.0)) },
      { client.deleteOfferPrice("off_1") },
      { client.updateConfiguration(UpdateConfigurationRequest(currencyName = "Credits")) },
    )

    for (call in calls) {
      assertFailsWith<ExpysException.NotConfigured> { call() }
    }
    assertEquals(0, http.requests.size)
  }
}
