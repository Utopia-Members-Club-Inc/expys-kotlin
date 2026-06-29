/**
 * Error handling: branch on the typed ExpysException hierarchy and the coarse
 * ApiErrorKind / stable error code. Zero UI. Env: EXPYS_MEMBER_TOKEN (required),
 * EXPYS_BASE_URL (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.errorhandling

import com.expys.sdk.ApiErrorKind
import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.ExpysException
import com.expys.sdk.models.CreateRedemptionRequest
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = token,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  try {
    client.createRedemption(CreateRedemptionRequest(offer = "off_does_not_exist"))
  } catch (error: ExpysException.Api) {
    when (error.error.kind) {
      ApiErrorKind.NOT_FOUND -> println("offer not found (${error.error.code})")
      ApiErrorKind.CONFLICT -> println("already redeemed (${error.error.code})")
      ApiErrorKind.RATE_LIMITED -> println("rate limited; retry after ${error.error.retryAfterMs} ms")
      else -> println("api error ${error.error.status} ${error.error.code}: ${error.error.message}")
    }
    error.error.requestId?.let { println("request id: $it") }
  } catch (error: ExpysException.Timeout) {
    println("request timed out")
  } catch (error: ExpysException.Network) {
    println("network error: ${error.detail}")
  }
}
