/**
 * Server-mode: backend-only methods that require an Org-API-Key (machine
 * credential), e.g. minting member tokens, crediting points, upserting members,
 * and managing webhooks. Zero UI.
 *
 * RUN THIS IN A SERVER/JVM BACKEND ONLY (a service, a Ktor/Spring app, a CLI).
 * The Org-API-Key (`expys_live_...` / `expys_sandbox_...`) is a secret and must
 * NEVER ship in an Android app or any client. If you configure the SDK with a
 * member token (a `v4.local.…` PASETO) and call a server-mode method, the SDK
 * fails fast with ExpysException.NotConfigured BEFORE any network call (and the
 * server 403s it anyway).
 *
 * Env: EXPYS_ORG_API_KEY (required), EXPYS_BASE_URL and EXPYS_EXTERNAL_USER_ID (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.servermode

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.ExpysEnvironment
import com.expys.sdk.models.CreateWebhookRequest
import com.expys.sdk.models.CreditWalletRequest
import com.expys.sdk.models.SetMemberRequest
import com.expys.sdk.models.TokenExchangeRequest
import kotlinx.coroutines.runBlocking
import java.net.URI

fun main() = runBlocking {
  val orgApiKey = System.getenv("EXPYS_ORG_API_KEY")
    ?: error(
      "Set EXPYS_ORG_API_KEY (your secret Org-API-Key, e.g. expys_live_...). " +
        "Run this on a backend only, never in a client app.",
    )
  val externalUserID = System.getenv("EXPYS_EXTERNAL_USER_ID") ?: "user_42"

  // Configure the client with the machine credential as the token. Server-mode
  // methods are guarded against member tokens client-side.
  val client = ExpysClient.create(
    ExpysConfiguration(
      token = orgApiKey,
      environment = ExpysEnvironment.SANDBOX,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  // Mint a short-lived member token for your app to use (return this to the app,
  // never the Org-API-Key). Idempotent POST: a retry replays rather than re-mints.
  val grant = client.exchangeToken(TokenExchangeRequest(externalUserID = externalUserID))
  println("minted member token expiring at ${grant.expiresAt}")

  // Upsert the member's profile. PUT is idempotent by HTTP semantics (no key).
  val member = client.setMember(externalUserID, SetMemberRequest(displayName = "Ada Lovelace", tier = "gold"))
  println("member ${member.externalUserID} is now tier=${member.tier}")

  // Credit points to the member's wallet. Idempotent POST sends an Idempotency-Key.
  val credited = client.creditPoints(
    CreditWalletRequest(amount = 100, externalUserID = externalUserID, reason = "welcome bonus"),
  )
  println("new balance: ${credited.balance} ${credited.currency.symbol}")

  // Register a webhook. The signingSecret is shown ONLY on creation - store it now.
  val webhook = client.createWebhook(
    CreateWebhookRequest(events = listOf("redemption.created"), url = URI("https://example.com/expys/webhooks")),
  )
  println("webhook ${webhook.id} secret: ${webhook.signingSecret}")

  // Org-wide analytics rollups.
  val summary = client.analyticsSummary()
  println("members: ${summary.memberCount}, minted: ${summary.pointsMinted}")
}
