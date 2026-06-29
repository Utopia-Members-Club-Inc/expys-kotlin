/**
 * Redemptions history and wallet ledger: the member-facing list read paths. Zero UI.
 * Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL and EXPYS_EXTERNAL_USER_ID (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.redemptionslist

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")
  val externalUserID = System.getenv("EXPYS_EXTERNAL_USER_ID")

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = token,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  // Cursor-paginate the member's open redemptions until nextCursor is null.
  var cursor: String? = null
  do {
    val page = client.listRedemptions(status = "OPEN", limit = 50, cursor = cursor, externalUserID = externalUserID)
    for (redemption in page.redemptions) {
      println("redemption ${redemption.id} [${redemption.status}]")
    }
    cursor = page.nextCursor
  } while (cursor != null)

  // The points ledger: each credit/debit on the member's wallet.
  val ledger = client.walletTransactions(limit = 50, externalUserID = externalUserID)
  for (transaction in ledger.transactions) {
    println("tx ${transaction.id}: ${transaction.type} ${transaction.amount} (${transaction.reason ?: "no reason"})")
  }
}
