/**
 * Pagination: walk every page of offers by following nextCursor until it is null.
 * Zero UI. Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL (optional).
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.pagination

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

  var cursor: String? = null
  var total = 0
  do {
    val page = client.listOffers(limit = 50, cursor = cursor)
    total += page.`data`.size
    page.`data`.forEach { println("- ${it.title} (${it.id})") }
    cursor = page.nextCursor
  } while (cursor != null)

  println("fetched $total offers across all pages")
}
