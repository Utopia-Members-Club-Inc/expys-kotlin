/**
 * Token refresh: supply a refresh hook and an expiry so the SDK refreshes the member
 * token proactively near expiry and reactively once on a 401. The hook must call YOUR
 * backend, which re-exchanges the Org-API-Key; the Org-API-Key never ships in the app.
 * Zero UI. Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.tokenrefresh

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.TokenRefresh
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = token,
      tokenExpiresAtMs = System.currentTimeMillis() + 5 * 60_000,
      refreshSkewMs = 60_000,
      refreshToken = {
        // Call YOUR backend, which re-exchanges the Org-API-Key, and return a fresh
        // token. TokenRefresh is constructed, not decoded, so your payload shape is
        // your concern. This stub just reuses the env token for illustration.
        TokenRefresh(accessToken = token, expiresAtMs = System.currentTimeMillis() + 5 * 60_000)
      },
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  val wallet = client.wallet()
  println("balance: ${wallet.balance}")
}
