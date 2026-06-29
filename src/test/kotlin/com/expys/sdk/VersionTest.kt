package com.expys.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
  @Test
  fun userAgentFoldsEnvironmentOrgAndSuffix() {
    val ua = ExpysVersion.buildUserAgent(ExpysEnvironment.SANDBOX, "org_1", "myapp/1.0")
    assertTrue(ua.startsWith("expys-sdk-kotlin/"))
    assertTrue(ua.contains("env=sandbox"))
    assertTrue(ua.contains("org=org_1"))
    assertTrue(ua.endsWith("myapp/1.0"))
  }

  @Test
  fun userAgentDefaultsToLiveWithoutOrgOrSuffix() {
    val ua = ExpysVersion.buildUserAgent(ExpysEnvironment.LIVE, null, null)
    assertTrue(ua.contains("env=live"))
    assertFalse(ua.contains("org="))
  }
}
