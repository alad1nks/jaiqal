# Frontend

The client uses the existing `:app:shared` module as the shared Compose Multiplatform application. Android and iOS keep thin platform entry points; API DTOs continue to come from `:core:api-contract`, so backend contracts are not duplicated in client modules.

## Client architecture

The Gradle graph follows `launcher -> app:shared -> feature -> core`:

- `:app:shared` is the product shell. It owns startup state, root/main navigation, platform bootstrap, and the composition root that combines feature modules.
- `:feature:auth`, `:feature:plants`, `:feature:devices`, `:feature:alerts`, and `:feature:settings` own their screens and routes. A feature also owns its view models, DI declaration, and feature-specific `data/domain/presentation` packages when needed.
- `:core:data` owns environment/backend configuration, Firebase session abstractions, the Ktor client, SQLDelight driver and account-scoped cache, connectivity/lifecycle boundaries, and shared data bindings. Repositories receive `BackendConfig`; they do not contain URLs.
- `:core:designsystem` contains the Material 3 theme and reusable UI states/components.
- `:resources` publishes Compose Multiplatform resources to every UI module.
- `:core:testing` contains reusable test fixtures without leaking test code into production modules.
- `:core:api-contract` remains the single shared wire-contract module used by the client and server.

This is a pragmatic feature modularization: substantial product features get a module, while `data`, `domain`, and `presentation` remain packages inside a feature rather than becoming a module each. Feature navigation and DI stay with the feature; `:app:shared` only connects them.

Plant history, realtime, device workflows, alert management, settings/localization, and Crashlytics integration are implemented through Step 10 of `frontend-task.md`.

## Firebase Authentication

Step 2 uses the official platform SDKs: Firebase Authentication from the Android BOM and `FirebaseAuth` from the Firebase Apple Swift package. No third-party KMP Firebase wrapper is used.

1. In Firebase Console, register the existing Android package `com.alad1nks.jaiqal` and iOS bundle ID `com.alad1nks.jaiqal.Jaiqal`.
2. Enable the Email/Password provider.
3. Put the downloaded Android configuration at `app/androidApp/google-services.json`.
4. Put the downloaded Apple configuration at `app/iosApp/iosApp/GoogleService-Info.plist`.
5. Use the same Firebase project ID as the backend `FIREBASE_PROJECT_ID`.

Both configuration files are ignored by Git. If one is absent, that platform still builds and opens the auth UI, but operations report that Firebase is not configured. Google Sign-In and Sign in with Apple are intentionally deferred because no corresponding provider configuration is present.

Firebase restores its persisted session through the platform auth-state listener. The client requires email verification before entering the main graph. Once verified, the shared network layer obtains the current Firebase ID Token and calls the existing `GET /api/v1/auth/me` endpoint. The backend creates or resolves the internal user; the client never generates an internal UUID. Passwords are passed only to the official Firebase SDK.

## Network and session handling

`:core:data` contains one project `ApiClient` backed by Ktor, not a wrapper per HTTP method. Its `core/network` package configures JSON content negotiation, connect/request/socket timeouts, common success decoding, the shared `ApiErrorResponse` contract, and stable timeout/connectivity/invalid-response errors. Coroutine cancellation is always rethrown unchanged.

Protected calls go through `AuthenticatedRequestExecutor`. It requests a token from `AuthProvider`, attaches `Authorization: Bearer ...`, and retries exactly once after `401` with a forced Firebase refresh. A `Mutex` serializes forced refreshes; requests waiting for another refresh reuse its new token. A second `401` raises a managed session error and never starts a retry loop. ID Tokens are held only for the lifetime of a request and are not persisted by the application.

Ktor logging is disabled in release builds. Debug builds log request/response metadata at `INFO` level only, redact authorization and cookie headers, and never log request/response bodies. Tests verify that the Firebase token does not appear in captured logs.

## Offline cache and sync policy

Android and iOS open the same SQLDelight schema through their platform drivers. Every table includes the backend internal `account_id`; plants, devices, latest device state, selected history ranges, alert events, alert rules, the current backend user, and last-sync metadata retain their server identifiers. Firebase ID Tokens, passwords, device tokens, and other credentials have no cache columns.

List replacement is transactional and happens only after a successful remote response. A failed refresh therefore leaves the previous cache intact. History rows are keyed by account, plant, aggregation interval, requested start/end, and measurement time so one selected range cannot be mistaken for another. Logout deletes all cached rows for the current internal account and leaves other accounts untouched.

The initial sync policy is deliberately one-way:

| Data/operation | Policy |
| --- | --- |
| Plant list and details | Cache first, then refresh |
| Latest measurement | Cache first, then network/SSE |
| Selected history range | Network first, fallback to the matching cached range |
| Alerts and rules | Cache first, then refresh |
| Create/update/acknowledge operations | Server first, then update cache |

