package com.expys.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// / Proactive-refresh + refresh behaviour of the token session (3.2). now=1000ms,
// / skew=30ms throughout, so the refresh window opens at expiry-30ms.
class SessionTest {
  private fun session(expiresAtMs: Long? = null, refresh: (suspend () -> TokenRefresh)? = null) =
    ExpysSession("t0", expiresAtMs, refresh, skewMs = 30L, now = { 1000L })

  @Test
  fun doesNotRefreshWithoutAClosure() = runTest {
    assertFalse(session(expiresAtMs = 0L).shouldRefreshProactively())
  }

  @Test
  fun doesNotRefreshWithoutExpiry() = runTest {
    assertFalse(session(refresh = { TokenRefresh("t1") }).shouldRefreshProactively())
  }

  @Test
  fun refreshesWithinSkewOfExpiry() = runTest {
    // 1000 + 30 >= 1020 -> true.
    assertTrue(
      session(expiresAtMs = 1020L, refresh = { TokenRefresh("t1") }).shouldRefreshProactively(),
    )
  }

  @Test
  fun doesNotRefreshWhenFarFromExpiry() = runTest {
    // 1000 + 30 < 5000 -> false.
    assertFalse(
      session(expiresAtMs = 5000L, refresh = { TokenRefresh("t1") }).shouldRefreshProactively(),
    )
  }

  @Test
  fun refreshTokenUpdatesTheToken() = runTest {
    val session = session(refresh = { TokenRefresh("t1") })
    session.refreshToken()
    assertEquals("t1", session.currentToken())
  }

  @Test
  fun refreshTokenThrowsWhenNotConfigured() = runTest {
    assertFailsWith<ExpysException.NotConfigured> { session().refreshToken() }
  }

  @Test
  fun refreshTokenUpdatesExpiryWindow() = runTest {
    // A refresh that returns a far-future expiry closes the proactive-refresh window.
    val session = session(expiresAtMs = 1010L, refresh = { TokenRefresh("t1", expiresAtMs = 9_000L) })
    assertTrue(session.shouldRefreshProactively())

    session.refreshToken()

    assertEquals("t1", session.currentToken())
    assertFalse(session.shouldRefreshProactively())
  }

  @Test
  fun concurrentRefreshAndReadIsMutexSafe() = runBlocking {
    // Exercise the Mutex: many parallel refresh/read coroutines must not deadlock,
    // crash, or tear the guarded token/expiry state.
    val session = ExpysSession("t0", null, refresh = { TokenRefresh("t1") }, skewMs = 30L, now = { 1000L })

    (1..100).map {
      launch(Dispatchers.Default) {
        session.refreshToken()
        session.currentToken()
      }
    }.joinAll()

    assertEquals("t1", session.currentToken())
  }
}
