/**
 * Conversations: list threads, read their messages, and send a message. Zero UI.
 * Env: EXPYS_MEMBER_TOKEN (required), EXPYS_BASE_URL and EXPYS_EXTERNAL_USER_ID (optional).
 *
 * listConversations/listMessages accept an optional externalUserID (a machine token
 * acting on a member's behalf); sendMessage is member-only and takes no externalUserID.
 *
 * This file lives outside src/main (it is not part of the published jar); it is
 * reference documentation that compiles against the SDK API.
 */
package com.expys.sdk.examples.conversations

import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
  val token = System.getenv("EXPYS_MEMBER_TOKEN")
    ?: error("Set EXPYS_MEMBER_TOKEN (a member token from your backend's /v1/auth/exchange)")
  val externalUserID = System.getenv("EXPYS_EXTERNAL_USER_ID")

  val client = ExpysClient.create(
    ExpysConfiguration(
      token = token,
      baseUrl = System.getenv("EXPYS_BASE_URL") ?: ExpysConfiguration.DEFAULT_BASE_URL,
    ),
  )

  val conversations = client.listConversations(externalUserID = externalUserID)
  println("found ${conversations.conversations.size} conversations")

  val conversation = conversations.conversations.firstOrNull() ?: return@runBlocking
  println("reading: ${conversation.title ?: conversation.id}")

  val messages = client.listMessages(conversation.id, limit = 50, externalUserID = externalUserID)
  for (message in messages.messages) {
    println("[${message.authorID}] ${message.body ?: "(no body)"}")
  }

  // Writes auto-send an Idempotency-Key so a retry replays rather than double-posts.
  val result = client.sendMessage(conversation.id, "Hello from the SDK")
  println("message sent: ok=${result.ok}")
}
