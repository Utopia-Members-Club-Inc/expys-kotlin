/**
 * Environments: `environment` is informational - it is enforced server-side by the
 * token claim and only surfaced in the User-Agent. Use a sandbox token to hit sandbox.
 * Zero UI. Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.environments

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.ExpysEnvironment
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")
  val baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL

  val sandbox = ExpysClient.create(
    ExpysConfiguration(token = token, environment = ExpysEnvironment.SANDBOX, baseUrl = baseUrl),
  )
  val live = ExpysClient.create(
    ExpysConfiguration(token = token, environment = ExpysEnvironment.LIVE, baseUrl = baseUrl),
  )

  println("sandbox offers: ${sandbox.listOffers(limit = 1).`data`.size}")
  println("live offers: ${live.listOffers(limit = 1).`data`.size}")
}
