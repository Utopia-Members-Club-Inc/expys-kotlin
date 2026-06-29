/**
 * Idempotency: pre-generate a key so a write that is retried (even across process
 * restarts) replays the original response instead of double-booking. Zero UI.
 * Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.idempotency

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.generateIdempotencyKey
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

  val offer = client.listOffers(limit = 1).`data`.firstOrNull() ?: return@runBlocking

  // Persist this key; reusing it on a retry replays the original response.
  val key = generateIdempotencyKey()
  val first = client.createRedemption(CreateRedemptionRequest(offer = offer.id), idempotencyKey = key)
  val replay = client.createRedemption(CreateRedemptionRequest(offer = offer.id), idempotencyKey = key)

  println("created ${first.id}; replay returned ${replay.id}")
}
