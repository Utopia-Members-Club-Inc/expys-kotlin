<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/expys-logo-white.svg" />
    <img alt="Expys" src="assets/expys-logo-black.svg" width="150" />
  </picture>
</p>

<h1 align="center">Expys SDK · Kotlin/JVM</h1>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.expys/sdk"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.expys/sdk?style=flat-square&labelColor=000000&color=9EC1DE" /></a>
  <a href="https://kotlinlang.org"><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-9EC1DE?style=flat-square&labelColor=000000&logo=kotlin&logoColor=white" /></a>
  <a href="https://openjdk.org"><img alt="JVM" src="https://img.shields.io/badge/JVM-17%2B-9EC1DE?style=flat-square&labelColor=000000" /></a>
  <a href="#platform-support"><img alt="Android" src="https://img.shields.io/badge/Android-API%2021%2B-9EC1DE?style=flat-square&labelColor=000000&logo=android&logoColor=white" /></a>
  <a href="./LICENSE"><img alt="license MIT" src="https://img.shields.io/badge/license-MIT-9EC1DE?style=flat-square&labelColor=000000" /></a>
</p>

Official Expys data SDK for Kotlin/JVM. Coroutine-native (suspend), OkHttp,
kotlinx.serialization. A single pure-Kotlin/JVM artifact (no Android resources, no
Android Gradle Plugin) that runs identically for Android and server consumers.

