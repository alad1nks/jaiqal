# Frontend architecture decisions

These records describe the decisions implemented by the shared client. Their status is **Accepted**.

## ADR-001: Firebase Auth replaces a client-owned JWT session

**Decision:** Registration, login, verification, password reset, session persistence, and ID Token refresh use the official Firebase Authentication SDKs. The client does not call the disabled backend `/auth/register`, `/auth/login`, `/auth/refresh`, or `/auth/logout` endpoints.

**Consequences:** Passwords stay inside the platform Firebase SDK, while protected Ktor calls use short-lived Firebase ID Tokens. Platform Firebase configuration is required for real authentication.

## ADR-002: Ktor backend is the source of truth

**Decision:** Backend responses and `:core:api-contract` define users, plants, devices, telemetry, history, alerts, and rules. Successful server mutations precede local cache updates.

**Consequences:** The client does not invent diagnoses, identifiers, ownership, or offline mutation success. Network access is required for writes.

## ADR-003: SQLDelight is an offline cache only

**Decision:** SQLDelight stores account-scoped sanitized server data and non-secret preferences. It contains no credentials and is not an independent domain authority.

**Consequences:** Cached reads survive connectivity loss; failed refreshes preserve prior data. Logout removes the current account's cache, and no complex offline write queue is maintained.

## ADR-004: SSE replaces frequent polling

**Decision:** Plant measurement events use the authenticated SSE endpoint while plant details are in the foreground. Events trigger authoritative refreshes into SQLDelight.

**Consequences:** Reconnect uses bounded exponential backoff and stops in background or on logout. Background realtime delivery requires a future push contract rather than aggressive polling.

## ADR-005: Compose UI and ViewModels are shared

**Decision:** Android and iOS render the same Compose Multiplatform screens and use common ViewModels/state. Platform launchers provide Firebase, HTTP engine, database driver, locale, URL opening, and diagnostics bridges.

**Consequences:** Product behavior and most tests are shared, while official platform SDK integration remains native and testable behind narrow interfaces.

## ADR-006: Feature modules own vertical slices

**Decision:** Auth, plants, devices, alerts, and settings are separate Gradle modules. Their `data`, `domain`, `presentation`, `navigation`, and `di` layers remain packages rather than becoming many small modules.

**Consequences:** Feature ownership and build boundaries stay clear without creating a generic framework or excessive module graph. `:app:shared` remains a thin composition shell.

## ADR-007: Official Firebase SDKs sit behind a common interface

**Decision:** `AuthProvider` is common code, implemented by Firebase Auth on Android and by a Swift/Firebase bridge on iOS. No third-party KMP Firebase wrapper is used.

**Consequences:** Firebase-specific types do not leak into shared features. Android and iOS implementations can be tested with fake bridges without contacting a real Firebase project.
