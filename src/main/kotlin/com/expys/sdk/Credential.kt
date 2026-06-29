package com.expys.sdk

// Org-API-Key machine credentials are formatted `expys_<env>_<random>` (e.g.
// `expys_live_...`, `expys_sandbox_...`). A member token is a PASETO `v4.local.…`
// and never starts with `expys_`.
private const val MACHINE_CREDENTIAL_PREFIX = "expys_"

/**
 * Classifies a configured credential as a machine (Org-API-Key) credential. True
 * iff the token starts with `expys_`. Machine credentials are long-lived and not
 * refreshed, so the initially configured token is authoritative.
 */
internal fun isMachineCredential(token: String): Boolean = token.startsWith(MACHINE_CREDENTIAL_PREFIX)

/**
 * Fails fast, client-side, when a server-only method is called without a machine
 * credential (i.e. a member token was supplied). Throws [ExpysException.NotConfigured]
 * before any network call. The server also enforces this (a member token gets 403
 * via the route auth matrix), but the SDK rejects it without a round-trip.
 */
internal fun assertMachineCredential(token: String, method: String) {
  if (!isMachineCredential(token)) {
    throw ExpysException.NotConfigured(
      "`$method` is a server-only method and requires an Org-API-Key credential, not a member token. " +
        "Never embed an Org-API-Key in a client app.",
    )
  }
}
