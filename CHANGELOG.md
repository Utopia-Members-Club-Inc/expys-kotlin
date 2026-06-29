# Changelog

All notable changes to the Expys Kotlin SDK (`com.expys:sdk`) are documented here.
This project follows [Semantic Versioning](https://semver.org) and the
[Expys SDK versioning and deprecation policy](https://docs.expys.com/guides/versioning).

## 0.1.0 - 2026-06-29

### Added

- **Server-mode methods** (server-only, require an Org-API-Key machine credential):
  `exchangeToken`, `creditPoints`, `setMember`, `getMember`, `removeMember`,
  `analyticsSummary`, `analyticsOffers`, `analyticsTimeseries`, `createWebhook`,
  `listWebhooks`, and `deleteWebhook` (parity with the TS and Swift SDKs).
  Calling one with a member token throws `ExpysException.NotConfigured` client-side
  before any request (the server also `403`s it). These add `PUT`/`DELETE`
  transport support.
- **Member-mode methods**: `listRedemptions`, `walletTransactions`,
  `listConversations`, `listMessages`, and `sendMessage` (parity with the TS and
  Swift SDKs).
- **`streamMessages(id)`**: a member-mode SSE stream of new conversation messages,
  returning a cold `Flow<Message>` (TypeScript `AsyncIterable`, Swift
  `AsyncThrowingStream`). It reconnects with backoff on transient failures,
  refreshes once on a `401`, and ends on a permanent error; cancelling collection
  tears down the connection.
- **New models** for the surface above: `Conversation`, `Message`, and the wallet
  `Transaction`, plus the member, analytics, and webhook types (`MemberSummary`,
  `SetMemberRequest`/`SetMemberResponse`, `CreditWalletRequest`/`CreditWalletResponse`,
  the `GetAnalytics*Response` shapes, `TokenExchangeRequest`/`TokenGrant`, and
  `CreateWebhookRequest`/`WebhookEndpoint`/`WebhookEndpointWithSecret`/
  `WebhookEndpointList`).
- **`Offer.pointsPrice`** — the points cost of a points-priced offer.
- **`ApiError.requestId`** — the server's `x-request-id`, for support correlation.
- `environment` and `orgId` are now folded into the `User-Agent` for attribution.
- **`generateIdempotencyKey()`** is now public, for pre-generating a key that makes a
  write retry-safe across process restarts (parity with the TS and Swift SDKs).
- **Public-API safety** — `explicitApi()` strict mode plus the Kotlin Binary
  Compatibility Validator with a committed `api/expys-sdk.api` dump and an `apiCheck`
  gate in CI.
- **Lint + static analysis** — ktlint (format + lint) and detekt, wired into `check`.
- **Android consumability** — a bundled `consumer-rules.pro` (R8/ProGuard keep rules)
  and a documented Android support story (JVM 17, OkHttp 4, core-library desugaring).
- **Docs** — KDoc on every public symbol; the API reference is generated with Dokka v2
  and a CI workflow publishes it to GitHub Pages. A real Dokka-generated javadoc jar is
  still published alongside the sources jar.
- **Build hygiene** — a version catalog (`gradle/libs.versions.toml`) for dependency and
  plugin versions.
- **Examples** — a full, compile-checked set under `examples/` (Pagination,
  ErrorHandling, TokenRefresh, Environments, Idempotency, Configuration,
  EligibilityWallet) alongside the existing BrowseRedeem.
- **Testing** — an opt-in sandbox integration suite (`EXPYS_INTEGRATION=1`), skipped in
  normal CI, plus broadened behavioural coverage.
- **Governance** — README badge row, `CONTRIBUTING.md`, package-level `SECURITY.md`, and
  a Codecov coverage upload in CI.

### Changed

- Coverage gate raised to line ≥95% / branch ≥85% (via Kover), enforced in CI.
- Dokka upgraded 1.9.20 -> 2.0.0 (V2 plugin).
- `ExpysSession` is now `internal` (it was never part of the intended public surface).
