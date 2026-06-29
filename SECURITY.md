# Security Policy

This package (the Expys Kotlin SDK, `com.expys:sdk`) is covered by the Expys
monorepo [Security Policy](../../SECURITY.md).

**Do not open a public issue or PR for a security vulnerability.** Report it
privately via GitHub's private vulnerability reporting or by email to
**security@expys.dev**, with the affected component and version, a description,
and reproduction steps. See the [full policy](../../SECURITY.md) for supported
versions and what to expect.

The SDK sends no telemetry. It makes requests only to the API base URL you
configure; the only metadata added is a `User-Agent` (SDK + spec version,
environment, and an optional org id for server-side attribution).
