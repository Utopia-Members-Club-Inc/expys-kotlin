package com.expys.sdk

/** The credential's environment. Sandbox and live share one host; the
 * environment is enforced server-side as a token claim. */
public enum class ExpysEnvironment {
  /** The sandbox environment, for development and testing. */
  SANDBOX,

  /** The production (live) environment. */
  LIVE,
}

/** Configures an [ExpysClient]. Holds a short-lived member token obtained by your
 * backend via POST /v1/auth/exchange; the Org-API-Key never ships in the app. */
public data class ExpysConfiguration(
  /** Short-lived member token your backend obtained from `POST /v1/auth/exchange`. */
  val token: String,
  /** Target environment. Informational only: it is enforced server-side by the token
   * claim and surfaced in the `User-Agent`; the SDK does not route by it. */
  val environment: ExpysEnvironment = ExpysEnvironment.LIVE,
  /** API base URL. Sandbox and live share one host; override only for local testing. */
  val baseUrl: String = DEFAULT_BASE_URL,
  /** Optional org id, folded into the `User-Agent` for server-side attribution. */
  val orgId: String? = null,
  /** Epoch-millis expiry of [token]; set it to enable proactive refresh near expiry. */
  val tokenExpiresAtMs: Long? = null,
  /** Called to obtain a fresh token; should hit your backend, which re-exchanges
   * the Org-API-Key. Without it, an expired token simply 401s. */
  val refreshToken: (suspend () -> TokenRefresh)? = null,
  /** Extra attempts on a retryable (429/5xx) response. Default 2 (3 attempts total). */
  val maxRetries: Int = 2,
  /** Per-request timeout in milliseconds; unset means no SDK-imposed ceiling. */
  val timeoutMs: Long? = null,
  /** Refresh this many milliseconds before [tokenExpiresAtMs]. Default 30s. */
  val refreshSkewMs: Long = 30_000,
  /** Appended to the SDK `User-Agent`, e.g. to identify the host application. */
  val userAgentSuffix: String? = null,
) {
  /** Default configuration values. */
  public companion object {
    /** Default API host (the canonical public domain). Sandbox and live share
     * one host; the environment is a token claim, not a host switch. */
    public const val DEFAULT_BASE_URL: String = "https://api.expys.com"
  }
}
