package com.expys.sdk

/** Embedded SDK + spec versions, surfaced in the User-Agent header for
 * server-side attribution. SDK versioning is independent semver, decoupled from
 * the spec version. */
public object ExpysVersion {
  /** This SDK's semantic version (independent of the spec version). */
  public const val SDK: String = "0.6.0"

  /** The Expys API spec version this SDK targets. */
  public const val SPEC: String = "1.0.0"

  /** Base `User-Agent` value, before per-client environment/org/suffix segments. */
  public const val USER_AGENT: String = "expys-sdk-kotlin/$SDK (spec/$SPEC)"

  /** Builds the per-client User-Agent, folding the environment and optional org
   * id into the comment for server-side attribution, then appending the
   * consumer's suffix. Format matches the TS and Swift SDKs:
   * `expys-sdk-kotlin/<sdk> (spec/<spec>; env=<env>[; org=<org>])[ <suffix>]`. */
  public fun buildUserAgent(environment: ExpysEnvironment, orgId: String?, suffix: String?): String {
    val segments = mutableListOf("spec/$SPEC", "env=${environment.name.lowercase()}")
    if (orgId != null) segments.add("org=$orgId")
    val base = "expys-sdk-kotlin/$SDK (${segments.joinToString("; ")})"
    return if (suffix != null) "$base $suffix" else base
  }
}
