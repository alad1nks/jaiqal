# Frontend

The client uses the existing `:app:shared` module as the shared Compose Multiplatform application. Android and iOS keep thin platform entry points; API DTOs continue to come from `:core:api-contract`, so backend contracts are not duplicated in client modules.

## Step 1 architecture

- `app/` owns startup state, the shared snackbar host, and type-safe root/auth/main navigation.
- `core/designsystem/` contains the small Material 3 theme and reusable UI states/components.
- `core/network/` owns environment and backend URL configuration. Repositories must receive `BackendConfig`; they must not contain URLs.
- `core/database/` contains the SQLDelight driver boundary and account-scoped offline schema.
- `core/cache/` maps shared API contracts to SQLDelight and defines explicit read/write sync policies.
- `core/connectivity/` and `core/lifecycle/` define shared state boundaries for later features.
- `di/` provides the Koin application module.

Plant, alert, and device features are still placeholders for later steps in `frontend-task.md`; the authentication flow is implemented in Step 2.

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

`core/network/` contains one project `ApiClient` backed by Ktor, not a wrapper per HTTP method. It configures JSON content negotiation, connect/request/socket timeouts, common success decoding, the shared `ApiErrorResponse` contract, and stable timeout/connectivity/invalid-response errors. Coroutine cancellation is always rethrown unchanged.

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

Application configuration and Koin bootstrap are created by Android/iOS entry points before composition. UI code no longer receives or constructs a backend base URL.

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
./gradlew :app:shared:jvmTest :app:shared:iosSimulatorArm64Test
```

Open `app/iosApp` in Xcode to build and run the iOS shell. Both platform applications render the UI from `:app:shared`.