> Beta. The generated models and transport are stable to use; the ergonomic layer is
> hardening during the rollout window. Pin an exact version in production and review
> the [versioning policy](https://docs.expys.com/guides/versioning).

## Getting Started

### Install

From Maven Central (`com.expys:sdk`):

```kotlin
// build.gradle.kts
dependencies {
  implementation("com.expys:sdk:0.1.0")
}
```

### Quick start

```kotlin
import com.expys.sdk.ExpysClient
import com.expys.sdk.ExpysConfiguration
import com.expys.sdk.TokenRefresh
import com.expys.sdk.models.CreateRedemptionRequest

val client = ExpysClient.create(
  ExpysConfiguration(
    // A short-lived member token your backend obtained from POST /v1/auth/exchange.
    token = memberToken,
    // Optional: refresh the token automatically. This must call YOUR backend,
    // which re-exchanges the Org-API-Key. The Org-API-Key never ships in the app.
    refreshToken = { TokenRefresh(accessToken = fetchFreshToken()) },
  ),
)

val offers = client.listOffers(limit = 20)
val redemption = client.createRedemption(CreateRedemptionRequest(offer = offers.`data`[0].id))
val status = client.getRedemption(redemption.id)
val eligibility = client.eligibility()
val wallet = client.wallet()
```

All calls are `suspend` functions; call them from a coroutine. Cancellation propagates
cleanly and is never retried.

## Authentication & Token Refresh

The SDK holds a **short-lived member token**, never the Org-API-Key. Your backend
exchanges its secret Org-API-Key for a member token (`POST /v1/auth/exchange`,
server-to-server) and hands it to the app. The SDK attaches it as a Bearer token and,
if you provide `refreshToken`, refreshes it automatically near expiry and on a `401`.

`refreshToken` must call **your** backend and return a
`TokenRefresh(accessToken = ..., expiresAtMs = ...)`:

```kotlin
val client = ExpysClient.create(
  ExpysConfiguration(
    token = memberToken,
    tokenExpiresAtMs = System.currentTimeMillis() + 5 * 60_000,
    refreshToken = {
      // Call YOUR backend, which re-exchanges the Org-API-Key, and return a fresh
      // token. TokenRefresh is constructed, not decoded, so your backend's payload
      // shape stays your concern.
      val fresh = myBackend.refresh()
      TokenRefresh(accessToken = fresh.accessToken, expiresAtMs = fresh.expiresAtMs)
    },
    refreshSkewMs = 60_000, // refresh ~60s before expiry
  ),
)
```

- Called **proactively** within `refreshSkewMs` (default 30s) of `tokenExpiresAtMs`,
  and **reactively** once on a `401`.
- If it throws, the error propagates to your call - the SDK does **not** retry a
  hard-failed refresh. Without `refreshToken`, an expired token simply `401`s.

See [`examples/TokenRefresh.kt`](examples/TokenRefresh.kt).

## Environments

`environment` (`LIVE` / `SANDBOX`, default `LIVE`) is **informational**: it is enforced
server-side by the token claim, so the SDK does not route by it (it only surfaces it in
the `User-Agent`). Use a sandbox token to hit sandbox.

## Offers

`listOffers` is cursor-paginated; follow `nextCursor` until it is null:

```kotlin
var cursor: String? = null
do {
  val page = client.listOffers(limit = 50, cursor = cursor)
  // handle page.`data`
  cursor = page.nextCursor
} while (cursor != null)
```

## Redemptions

```kotlin
val redemption = client.createRedemption(CreateRedemptionRequest(offer = offerId))
val status = client.getRedemption(redemption.id)

// Cursor-paginated history, filtered by lifecycle status.
val page = client.listRedemptions(status = "OPEN", limit = 50)
```

Writes send an `Idempotency-Key` automatically so a retry replays the original response
rather than double-booking. Override it (e.g. to make a write retry-safe across process
restarts) by pre-generating a key:

```kotlin
import com.expys.sdk.generateIdempotencyKey

val key = generateIdempotencyKey() // persist this, then reuse it on retry
client.createRedemption(CreateRedemptionRequest(offer = offerId), idempotencyKey = key)
```

`createRedemption` surfaces the typed failure modes by status + stable `code`: a `409` is
`ExpysException.Api` with `kind == ApiErrorKind.CONFLICT` (code `REDEMPTION_ALREADY_EXISTS`)
when the member already booked the offer, and a `422` is `ExpysException.Api` with
`code == "INSUFFICIENT_POINTS"` when the wallet balance is too low (see [Errors](#errors)).

`listRedemptions` is cursor-paginated and filters by lifecycle `status` (`SUBMITTED`,
`OPEN`, `AWAITING_VENDOR`, `AWAITING_CUSTOMER`, `PURCHASED`, `CANCELED`, `COMPLETED`).
`externalUserID` names the member when a machine token reads on their behalf.

## Eligibility

```kotlin
// externalUserID names the member when a machine token calls on their behalf.
val eligibility = client.eligibility(externalUserID = null)
println("${eligibility.tier} ${eligibility.wallet.balance}")
```

## Wallet

```kotlin
val wallet = client.wallet()
println("${wallet.balance} ${wallet.amountReceived} ${wallet.amountSpent} ${wallet.currency.symbol}")

// The cursor-paginated points ledger (each credit/debit).
val ledger = client.walletTransactions(limit = 50)
```

`walletTransactions` accepts an optional `externalUserID` (a machine token reading on a
member's behalf).

## Conversations

```kotlin
val conversations = client.listConversations(externalUserID = null)
val id = conversations.conversations[0].id

// Cursor-paginated messages in a conversation.
val messages = client.listMessages(id, limit = 50)

// A member-only write; it auto-sends an Idempotency-Key (override it per call).
val result = client.sendMessage(id, "Hello")
println(result.ok)
```

`listConversations` and `listMessages` accept an optional `externalUserID` (a machine
token acting on a member's behalf). `sendMessage` is member-only and takes no
`externalUserID`.

### Streaming

`streamMessages(id)` returns a cold `Flow<Message>` of new, member-visible messages
over Server-Sent Events. Collect it to subscribe; history is not replayed (pair it
with `listMessages` for the backlog). The flow reconnects with backoff on transient
failures (network drop / `5xx` / `429`, honoring `Retry-After`) and refreshes once on
a `401`; it throws an `ExpysException.Api` on a permanent error (`FORBIDDEN` /
`NOT_FOUND`, or `UNAUTHORIZED` after a failed refresh). Member-only - no `externalUserID`.

```kotlin
client.streamMessages("cnv_123").collect { message ->
  println(message.body)
}
```

Cancelling collection (e.g. cancelling the collecting coroutine's scope) tears down the
underlying connection and any pending reconnect timer. This is the one intentional
concurrency difference across the SDKs (TypeScript returns an `AsyncIterable`, Swift an
`AsyncStream`); see the `StreamMessages` example and
[SDK differences](https://docs.expys.com/sdks/differences).

## Server vs app methods (server-only)

Most methods above run with a short-lived **member token** and are safe to call
from your app. The following methods are **server-only**: they require an
**Org-API-Key** machine credential (`expys_live_...` / `expys_sandbox_...`) and
**must run only on your backend** (a JVM service, a Ktor/Spring app, a CLI). Never
ship an Org-API-Key in an Android app or any client.

| Method                                                          | Endpoint                          |
| --------------------------------------------------------------- | --------------------------------- |
| `client.exchangeToken(input, idempotencyKey)`                   | `POST /v1/auth/exchange`          |
| `client.creditPoints(input, idempotencyKey)`                    | `POST /v1/wallet/credit`          |
| `client.setMember(externalUserID, input)`                       | `PUT /v1/members/{externalUserID}`    |
| `client.getMember(externalUserID)`                              | `GET /v1/members/{externalUserID}`    |
| `client.removeMember(externalUserID, retainBalance)`            | `DELETE /v1/members/{externalUserID}` |
| `client.analyticsSummary()`                                     | `GET /v1/analytics/summary`       |
| `client.analyticsOffers()`                                      | `GET /v1/analytics/offers`        |
| `client.analyticsTimeseries(from, to, interval)`                | `GET /v1/analytics/timeseries`    |
| `client.createWebhook(input, idempotencyKey)`                   | `POST /v1/webhooks`               |
| `client.listWebhooks()`                                         | `GET /v1/webhooks`                |
| `client.deleteWebhook(id)`                                      | `DELETE /v1/webhooks/{id}`        |

If you configure the SDK with a member token (a `v4.local.…` PASETO) and call any
of these, the SDK **fails fast client-side** with `ExpysException.NotConfigured`
**before any network request** — the credential is classified as a machine
credential iff it starts with `expys_`. The server **also** enforces this: a member
token is `403`'d via the route auth matrix. The three POSTs (`exchangeToken`,
`creditPoints`, `createWebhook`) auto-send an `Idempotency-Key` like the other
writes; the `PUT` and `DELETE`s are idempotent by HTTP semantics and send no key.

```kotlin
// Backend only — never in a client app.
val client = ExpysClient.create(ExpysConfiguration(token = orgApiKey, environment = ExpysEnvironment.LIVE))
val grant = client.exchangeToken(TokenExchangeRequest(externalUserID = "user_42"))
client.creditPoints(CreditWalletRequest(amount = 100, externalUserID = "user_42"))
```

See the `ServerMode` example.

## Errors

Calls throw `ExpysException`:

```kotlin
try {
  client.createRedemption(CreateRedemptionRequest(offer = offerId))
} catch (e: ExpysException.Api) {
  when (e.error.kind) {
    ApiErrorKind.CONFLICT -> if (e.error.code == "REDEMPTION_ALREADY_EXISTS") { /* already booked */ }
    ApiErrorKind.RATE_LIMITED -> { /* e.error.retryAfterMs is set */ }
    else -> {}
  }
} catch (e: ExpysException.Timeout) {
  // request timed out
}
```

`ExpysException` subtypes: `Api(ApiError)`, `Network(detail)`, `Timeout`,
`Decoding(detail)`, `NotConfigured(detail)`. `ApiError` carries `status`, the stable
envelope `code`, `message`, optional `retryAfterMs` (milliseconds, matching the TS/Swift
SDKs), optional `requestId` (the server's `x-request-id` - quote it to support to trace
the call), and a coarse `kind`.

`code` is the stable contract - branch on it (e.g. `REDEMPTION_ALREADY_EXISTS` on a 409,
`INSUFFICIENT_POINTS` on a 422 when the wallet balance is too low), but treat an unknown
code as the generic class for its `kind` (new codes can appear without a major version).
The full list lives in the [`/v1` error responses](https://docs.expys.com/guides/errors).

## Retries & Timeouts

`429`/`5xx` responses are retried with full-jitter exponential backoff (base 500ms, cap
10s) honoring `Retry-After` (delta-seconds or an HTTP-date, clamped to [0, 300s]).
Defaults: `maxRetries` is **2** (3 attempts total); `timeoutMs` is unset - set
`ExpysConfiguration.timeoutMs` for a per-request ceiling. Cancellation always propagates
and is never retried.

## Configuration Reference

| Option             | Type                              | Default      | Notes                                          |
| ------------------ | --------------------------------- | ------------ | ---------------------------------------------- |
| `token`            | `String`                          | (required)   | Short-lived member token from your backend.    |
| `environment`      | `ExpysEnvironment`                | `LIVE`       | `LIVE` / `SANDBOX`; informational only.        |
| `baseUrl`          | `String`                          | default host | API host (sandbox and live share one).         |
| `orgId`            | `String?`                         | `null`       | Folded into the `User-Agent` for attribution.  |
| `tokenExpiresAtMs` | `Long?`                           | `null`       | Epoch-millis; enables proactive refresh.       |
| `refreshToken`     | `(suspend () -> TokenRefresh)?`   | `null`       | Calls your backend for a fresh token.          |
| `maxRetries`       | `Int`                             | `2`          | Extra attempts on 429/5xx.                     |
| `timeoutMs`        | `Long?`                           | `null`       | Per-request ceiling, in milliseconds.          |
| `refreshSkewMs`    | `Long`                            | `30_000`     | Refresh this long before expiry.               |
| `userAgentSuffix`  | `String?`                         | `null`       | Appended to the SDK `User-Agent`.              |

`ExpysClient.create(configuration, httpClient)` accepts an optional `HttpClient` to
inject a custom transport (for instrumentation, metrics, or testing); it defaults to an
OkHttp-backed engine.

## Versioning Policy

SDK versioning is independent semver, decoupled from the spec version, and follows the
[Expys SDK versioning and deprecation policy](https://docs.expys.com/guides/versioning). The
public ABI is guarded by the Kotlin Binary Compatibility Validator (`./gradlew apiCheck`
against the committed [`api/expys-sdk.api`](api/expys-sdk.api)). See the
[CHANGELOG](CHANGELOG.md).

## Documentation

The full API reference is generated with [Dokka](https://kotlinlang.org/docs/dokka-introduction.html)
v2 and published to GitHub Pages once the docs workflow is enabled. Build it locally:

```sh
./gradlew dokkaGeneratePublicationHtml
# output: build/dokka/html/index.html
```

## Examples

Runnable, env-var-driven samples (zero UI) live in [`examples/`](examples), one per
concept, mirroring the TypeScript and Swift SDKs: `BrowseRedeem`, `Pagination`,
`ErrorHandling`, `TokenRefresh`, `Environments`, `Idempotency`, `Configuration`,
`EligibilityWallet`, `RedemptionsList`, `Conversations`. They are compile-checked in CI
(the `examples` source set).

## Platform support

A single pure-Kotlin/JVM artifact (no Android Gradle Plugin, no Android resources)
that runs unchanged on the server and on Android.

- **JVM:** Java 17+ (the artifact targets JVM 17 bytecode).
- **Android:** consumable from an app that compiles against Java 17 (Android Gradle
  Plugin 8+, with `compileOptions`/`kotlinOptions` set to 17).
  - **`minSdk`:** the SDK parses RFC 7231 `Retry-After` dates with `java.time`, which
    needs **API 26+**, or **API 21+ with [core library desugaring]** enabled:

    ```kotlin
    // app/build.gradle.kts
    android {
      compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
      }
    }
    dependencies {
      coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    }
    ```

  - **OkHttp:** the transport is OkHttp 4.12, which supports Android API 21+.
  - **R8/ProGuard:** the SDK ships keep rules inside the artifact
    (`META-INF/proguard/expys-sdk.pro`, source [`consumer-rules.pro`](consumer-rules.pro)),
    so R8 applies them automatically when shrinking a release build - the public API
    and the kotlinx.serialization model metadata are preserved with no app-side config.

Build from source with `./gradlew build`.

[core library desugaring]: https://developer.android.com/studio/write/java8-support-table

## Other SDKs

The TypeScript and Swift SDKs expose the same methods and behavior. See
[SDK differences](https://docs.expys.com/sdks/differences) for the intentional per-language
differences (and what's guaranteed identical).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build/test/lint/apiCheck/Dokka commands, the
`kotlin/vX.Y.Z` release flow, and the cross-SDK parity rule.

## Security

Report vulnerabilities privately - see [SECURITY.md](SECURITY.md). Please do not open a
public issue. The SDK sends no telemetry.
