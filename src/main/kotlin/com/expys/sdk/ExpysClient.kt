package com.expys.sdk

import com.expys.sdk.models.AcceptTermsRequest
import com.expys.sdk.models.ConfirmPhoneVerificationRequest
import com.expys.sdk.models.ConfirmPhoneVerificationResponse
import com.expys.sdk.models.CreateInterestRequest
import com.expys.sdk.models.CreateInterestResponse
import com.expys.sdk.models.CreateRedemptionRequest
import com.expys.sdk.models.CreateWebhookRequest
import com.expys.sdk.models.CreditWalletRequest
import com.expys.sdk.models.CreditWalletResponse
import com.expys.sdk.models.DeleteWebhookResponse
import com.expys.sdk.models.GetAnalyticsOffersResponse
import com.expys.sdk.models.GetAnalyticsSummaryResponse
import com.expys.sdk.models.GetAnalyticsTimeseriesResponse
import com.expys.sdk.models.GetBalanceResponse
import com.expys.sdk.models.GetTermsContentResponse
import com.expys.sdk.models.GetTermsResponse
import com.expys.sdk.models.ListConversationsResponse
import com.expys.sdk.models.ListMembersResponse
import com.expys.sdk.models.ListMessagesResponse
import com.expys.sdk.models.ListRedemptionsResponse
import com.expys.sdk.models.ListTransactionsResponse
import com.expys.sdk.models.MemberEligibility
import com.expys.sdk.models.MemberSummary
import com.expys.sdk.models.Message
import com.expys.sdk.models.OfferList
import com.expys.sdk.models.Redemption
import com.expys.sdk.models.RemoveMemberResponse
import com.expys.sdk.models.SendMessageRequest
import com.expys.sdk.models.SendMessageResponse
import com.expys.sdk.models.SetInterestIntakeRequest
import com.expys.sdk.models.SetMemberRequest
import com.expys.sdk.models.SetMemberResponse
import com.expys.sdk.models.SetRedemptionFeedbackRequest
import com.expys.sdk.models.StartPhoneVerificationResponse
import com.expys.sdk.models.TokenExchangeRequest
import com.expys.sdk.models.TokenGrant
import com.expys.sdk.models.Wallet
import com.expys.sdk.models.WebhookEndpointList
import com.expys.sdk.models.WebhookEndpointWithSecret
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal inline fun <reified T> decode(json: Json, body: String): T = try {
  json.decodeFromString<T>(body)
} catch (error: Exception) {
  throw ExpysException.Decoding(error.message ?: "Failed to decode response")
}

private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** The Expys data SDK client. Configure once with a short-lived member token and
 * an optional refresh closure; every call retries 429/5xx with backoff and sends
 * an idempotency key on writes. */
