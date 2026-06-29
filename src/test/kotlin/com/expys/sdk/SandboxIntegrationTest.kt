package com.expys.sdk

import com.expys.sdk.models.CreateRedemptionRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in end-to-end suite against a real Expys sandbox. Skipped unless
 * EXPYS_INTEGRATION=1 is set, so it never runs in normal CI. Provide a sandbox member
 * token via EXPYS_MEMBER_TOKEN (and optionally override the host with EXPYS_BASE_URL):
 *
 *   EXPYS_INTEGRATION=1 EXPYS_MEMBER_TOKEN=<sandbox token> \
 *     ./gradlew test --tests 'com.expys.sdk.SandboxIntegrationTest'
 */
@EnabledIfEnvironmentVariable(named = "EXPYS_INTEGRATION", matches = "1")
class SandboxIntegrationTest {
  private fun client(): ExpysClient {
    val token = System.getenv("EXPYS_MEMBER_TOKEN")
      ?: error("EXPYS_MEMBER_TOKEN must be set for the sandbox integration suite")
    val baseUrl = System.getenv("EXPYS_BASE_URL")
    val configuration = if (baseUrl != null) {
      ExpysConfiguration(token = token, environment = ExpysEnvironment.SANDBOX, baseUrl = baseUrl)
    } else {
      ExpysConfiguration(token = token, environment = ExpysEnvironment.SANDBOX)
    }
    return ExpysClient.create(configuration)
  }

  @Test
  fun browseRedeemGet() = runTest {
    val client = client()
    val offers = client.listOffers(limit = 5)
    assertTrue(offers.`data`.isNotEmpty(), "sandbox should return at least one offer")

    val redemption = client.createRedemption(CreateRedemptionRequest(offer = offers.`data`[0].id))
    val fetched = client.getRedemption(redemption.id)
    assertEquals(redemption.id, fetched.id)
  }

  @Test
  fun walletAndEligibility() = runTest {
    val client = client()
    val wallet = client.wallet()
    assertTrue(wallet.currency.symbol.isNotEmpty())

    val eligibility = client.eligibility()
    assertTrue(eligibility.tier.isNotEmpty())
  }
}
