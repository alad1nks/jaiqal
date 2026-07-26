# Жайқал mobile client

## Architecture

`app/shared` is the project's Compose Multiplatform client module (the equivalent
of `composeApp`). Android and iOS hosts render the same `App`. Code is split into
`core` infrastructure and feature packages; API wire models come only from
`core/api-contract`.

The intended dependency direction is UI → common ViewModel → repository → remote
Ktor source/local SQLDelight source. The backend is the source of truth. Reads are
cache-first except history (network-first with cache fallback); mutations are
server-first. Account IDs partition cached rows and logout must delete that
account's rows. Firebase tokens must never be written to the cache.

## Authentication and networking

Native hosts implement `AuthProvider` using the official Firebase Auth Android
and Apple SDKs. Firebase restores and refreshes its own session. The client gets
an ID token immediately before a protected request and sends it to
`GET /api/v1/auth/me`. `AuthenticatedRequestExecutor` retries exactly once after
401, serializes forced refresh with a mutex, and never logs or persists tokens.
Passwords are sent only to Firebase—not to Ktor. The legacy Ktor login, register,
refresh and logout endpoints are not used.

Local URLs are `http://10.0.2.2:8080` for an Android emulator and
`http://127.0.0.1:8080` for an iOS simulator. Physical devices must receive a
developer-machine LAN URL through build configuration. Production must inject an
HTTPS URL; repositories do not contain URLs. Cleartext transport should be
enabled only in an Android debug network-security config / iOS debug ATS setting.

## Actual backend endpoints

- `GET /api/v1/auth/me`
- `GET|POST /api/v1/plants`, `GET|PATCH|DELETE /api/v1/plants/{plantId}`
- `GET /api/v1/plants/{plantId}/latest|history|stream`
- `GET|PUT /api/v1/plants/{plantId}/alert-rules`
- `GET /api/v1/plants/{plantId}/alerts`
- `POST /api/v1/plants/{plantId}/alerts/{alertId}/acknowledge`
- `POST /api/v1/devices/claim`, `GET /api/v1/devices[/{deviceId}]`
- `PATCH /api/v1/devices/{deviceId}` and `/calibration`

The mobile client never calls device telemetry routes and never receives the ESP32
device token. Although an operator token-rotation endpoint exists, it deliberately
has no mobile workflow because its response contains the raw device credential.

SSE should run only in foreground after authentication. On failure reconnect with
capped exponential backoff plus jitter, refresh on foreground entry, and cancel on
logout. The server's aggregated intervals are `raw`, `5m`, `1h`, and `1d`.

## Firebase owner checklist

1. Create/select the Firebase project and add Android app
   `com.alad1nks.jaiqal` and the iOS app whose bundle ID is in `Config.xcconfig`.
2. Download each platform configuration file through a secret-aware CI process;
   do not commit it in this repository.
3. Enable Email/Password authentication. Configure SHA fingerprints and URL
   schemes only if Google Sign-In is later enabled; configure Apple capability and
   provider only if Sign in with Apple is later enabled.
4. Add the official Firebase Auth and Crashlytics SDKs to both native hosts. Set
   release mapping/dSYM upload and disable debug crash collection as desired.
5. Configure an APNs key before enabling FCM on iOS.

Platform Firebase configuration files identify apps; they are **not** Firebase
Admin service-account credentials. Firebase Admin JSON/private keys belong only in
the server environment and must never be included in a frontend target.

## Localization and builds

Shared resources contain English, Russian, and Kazakh UI strings and follow the
device locale. Build Android with `./gradlew :app:androidApp:assembleDebug`.
Compile shared Apple code on macOS with
`./gradlew :app:shared:compileKotlinIosSimulatorArm64`, then open
`app/iosApp/iosApp.xcodeproj` in Xcode.

## Backend/configuration dependencies and current limitations

- The backend has no endpoint for registering/deactivating a user FCM token.
  `PushTokenRegistrar` is retained as a platform boundary, but production must not
  invent an endpoint. Push delivery therefore remains blocked.
- Firebase platform files, Apple package integration, APNs entitlements, and
  Crashlytics upload credentials are owner/CI configuration and are intentionally
  absent.
- SQLDelight schema/repository implementations, complete feature screens,
  type-safe navigation/deep links, SSE lifecycle integration, and native Firebase
  adapters remain implementation work; no fake production data is supplied.

## Architecture decisions

1. Firebase Auth owns the client session instead of application JWTs.
2. Ktor/PostgreSQL remains the business-data source of truth.
3. SQLite is an offline cache, never a second writable source of truth.
4. SSE is preferred to frequent polling.
5. Compose UI and ViewModels are shared by Android and iOS.
6. Feature packages avoid premature Gradle-module proliferation.
7. Official native Firebase SDKs sit behind a small common interface.
