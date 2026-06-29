/**
 * Reference sample: data-only browse -> eligibility -> redemption flow. Zero UI.
 *
 * CROSS-PHASE DEPENDENCY: this completes end-to-end against the seeded sandbox
 * tenant from Phase 4.6 (not yet built). Until then, point EXPYS_BASE_URL at a
 * stub. EXPYS_MEMBER_TOKEN is a short-lived member token your backend obtained
 * from POST /v1/auth/exchange.
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.browseredeem

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.ExpysEnvironment
import com.expys.sdk.ExpysException
import com.expys.sdk.models.CreateRedemptionRequest
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = token,
      environment = ExpysEnvironment.SANDBOX,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  val eligibility = client.eligibility()
  println("tier: ${eligibility.tier}, balance: ${eligibility.wallet.balance}")

  val offers = client.listOffers(limit = 10)
  println("browsed ${offers.`data`.size} offers")

  val offer = offers.`data`.firstOrNull() ?: return@runBlocking
  println("redeeming: ${offer.title} (${offer.id})")

  try {
    val redemption = client.createRedemption(CreateRedemptionRequest(offer = offer.id))
    println("redemption created: ${redemption.id} [${redemption.status}]")
    println("status now: ${client.getRedemption(redemption.id).status}")
  } catch (error: ExpysException.Api) {
    if (error.error.code == "REDEMPTION_ALREADY_EXISTS") {
      println("already redeemed")
    } else {
      throw error
    }
  }
}
