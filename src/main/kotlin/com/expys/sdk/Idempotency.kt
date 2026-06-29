package com.expys.sdk

import java.util.UUID

/**
 * Generates a UUIDv4 idempotency key for a write.
 *
 * Writes (`createRedemption`) send one automatically; call this only to pre-generate
 * a key you can persist and reuse so a retry across process restarts replays the
 * original response rather than double-booking. Public for parity with the
 * TypeScript and Swift SDKs.
 */
public fun generateIdempotencyKey(): String = UUID.randomUUID().toString()
