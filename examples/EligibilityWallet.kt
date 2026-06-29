/**
 * Eligibility and wallet: read the member's tier and balances. Zero UI.
 * Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL and EXPYS_EXTERNAL_USER_ID (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.eligibilitywallet

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
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

  // externalUserID names the member when a machine token calls on their behalf.
  val eligibility = client.eligibility(externalUserID = System.getenv("EXPYS_EXTERNAL_USER_ID"))
  println("tier: ${eligibility.tier}")

  val wallet = client.wallet()
  println("balance: ${wallet.balance} ${wallet.currency.symbol}")
  println("received: ${wallet.amountReceived}, spent: ${wallet.amountSpent}")
}
