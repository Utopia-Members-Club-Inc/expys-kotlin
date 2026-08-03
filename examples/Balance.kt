/**
 * Balance: read your org's points balance, credit limit, and settlement mode.
 *
 * RUN THIS IN A SERVER/JVM BACKEND ONLY. Reading the balance needs an Org-API-Key
 * (`expys_live_...` / `expys_sandbox_...`) with the BILLING_READ scope. That key
 * is a secret and must NEVER ship in an Android app or any client.
 *
 * Which layer your redemptions debit depends on `settlementMode`:
 *  - MEMBER_WALLET: each VIP has their own wallet, which you fund by crediting
 *    points. Redemptions debit the VIP.
 *  - ORG_POOL: there are no per-VIP balances at all. Redemptions debit this
 *    org-level balance directly, so you never mirror or reconcile a VIP balance.
 *
 * In ORG_POOL mode no webhook fires per redemption, so poll this endpoint (or
 * subscribe to `org.points.low`) to know when to top up.
 *
 * Env: EXPYS_ORG_API_KEY (required), EXPYS_BASE_URL (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.balance

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.ExpysEnvironment
import com.expys.sdk.models.GetBalanceResponse
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val orgApiKey = System.getenv("EXPYS_ORG_API_KEY")
    ?: error(
      "Set EXPYS_ORG_API_KEY (your secret Org-API-Key, e.g. expys_live_...). " +
        "Run this on a backend only, never in a client app.",
    )

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = orgApiKey,
      environment = ExpysEnvironment.SANDBOX,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  val account = client.balance()

  println("settlement mode: ${account.settlementMode.value}")
  println("balance: ${account.balance} points")

  when (account.settlementMode) {
    GetBalanceResponse.SettlementMode.ORG_POOL -> {
      // Spendable headroom includes the credit limit: a postpaid org may overdraw
      // to -creditLimit before redemptions are refused with INSUFFICIENT_ORG_POINTS.
      val spendable = account.balance + account.creditLimit
      println("spendable now: $spendable points")
      println("lifetime spent from the pool: ${account.lifetimeSpent}")

      if (spendable <= 0) {
        println("Pool exhausted - redemptions will be refused until topped up.")
      }
    }
    GetBalanceResponse.SettlementMode.MEMBER_WALLET -> {
      // MEMBER_WALLET: this balance funds the points you credit to VIPs, and each
      // VIP's own wallet is what a redemption debits.
      println("VIP redemptions debit each member's wallet, not this balance.")
    }
  }
}
