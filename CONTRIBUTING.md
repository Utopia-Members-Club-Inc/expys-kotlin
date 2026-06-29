# Contributing to the Expys Kotlin SDK

## Dev setup

The SDK is a single pure-Kotlin/JVM Gradle module in the Expys monorepo at
`packages/sdk-kotlin`. Work from that directory. You need a JDK 17+ (CI builds on
JDK 17 and 21); the Gradle wrapper provides Gradle 8.14.

```sh
cd packages/sdk-kotlin
./gradlew build
```

## Commands

Run from `packages/sdk-kotlin`:

```sh
./gradlew build                  # compile + test + check (ktlint, detekt, apiCheck)
./gradlew test                   # run the JUnit 5 suite
./gradlew koverVerify            # coverage gate (95% line / 85% branch)
./gradlew koverHtmlReport        # human-readable coverage report
./gradlew ktlintCheck            # lint
./gradlew ktlintFormat           # auto-format
./gradlew detekt                 # static analysis
./gradlew apiCheck               # fail on public-API (ABI) drift
./gradlew apiDump                # update the .api baseline after an intentional API change
./gradlew dokkaGeneratePublicationHtml   # build the API reference (Dokka v2 HTML)
./gradlew compileExamplesKotlin  # compile-check the examples source set
./gradlew publishToMavenLocal    # local publish to inspect POM/jars/signatures
```

### Integration suite (opt-in, real sandbox)

`SandboxIntegrationTest` is skipped unless `EXPYS_INTEGRATION=1`; it never runs in
normal CI. Provide a sandbox member token (and optionally a base URL via
`EXPYS_BASE_URL`):

```sh
EXPYS_INTEGRATION=1 EXPYS_MEMBER_TOKEN=<sandbox token> \
  ./gradlew test --tests 'com.expys.sdk.SandboxIntegrationTest'
```

## House rules

- No emojis in code, comments, or docs.
- Immutability; functional style where idiomatic.
- TDD: write tests first; keep coverage at or above the gate.
- Small, cohesive files. Keep the public surface intentional: `explicitApi()` is on,
  so every public symbol needs an explicit visibility modifier, explicit types, and a KDoc.
- After any intentional public-API change, run `./gradlew apiDump` and commit the
  updated `api/expys-sdk.api`.

## Public surface and cross-SDK parity

- The method names, configuration option names, error taxonomy, retry/idempotency
  semantics, and `User-Agent` format are a frozen contract shared with the TypeScript
  and Swift SDKs. Do not change them here without mirroring the change in the other two
  SDKs and the spec. CI enforces spec drift and native-model parity (`native-model-drift`).
- Never hand-edit `src/main/kotlin/com/expys/sdk/models/**`; it is generated from
  `packages/api-spec/v1.sdk.json` by OpenAPI Generator. Regenerate via the monorepo's
  `bun run sdk:generate-models` (the generator post-processes the models to satisfy
  `explicitApi()`, so the output stays drift-clean).
- No new runtime dependencies (the graph is kotlinx-serialization, kotlinx-coroutines,
  OkHttp) without sign-off. Build/test/doc plugins are fine. Versions live in
  `gradle/libs.versions.toml`.

## Releasing

Releases are tag-driven (lead engineer, with approval):

```sh
git tag kotlin/vX.Y.Z
git push origin kotlin/vX.Y.Z
```

The tag triggers [`sdk-release.yml`](../../.github/workflows/sdk-release.yml): it
re-verifies spec/parity, syncs the embedded `ExpysVersion.SDK` constant in
`src/main/kotlin/com/expys/sdk/Version.kt` from the tag, runs the tests + coverage
gate, and publishes `com.expys:sdk` to Maven Central
(`publishToMavenCentral -PsdkVersion=X.Y.Z`) with the GPG + Central Portal secrets.
Never tag or publish without approval.
