/**
 * Configuration: the full set of ExpysConfiguration options in one place. Zero UI.
 * Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL and EXPYS_ORG_ID (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.configuration

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.ExpysEnvironment
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = token,
      environment = ExpysEnvironment.SANDBOX,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
      orgId = System.getenv("EXPYS_ORG_ID"),
      maxRetries = 3,
      timeoutMs = 10_000,
      refreshSkewMs = 30_000,
      userAgentSuffix = "my-app/1.0",
    ),
  )

  println("offers: ${client.listOffers(limit = 1).`data`.size}")
}
