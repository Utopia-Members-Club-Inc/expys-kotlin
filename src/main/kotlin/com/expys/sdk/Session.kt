package com.expys.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of a token refresh: a fresh short-lived member token and its expiry. */
public data class TokenRefresh(
  /** The fresh short-lived member token. */
  val accessToken: String,
  /** Epoch-millis expiry of [accessToken], enabling the next proactive refresh. */
  val expiresAtMs: Long? = null,
)

/** Holds the member token and refreshes it via the configured suspend lambda. A
 * Mutex guards the mutable token state under concurrent requests. Internal: the
 * session is an implementation detail, not part of the public surface. */
internal class ExpysSession(
  initialToken: String,
  initialExpiresAtMs: Long?,
  private val refresh: (suspend () -> TokenRefresh)?,
  private val skewMs: Long,
  private val now: () -> Long,
) {
  private val mutex = Mutex()
  private var token: String = initialToken
  private var expiresAtMs: Long? = initialExpiresAtMs

  val canRefresh: Boolean get() = refresh != null

  suspend fun currentToken(): String = mutex.withLock { token }

  suspend fun shouldRefreshProactively(): Boolean = mutex.withLock {
    val expiry = expiresAtMs
    if (!canRefresh || expiry == null) false else now() + skewMs >= expiry
  }

  suspend fun refreshToken() {
    val refreshFn = refresh ?: throw ExpysException.NotConfigured("No refreshToken configured")
    val result = refreshFn()
    mutex.withLock {
      token = result.accessToken
      expiresAtMs = result.expiresAtMs
    }
  }
}
