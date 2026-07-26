# Mobile frontend architecture

The existing `:app:shared` module is the Compose application (the `composeApp` equivalent). Android, iOS, desktop and web launch the same `App` root. Code is grouped by feature/layer inside that module; public wire types remain in `:core:api-contract`.

## Data and dependency flow

`Compose screen → common presentation state → repository interface → remote/local data source`. Reads flow back from the cache as `Flow`; the server remains authoritative. The SQL schema in `src/commonMain/sqldelight` contains bounded history plus plants, devices, latest measurements and alerts. A successful refresh replaces cache data transactionally; errors retain it. Logout clears Firebase state and all user-owned rows.

The HTTP client uses the OkHttp engine on Android and Darwin on iOS, JSON content negotiation, timeouts, per-request IDs and bearer injection. Debug logging is header-only and redacts authorization. Firebase Authentication owns ID-token persistence and renewal: the app does **not** store or issue application refresh tokens. `SessionManager` serializes forced ID-token renewals.

## Configuration and running

Use `http://10.0.2.2:8080` from the Android emulator and `http://127.0.0.1:8080` from the iOS simulator. Production builds should inject their HTTPS URL and platform Firebase implementation at startup.

```bash
./gradlew :app:androidApp:assembleDebug
./gradlew :app:shared:compileKotlinIosSimulatorArm64
# Then open app/iosApp in Xcode.
```

Strings use semantic Compose resource keys. English is the default; Russian lives in `values-ru`, Kazakh in `values-kk`. Add a key to every locale in the same change.

Realtime subscriptions are screen-scoped, unique per plant, paused outside the active lifecycle and reconnect with capped exponential backoff. Every reconnect first refreshes REST latest data; SSE only invalidates/updates the cache and is not the source of truth. Mobile networking may suspend in background; monitoring continues on the server.

## Checks

```bash
./gradlew :app:shared:allTests
./gradlew :app:androidApp:assembleDebug
./gradlew :app:shared:compileKotlinIosSimulatorArm64
```

## Current backend dependencies

Firebase client SDK adapters, live calibration samples, push-token registration, image uploads and a production push sender require platform/backend integration. No fake production data, raw device token display, background rapid polling, or legacy application-issued user refresh-token flow is included.