Offline mutations are not queued and never create optimistic server entities. Connectivity and timeout failures return a clear `OfflineMutationException`; after a successful server mutation, a failed local write does not misreport the server operation as failed and a later refresh repairs the cache.

The SQLDelight migration from the original metadata-only schema is exercised against in-memory SQLite: the test creates version 1, migrates to version 2, verifies old metadata remains, and writes through a newly added table.

## Plants

Step 5 replaces the plants placeholder with list, create, edit, and details routes. `OfflineFirstPlantRepository` combines the shared `PlantResponse`, `DeviceResponse`, `PlantLatestResponse`, `AlertEventResponse`, and `PlantHistoryResponse` contracts; it does not duplicate backend entities or infer a local plant-health diagnosis.

The list renders SQLDelight data immediately, then refreshes `/api/v1/plants`, `/api/v1/devices`, latest telemetry, and alerts with Firebase authentication. It supports pull-to-refresh, loading/empty/content states, a recoverable cached/offline state, server identifiers, device status, measurement time, soil moisture, and active warnings. Remote images are not downloaded in this step; cards consistently use an accessible local plant placeholder.

Create and edit send only the actual API fields `name`, `species`, and `imageUrl`. Client validation mirrors the backend/database limits: a trimmed 1–255 character name, species up to 255 characters, and image URL up to 2048 characters. Mutations are server-first and cache the returned server-generated ID; an offline mutation shows an explicit error and creates no optimistic plant.

Plant details display available telemetry with units and measurement timestamps, use the backend `online` flag for stale/offline presentation, mark metric warnings only from active backend alert types, and expose calibration status. Device claiming and calibration actions are visible but intentionally hand off to the device workflow planned for Step 7.

## Measurement history and realtime

Step 6 adds selectable 24-hour, 7-day, and 30-day history ranges. Requests use the backend aggregation intervals `5m`, `1h`, and `1d` respectively, keeping large responses bounded and server-aggregated. Four focused Compose Canvas charts show soil moisture, air temperature, air humidity, and light. Soil moisture falls back to raw ADC values when calibrated percentages are unavailable. Each chart includes units, local-time labels, textual min/max accessibility information, visible point markers, and separate line segments around missing values or unexpectedly large time gaps. Loading, empty, cached-error, and retry states are handled per selected range.

Plant details connect to the authenticated `/api/v1/plants/{plantId}/stream` SSE endpoint only while the screen is in the foreground. The Firebase ID Token is attached as a bearer credential and is never persisted. A measurement event triggers network refresh of latest telemetry, active alerts, and the currently selected history range; SQLDelight remains the source observed by UI flows. Reconnect uses bounded exponential backoff with jitter, stops in background, and is cancelled by logout. Returning to the foreground performs a full plant refresh. The app does not poll every few seconds and does not attempt permanent background monitoring.

## Device claiming and calibration

Step 7 lives in `:feature:devices`. `ClaimDeviceScreen` accepts manual one-time code entry and an owned plant selection; no QR dependency is currently present. It calls only `POST /api/v1/devices/claim` with `ClaimDeviceRequest`. The backend intentionally returns the same ownership-hiding `NOT_FOUND` response for an invalid, expired, consumed, or unavailable claim, so the UI presents those cases as one non-disclosing error instead of inventing distinctions the contract does not provide.

A connectivity failure or timeout after submitting a claim is treated as an uncertain result. Retrying first calls `GET /api/v1/devices` and considers a device attached to the selected plant authoritative; only when no attachment is present is the one-time code submitted again. Claim codes remain only in view-model memory and are cleared after success. ESP32 Device Tokens are never requested, returned by these flows, displayed, logged, or persisted.

`DeviceDetailsScreen` shows cached device identity, firmware, last-seen, online, and calibration values. Its five-step calibration wizard captures dry and wet readings from the actual `GET /api/v1/plants/{plantId}/latest` contract and writes them with `PATCH /api/v1/devices/{deviceId}/calibration`. The backend does not expose a multi-sample calibration endpoint, so the client shows each measurement timestamp and lets the user repeat unstable captures rather than fabricating sample aggregation. Equal values are rejected; increasing or decreasing wet ADC direction is accepted and explained. Offline devices, missing raw readings, timeout, cancellation without a write, and recalibration are explicit states.

## Alerts and rules

Step 8 lives in `:feature:alerts`. Its repository combines cached plants and alert events for the tab, refreshes each owned plant through the actual alerts endpoints, and keeps rule caches scoped by account and plant. Acknowledge and `PUT` rule mutations are server-first and never report an offline save as successful.

The rule editor keeps unsaved drafts separate from server-backed rules, validates the backend's threshold and 0–2,592,000 second duration constraints, explains debounce duration, and can reset a rejected draft. Alert events currently contain status and timestamps but no measured value or historical threshold; the UI explicitly marks those fields unavailable rather than joining against a potentially changed current rule.

