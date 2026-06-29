package com.expys.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialTest {
  @Test
  fun classifiesOrgApiKeyAsMachineCredential() {
    assertTrue(isMachineCredential("expys_live_abc"))
    assertTrue(isMachineCredential("expys_sandbox_abc"))
  }

  @Test
  fun classifiesMemberTokenAsNotMachine() {
    assertFalse(isMachineCredential("v4.local.abc"))
    assertFalse(isMachineCredential(""))
    assertFalse(isMachineCredential("Expys_live_x"))
  }

  @Test
  fun assertDoesNotThrowForMachineCredential() {
    assertMachineCredential("expys_live_x", "creditPoints")
  }

  @Test
  fun assertThrowsNotConfiguredForMemberToken() {
    val error = assertFails { assertMachineCredential("v4.local.x", "creditPoints") }
    assertTrue(error is ExpysException.NotConfigured)
    assertTrue(error.detail.contains("creditPoints"))
    assertTrue(error.detail.contains("server-only"))
    assertTrue(error.detail.contains("Org-API-Key"))
  }

  private fun assertFails(block: () -> Unit): Throwable {
    try {
      block()
    } catch (error: Throwable) {
      return error
    }
    throw AssertionError("expected an exception")
  }
}
