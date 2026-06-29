/**
 * Streaming: subscribe to new conversation messages over SSE, with cancellation.
 * Zero UI.
 * Env: EXPYS_MEMBER_TOKEN and EXPYS_CONVERSATION_ID (required), EXPYS_BASE_URL (optional).
 *
 * streamMessages is member-only (no externalUserID) and pushes only NEW messages;
 * use listMessages for the backlog. The flow reconnects with backoff on transient
 * failures and ends on a permanent error.
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.streammessages

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")
  val conversationId = System.getenv("EXPYS_CONVERSATION_ID")
    ?: error("Set EXPYS_CONVERSATION_ID (a conversation to stream)")

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = token,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  // Optional: print the recent backlog first, then live-stream what follows.
  val history = client.listMessages(conversationId, limit = 20)
  for (message in history.messages) {
    println("[history ${message.authorID}] ${message.body ?: "(no body)"}")
  }

  println("listening for new messages (stops after 5)...")

  // collect() consumes the cold Flow lazily; `take(5)` cancels collection after
  // five messages, which tears down the underlying connection and any pending
  // reconnect timer - no leaked coroutines. In a real app you would cancel the
  // collecting coroutine's scope (e.g. a ViewModel scope) instead.
  var received = 0
  client.streamMessages(conversationId).take(5).collect { message ->
    received++
    println("[live ${message.authorID}] ${message.body ?: "(no body)"}")
  }

  println("done: received $received live message(s)")
}