Application configuration and Koin bootstrap are created by Android/iOS entry points before composition. UI code no longer receives or constructs a backend base URL.

## Settings, localization, and accessibility

The settings feature exposes the account email and verification state, resend-verification and logout actions, application version, language, theme, and an optional privacy-policy link. Debug builds additionally show non-secret diagnostics (platform, environment, and backend URL); release builds do not construct or render that section. Theme and language are stored in the shared SQLDelight preferences table and survive process restarts without storing credentials.

Russian is the default resource locale, with complete English (`values-en`) and Kazakh (`values-kk`) resource sets. Choosing `System`, `Қазақша`, `Русский`, or `English` applies the locale immediately where the platform supports runtime locale changes; choosing the system option returns control to the operating-system locale. Dates, times, decimals, percentages, and temperatures are formatted according to the active locale while preserving the API's units.

Interactive controls use semantic roles and selected states, meaningful images have content descriptions, status is always conveyed by text as well as color, and touch targets use the design system's 48 dp minimum. Charts retain visible textual values and accessible min/max summaries, so their meaning is not available only through the plotted line.

The privacy-policy URL is optional and contains no secret. Configure it per build without editing UI code:

```bash
./gradlew :app:androidApp:assembleDebug \
  -PJAIQAL_PRIVACY_POLICY_URL=https://example.com/privacy
```

For iOS, set `PRIVACY_POLICY_URL` in `app/iosApp/Configuration/Config.xcconfig` or the ignored local configuration used by the Xcode target. When it is empty, Settings shows a localized placeholder instead of a broken link.

## Push dependency and Crashlytics

The backend currently has no authenticated endpoint for registering, rotating, or deactivating a user's FCM token. Consequently, the client deliberately does not include Firebase Messaging, request notification permission, obtain an FCM/APNs token, or invent a URL. `PushTokenRegistrar` and `UnavailablePushTokenRegistrar` define the common boundary. Enabling push later requires a backend contract that accepts a platform and token with a Firebase ID Token, supports idempotent rotation/deactivation, and defines notification payload fields for plant/alert deep links. APNs setup on iOS belongs to that later integration.

Crashlytics is linked through the Firebase Android BOM and the existing Firebase Apple Swift package. Debug collection is disabled before SDK initialization by Android manifest metadata and `Info-Debug.plist`; release collection is enabled. The common reporter accepts only a closed set of non-personal issue codes and deduplicates each code for the process lifetime. It never accepts arbitrary messages, passwords, Firebase ID Tokens, FCM tokens, ESP32 Device Tokens, email addresses, or user IDs.

When `app/androidApp/google-services.json` is present, the Android build applies both Google Services and the Crashlytics Gradle plugin. Release minification generates a mapping file and the plugin handles its upload. The Xcode target links `FirebaseCrashlytics`; its final Release build phase invokes Firebase's symbol uploader for dSYM files when the built app contains `GoogleService-Info.plist`. Missing Firebase configuration keeps local builds functional and prints a release-build warning instead of uploading symbols.

After adding both ignored Firebase configuration files, make one controlled release test crash per platform and confirm it appears in the Firebase console. Do not add Analytics, Firestore, Realtime Database, or Messaging as part of this setup.

## Backend environments

| Target | Debug/local default | Production configuration |
| --- | --- | --- |
| Android emulator | `http://10.0.2.2:8080` | Gradle property `JAIQAL_PRODUCTION_API_BASE_URL` |
| iOS Simulator | `http://127.0.0.1:8080` | `API_BASE_URL` in `Config.xcconfig` |
| Physical device | Development machine LAN URL, configured as below | HTTPS URL only |

Android local overrides can be passed without editing source:

```bash
./gradlew :app:androidApp:assembleDebug -PJAIQAL_LOCAL_API_BASE_URL=http://192.168.1.10:8080
```

For iOS physical-device development, copy `app/iosApp/Configuration/Local.xcconfig.example` to the ignored `Local.xcconfig` and set the development machine's LAN address. Android cleartext access is enabled only by the debug manifest. The iOS local-network exception exists only in `Info-Debug.plist`; Release uses `Info.plist` without an ATS exception. `DefaultBackendConfig` also rejects non-HTTPS production URLs.

The checked-in production endpoint is intentionally non-routable. Supply a real HTTPS endpoint in deployment configuration; do not commit credentials or service secrets.

## Build checks

```bash
./gradlew :app:androidApp:assembleDebug
./gradlew :app:shared:compileKotlinIosSimulatorArm64
./gradlew :core:data:jvmTest :feature:auth:jvmTest :feature:plants:jvmTest
./gradlew :feature:devices:jvmTest
./gradlew :feature:alerts:jvmTest
./gradlew :feature:settings:jvmTest
./gradlew :app:shared:jvmTest :app:shared:iosSimulatorArm64Test
```

Open `app/iosApp` in Xcode to build and run the iOS shell. Both platform applications render the UI from `:app:shared`.