public class ExpysClient internal constructor(
  private val transport: Transport,
  private val streamTransport: StreamTransport,
  private val json: Json,
  // The credential the client was configured with. Server-mode methods check it is
  // a machine (Org-API-Key) credential before issuing any request. Machine
  // credentials are long-lived and never refreshed, so this is authoritative.
  private val configuredToken: String,
) {
  /**
   * Browse available offers. Cursor-paginate with [cursor] until the response's
   * `nextCursor` is null.
   * @param limit Maximum number of offers to return.
   * @param cursor Pagination cursor from a previous response's `nextCursor`.
   * @throws ExpysException.Api on a non-2xx response.
   */
  public suspend fun listOffers(limit: Int? = null, cursor: String? = null): OfferList = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/offers",
      query = mapOf("limit" to limit?.toString(), "cursor" to cursor),
    ),
  )

  /**
   * Book (request) an offer for the member. Sends an `Idempotency-Key` so a retry
   * replays rather than double-books.
   * @param input The offer to redeem (and optionally the externalUserID a machine
   *   token acts for).
   * @param idempotencyKey Override the auto-generated key (e.g. to retry across
   *   process restarts).
   * @throws ExpysException.Api with `kind == ApiErrorKind.CONFLICT` (code
   *   `REDEMPTION_ALREADY_EXISTS`) on 409 when the member already booked the offer,
   *   or `kind == ApiErrorKind.VALIDATION` with `code == "INSUFFICIENT_POINTS"` on
   *   422 when the wallet balance is too low.
   */
  public suspend fun createRedemption(input: CreateRedemptionRequest, idempotencyKey: String? = null): Redemption {
    val body = json.encodeToString(CreateRedemptionRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "POST",
        path = "/v1/redemptions",
        body = body,
        idempotencyKey = idempotencyKey ?: generateIdempotencyKey(),
      ),
    )
  }

  /** Read a redemption by its id. @throws ExpysException.Api (404 if not found). */
  /**
   * Register interest in an experience: opens a concierge conversation and carries the
   * member's dates, with **no points debited and no inventory held**. Sends an
   * `Idempotency-Key` so a retry replays rather than double-registers.
   *
   * Dietary, allergy and accessibility answers are not accepted here - send them to
   * [setInterestIntake], which does not store them.
   */
  public suspend fun createInterest(
    input: CreateInterestRequest,
    idempotencyKey: String? = null,
  ): CreateInterestResponse {
    val body = json.encodeToString(CreateInterestRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "POST",
        path = "/v1/interests",
        body = body,
        idempotencyKey = idempotencyKey ?: generateIdempotencyKey(),
      ),
    )
  }

  /** Read an interest and its intake. */
  public suspend fun getInterest(id: String): CreateInterestResponse =
    decode(json, transport.execute(method = "GET", path = "/v1/interests/${encodePathSegment(id)}"))

  /**
   * Replace an interest's intake. Send the complete answer each time - a partial merge
   * over a set of dates has no sane meaning.
   *
   * The dietary, allergy and accessibility fields are forwarded to the concierge team and
   * **never stored**: you can send them, you cannot read them back, and the response
   * records only when they were sent.
   */
  public suspend fun setInterestIntake(id: String, input: SetInterestIntakeRequest): CreateInterestResponse {
    val body = json.encodeToString(SetInterestIntakeRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "PUT",
        path = "/v1/interests/${encodePathSegment(id)}/intake",
        body = body,
      ),
    )
  }

  /**
   * Send a verification code to the number on an interest's intake.
   *
   * Until a number is verified it is display-only and the concierge team will not text
   * it: an unverified number is one typo away from sending a stranger somebody else's
   * itinerary.
   */
  public suspend fun startPhoneVerification(id: String): StartPhoneVerificationResponse = decode(
    json,
    transport.execute(
      method = "POST",
      path = "/v1/interests/${encodePathSegment(id)}/phone/verification",
    ),
  )

  /**
   * Confirm a phone verification code.
   *
   * `verified == false` is **not** an error: the code was wrong or expired, which is an
   * ordinary thing for somebody to do. Ask again.
   */
  public suspend fun confirmPhoneVerification(
    id: String,
    input: ConfirmPhoneVerificationRequest,
  ): ConfirmPhoneVerificationResponse {
    val body = json.encodeToString(ConfirmPhoneVerificationRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "PUT",
        path = "/v1/interests/${encodePathSegment(id)}/phone/verification",
        body = body,
      ),
    )
  }

  /**
   * List the legal documents this member has and has not accepted.
   *
   * Metadata only - safe to call on every launch. The document text is a separate,
   * cacheable call: see [getTermsContent].
   *
   * `acceptedAt` is null while a document is outstanding, including when the member
   * accepted an earlier version of it.
   */
  public suspend fun getTerms(externalUserID: String? = null): GetTermsResponse = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/terms",
      query = mapOf("externalUserID" to externalUserID),
    ),
  )

  /**
   * Read one version's text, rendered for your organisation.
   *
   * A published version never changes, so this response is stable forever and is served
   * `Cache-Control: immutable`. Fetch it when the consent sheet opens, not on launch -
   * the terms of service are around 28kB.
   */
  public suspend fun getTermsContent(version: String): GetTermsContentResponse = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/terms/${encodePathSegment(version)}/content",
    ),
  )

  /**
   * Record that the member accepted one or more document versions. Sends an
   * `Idempotency-Key` so a retry replays rather than double-records.
   *
   * Re-accepting a version already on file is a no-op, not an error. The response is the
   * same shape as [getTerms], so a consent sheet can dismiss without a second call.
   */
  public suspend fun acceptTerms(input: AcceptTermsRequest, idempotencyKey: String? = null): GetTermsResponse {
    val body = json.encodeToString(AcceptTermsRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "POST",
        path = "/v1/terms/acceptance",
        body = body,
        idempotencyKey = idempotencyKey ?: generateIdempotencyKey(),
      ),
    )
  }

  /**
   * Record a member's score for a completed experience. 0-10, the NPS scale.
   *
   * Throws with code `REDEMPTION_NOT_COMPLETED` when the experience has not happened
   * yet; its `details.status` names the current one.
   */
  public suspend fun setRedemptionFeedback(id: String, input: SetRedemptionFeedbackRequest): Redemption {
    val body = json.encodeToString(SetRedemptionFeedbackRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "PUT",
        path = "/v1/redemptions/${encodePathSegment(id)}/feedback",
        body = body,
      ),
    )
  }

  public suspend fun getRedemption(id: String): Redemption =
    decode(json, transport.execute(method = "GET", path = "/v1/redemptions/${encodePathSegment(id)}"))

  /**
   * List the member's redemptions. Cursor-paginate with [cursor] until the
   * response's `nextCursor` is null; filter by lifecycle [status].
   * @param status Lifecycle status filter (`SUBMITTED`, `OPEN`, `AWAITING_VENDOR`,
   *   `AWAITING_CUSTOMER`, `PURCHASED`, `CANCELED`, `COMPLETED`).
   * @param limit Maximum number of redemptions to return (1-100).
   * @param cursor Pagination cursor from a previous response's `nextCursor`.
   * @param externalUserID Names the member when a machine token calls on their behalf.
   * @throws ExpysException.Api on a non-2xx response.
   */
  public suspend fun listRedemptions(
    status: String? = null,
    limit: Int? = null,
    cursor: String? = null,
    externalUserID: String? = null,
  ): ListRedemptionsResponse = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/redemptions",
      query = mapOf(
        "status" to status,
        "limit" to limit?.toString(),
        "cursor" to cursor,
        "externalUserID" to externalUserID,
      ),
    ),
  )

  /**
   * List the member's wallet transactions (the points ledger). Cursor-paginate
   * with [cursor] until the response's `nextCursor` is null.
   * @param limit Maximum number of transactions to return.
   * @param cursor Pagination cursor from a previous response's `nextCursor`.
   * @param externalUserID Names the member when a machine token calls on their behalf.
   * @throws ExpysException.Api on a non-2xx response.
   */
  public suspend fun walletTransactions(
    limit: Int? = null,
    cursor: String? = null,
    externalUserID: String? = null,
  ): ListTransactionsResponse = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/wallet/transactions",
      query = mapOf(
        "limit" to limit?.toString(),
        "cursor" to cursor,
        "externalUserID" to externalUserID,
      ),
    ),
  )

  /**
   * List the member's conversations.
   * @param externalUserID Names the member when a machine token calls on their behalf.
   * @throws ExpysException.Api on a non-2xx response.
   */
  public suspend fun listConversations(externalUserID: String? = null): ListConversationsResponse = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/conversations",
      query = mapOf("externalUserID" to externalUserID),
    ),
  )

  /**
   * List the messages in a conversation. Cursor-paginate with [cursor] until the
   * response's `nextCursor` is null.
   * @param id The conversation id.
   * @param limit Maximum number of messages to return.
   * @param cursor Pagination cursor from a previous response's `nextCursor`.
   * @param externalUserID Names the member when a machine token calls on their behalf.
   * @throws ExpysException.Api on a non-2xx response.
   */
  public suspend fun listMessages(
    id: String,
    limit: Int? = null,
    cursor: String? = null,
    externalUserID: String? = null,
  ): ListMessagesResponse = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/conversations/${encodePathSegment(id)}/messages",
      query = mapOf(
        "limit" to limit?.toString(),
        "cursor" to cursor,
        "externalUserID" to externalUserID,
      ),
    ),
  )

  /**
   * Send a message into a conversation. Sends an `Idempotency-Key` so a retry
   * replays rather than double-posts.
   * @param id The conversation id.
   * @param message The message body to send.
   * @param idempotencyKey Override the auto-generated key (e.g. to retry across
   *   process restarts).
   * @throws ExpysException.Api on a non-2xx response.
   */
  public suspend fun sendMessage(id: String, message: String, idempotencyKey: String? = null): SendMessageResponse {
    val body = json.encodeToString(SendMessageRequest.serializer(), SendMessageRequest(message = message))
    return decode(
      json,
      transport.execute(
        method = "POST",
        path = "/v1/conversations/${encodePathSegment(id)}/messages",
        body = body,
        idempotencyKey = idempotencyKey ?: generateIdempotencyKey(),
      ),
    )
  }

  /**
   * Stream new, member-visible messages in a conversation over Server-Sent Events
   * as they arrive. Returns a cold [Flow] of [Message]; collect it to subscribe.
   * History is not replayed - pair this with [listMessages] for the backlog. The
   * flow reconnects with backoff on transient failures and refreshes once on a
   * 401; it throws an [ExpysException.Api] on a permanent error (`FORBIDDEN` /
   * `NOT_FOUND`, or `UNAUTHORIZED` after a failed refresh). Cancelling collection
   * tears down the connection. Member-only; takes no `externalUserID`.
   * @param id The conversation id.
   * @return A cold [Flow] of new [Message].
   */
  public fun streamMessages(id: String): Flow<Message> = streamTransport.streamMessages(id)

  /**
   * The member's eligibility (tier + wallet).
   * @param externalUserID Names the member when a machine token calls on their behalf.
   */
  public suspend fun eligibility(externalUserID: String? = null): MemberEligibility = decode(
    json,
    transport.execute(
      method = "GET",
      path = "/v1/eligibility",
      query = mapOf("externalUserID" to externalUserID),
    ),
  )

  /** The member's wallet (balances). */
  public suspend fun wallet(): Wallet = decode(json, transport.execute(method = "GET", path = "/v1/wallet"))

  // Server-mode methods require an Org-API-Key machine credential. Each guards the
  // configured token BEFORE any request and throws [ExpysException.NotConfigured]
  // when a member token was supplied. The server also 403s a member token, but the
  // SDK fails fast.

  /**
   * Exchange this org's credential for a short-lived member token. Sends an
   * `Idempotency-Key` so a retry replays rather than re-mints. Server-only.
   * @param input The member to mint a token for (`externalUserID`) plus optional profile fields.
   * @param idempotencyKey Override the auto-generated key.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun exchangeToken(input: TokenExchangeRequest, idempotencyKey: String? = null): TokenGrant {
    assertMachineCredential(configuredToken, "exchangeToken")
    val body = json.encodeToString(TokenExchangeRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "POST",
        path = "/v1/auth/exchange",
        body = body,
        idempotencyKey = idempotencyKey ?: generateIdempotencyKey(),
      ),
    )
  }

  /**
   * Credit points to a member's wallet. Sends an `Idempotency-Key` so a retry
   * replays rather than double-credits. Server-only.
   * @param input The [CreditWalletRequest] (`amount`, `externalUserID`, optional `reason`).
   * @param idempotencyKey Override the auto-generated key.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun creditPoints(input: CreditWalletRequest, idempotencyKey: String? = null): CreditWalletResponse {
    assertMachineCredential(configuredToken, "creditPoints")
    val body = json.encodeToString(CreditWalletRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "POST",
        path = "/v1/wallet/credit",
        body = body,
        idempotencyKey = idempotencyKey ?: generateIdempotencyKey(),
      ),
    )
  }

  /**
   * Upsert a member's profile (tier, display name, attributes) by their external
   * id. Idempotent by HTTP semantics (PUT), so no idempotency key is sent. Server-only.
   * @param externalUserID The member's external user id.
   * @param input The fields to upsert.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun setMember(externalUserID: String, input: SetMemberRequest): SetMemberResponse {
    assertMachineCredential(configuredToken, "setMember")
    val body = json.encodeToString(SetMemberRequest.serializer(), input)
    return decode(
      json,
      transport.execute(method = "PUT", path = "/v1/members/${encodePathSegment(externalUserID)}", body = body),
    )
  }

  /**
   * Read a member's profile by their external id. Server-only.
   * @param externalUserID The member's external user id.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun getMember(externalUserID: String): MemberSummary {
    assertMachineCredential(configuredToken, "getMember")
    return decode(json, transport.execute(method = "GET", path = "/v1/members/${encodePathSegment(externalUserID)}"))
  }

  /**
   * List the org's members, newest-first. Cursor-paginate with [cursor] until the
   * response's `nextCursor` is null. Server-only.
   * @param tier Return only members whose effective tier matches this value exactly.
   * @param limit Maximum number of members to return (1-100).
   * @param cursor Pagination cursor from a previous response's `nextCursor`.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun listMembers(
    tier: String? = null,
    limit: Int? = null,
    cursor: String? = null,
  ): ListMembersResponse {
    assertMachineCredential(configuredToken, "listMembers")
    return decode(
      json,
      transport.execute(
        method = "GET",
        path = "/v1/members",
        query = mapOf(
          "tier" to tier,
          "limit" to limit?.toString(),
          "cursor" to cursor,
        ),
      ),
    )
  }

  /**
   * Remove (archive) a member by their external id. Idempotent by HTTP semantics
   * (DELETE), so no idempotency key is sent. Server-only.
   * @param externalUserID The member's external user id.
   * @param retainBalance Keep the member's points balance instead of clearing it.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun removeMember(externalUserID: String, retainBalance: Boolean? = null): RemoveMemberResponse {
    assertMachineCredential(configuredToken, "removeMember")
    return decode(
      json,
      transport.execute(
        method = "DELETE",
        path = "/v1/members/${encodePathSegment(externalUserID)}",
        query = mapOf("retainBalance" to retainBalance?.toString()),
      ),
    )
  }

  /**
   * Org-wide analytics rollups (members, points minted/spent, completion rate). Server-only.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun analyticsSummary(): GetAnalyticsSummaryResponse {
    assertMachineCredential(configuredToken, "analyticsSummary")
    return decode(json, transport.execute(method = "GET", path = "/v1/analytics/summary"))
  }

  /**
   * Per-offer analytics rollups for the org. Server-only.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun analyticsOffers(): GetAnalyticsOffersResponse {
    assertMachineCredential(configuredToken, "analyticsOffers")
    return decode(json, transport.execute(method = "GET", path = "/v1/analytics/offers"))
  }

  /**
   * The org's points balance, credit limit, lifetime pool spend, and settlement mode.
   * Server-only; requires the `BILLING_READ` scope.
   *
   * In `ORG_POOL` mode no per-redemption webhook fires, so poll this to track a balance
   * your VIPs' bookings are drawing down. An org with no pool yet reports zeros rather
   * than erroring.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun balance(): GetBalanceResponse {
    assertMachineCredential(configuredToken, "balance")
    return decode(json, transport.execute(method = "GET", path = "/v1/balance"))
  }

  /**
   * Time-bucketed analytics over a window. Server-only.
   * @param from Start of the window, an ISO-8601 date-time string. Required.
   * @param to End of the window, an ISO-8601 date-time string. Required.
   * @param interval Bucket interval: `day`, `week`, or `month`. Required.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun analyticsTimeseries(from: String, to: String, interval: String): GetAnalyticsTimeseriesResponse {
    assertMachineCredential(configuredToken, "analyticsTimeseries")
    return decode(
      json,
      transport.execute(
        method = "GET",
        path = "/v1/analytics/timeseries",
        query = mapOf("from" to from, "to" to to, "interval" to interval),
      ),
    )
  }

  /**
   * Register a webhook endpoint. Sends an `Idempotency-Key` so a retry replays
   * rather than double-registers. Server-only.
   * @param input The webhook `events` and delivery `url`.
   * @param idempotencyKey Override the auto-generated key.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun createWebhook(
    input: CreateWebhookRequest,
    idempotencyKey: String? = null,
  ): WebhookEndpointWithSecret {
    assertMachineCredential(configuredToken, "createWebhook")
    val body = json.encodeToString(CreateWebhookRequest.serializer(), input)
    return decode(
      json,
      transport.execute(
        method = "POST",
        path = "/v1/webhooks",
        body = body,
        idempotencyKey = idempotencyKey ?: generateIdempotencyKey(),
      ),
    )
  }

  /**
   * List the org's webhook endpoints. Server-only.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun listWebhooks(): WebhookEndpointList {
    assertMachineCredential(configuredToken, "listWebhooks")
    return decode(json, transport.execute(method = "GET", path = "/v1/webhooks"))
  }

  /**
   * Delete a webhook endpoint by its id. Server-only.
   * @param id The webhook id.
   * @throws ExpysException.NotConfigured when configured with a member token.
   */
  public suspend fun deleteWebhook(id: String): DeleteWebhookResponse {
    assertMachineCredential(configuredToken, "deleteWebhook")
    return decode(json, transport.execute(method = "DELETE", path = "/v1/webhooks/${encodePathSegment(id)}"))
  }

  /** Factory entry point for [ExpysClient]. */
  public companion object {
    /**
     * Creates a client from [configuration]. Optionally inject a custom [httpClient]
     * (for instrumentation, metrics, or a different transport); the default is an
     * OkHttp-backed engine.
     */
    public fun create(configuration: ExpysConfiguration, httpClient: HttpClient? = null): ExpysClient {
      val json = Json {
        ignoreUnknownKeys = true
        serializersModule = expysSerializersModule
      }
      val session = ExpysSession(
        initialToken = configuration.token,
        initialExpiresAtMs = configuration.tokenExpiresAtMs,
        refresh = configuration.refreshToken,
        skewMs = configuration.refreshSkewMs,
        now = { System.currentTimeMillis() },
      )
      val engine = httpClient ?: OkHttpEngine(buildOkHttpClient(configuration))
      val userAgent = ExpysVersion.buildUserAgent(
        configuration.environment,
        configuration.orgId,
        configuration.userAgentSuffix,
      )
      val transport = Transport(
        baseUrl = configuration.baseUrl,
        session = session,
        http = engine,
        maxRetries = configuration.maxRetries,
        userAgent = userAgent,
        sleep = { delay(it) },
        now = { System.currentTimeMillis() },
        random = { Math.random() },
      )
      val streamTransport = StreamTransport(
        baseUrl = configuration.baseUrl,
        session = session,
        http = engine,
        userAgent = userAgent,
        json = json,
        sleep = { delay(it) },
        now = { System.currentTimeMillis() },
        random = { Math.random() },
      )
      return ExpysClient(transport, streamTransport, json, configuration.token)
    }

    private fun buildOkHttpClient(configuration: ExpysConfiguration): OkHttpClient {
      val builder = OkHttpClient.Builder()
      configuration.timeoutMs?.let { builder.callTimeout(it, TimeUnit.MILLISECONDS) }
      return builder.build()
    }
  }
}
