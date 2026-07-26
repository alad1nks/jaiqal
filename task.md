# Жайқал — KMP frontend implementation task

## Goal

Implement the first production-ready Kotlin Multiplatform frontend for **Жайқал**, an Android and iOS application that monitors house plants through ESP32 devices.

The application must allow a user to:

- register and sign in;
- create and manage plants;
- claim an ESP32 device;
- view the latest soil moisture, air temperature, air humidity, and light readings;
- view measurement history;
- receive realtime updates;
- configure and review alerts;
- continue seeing previously loaded data while offline.

Use **Compose Multiplatform with shared UI and shared presentation/domain/data logic**. The backend is a separate Ktor application in the same repository.

---

## Working rules

1. Inspect the repository before modifying anything:
   - read `README.md`, `AGENTS.md`, `settings.gradle.kts`, version catalogs, convention plugins, and existing app modules;
   - inspect existing architecture, navigation, networking, localization, DI, theming, and testing conventions;
   - reuse equivalent modules and abstractions instead of creating duplicates;
   - preserve unrelated user changes.
2. Create a short implementation plan before coding.
3. Complete the steps below in order.
4. After every step:
   - compile all affected targets;
   - run relevant tests;
   - fix failures before continuing.
5. Use versions from the existing version catalog. If a dependency is missing, select a current stable version compatible with the repository's Kotlin, Compose Multiplatform, and Ktor versions.
6. Do not put backend code, database credentials, device secrets, or server-only models in the frontend.
7. Do not create generic abstractions until at least two concrete consumers need them.
8. Do not create a universal `BaseViewModel`, `BaseRepository`, or generic MVI framework.
9. Keep Android and iOS behavior consistent while respecting platform-specific secure storage and lifecycle behavior.
10. If an API required by this task is not implemented yet, define the interface and use a fake only in previews/tests. Do not ship fake production data.

---

## Target architecture

Use a pragmatic layered architecture:

```text
Compose Screen
    ↓ actions
Common ViewModel
    ↓
Repository interface
    ↓
Repository implementation
    ├── Remote data source → Ktor Client → Backend API
    └── Local data source  → SQLDelight → SQLite
```

Data returned to UI flows in the opposite direction:

```text
SQLite / Network
    → Repository Flow
    → ViewModel StateFlow
    → Compose UI
```

The server is the source of truth. SQLite is an offline cache and must never silently override newer server data.

### Recommended Gradle structure

Reuse equivalent existing modules. Otherwise prefer:

```text
:core:api-contract       # Shared with the Ktor server
:app:composeApp          # Shared KMP UI, domain, data, and presentation
:app:androidApp          # Android entry point if separate
iosApp/                  # Xcode iOS entry point
```

Do not immediately create a Gradle module for every feature. Inside `composeApp`, organize code by feature and layer:

```text
composeApp/src/commonMain/kotlin/<base-package>/
├── app/
│   ├── App.kt
│   ├── AppState.kt
│   └── navigation/
├── core/
│   ├── common/
│   ├── designsystem/
│   ├── network/
│   ├── database/
│   ├── session/
│   └── lifecycle/
├── auth/
│   ├── data/
│   ├── domain/
│   └── presentation/
├── plants/
├── plantdetails/
├── devices/
├── alerts/
└── settings/
```

Each feature may contain:

```text
data/
domain/
presentation/
```

Only add a domain use-case class when it contains real business logic or combines multiple repositories. Simple repository calls can be made directly from a ViewModel.

---

## Technical choices

Use existing equivalents when present. Otherwise use:

- Compose Multiplatform;
- AndroidX Lifecycle ViewModel in `commonMain`;
- type-safe AndroidX Navigation Compose for Multiplatform;
- Kotlin coroutines and `StateFlow`;
- Ktor Client;
- kotlinx.serialization;
- kotlinx.datetime;
- SQLDelight for the offline cache;
- Koin with the Kotlin DSL for dependency injection;
- Coil 3 for remote images if plant images are implemented;
- Compose Multiplatform resources for strings and images.

Ktor engines:

- Android: OkHttp;
- iOS: Darwin.

Do not expose API DTOs directly to composables. Use explicit mappings:

```text
API DTO ↔ domain model ↔ local database model
```

---

# Step 1 — Application shell, design system, and navigation

## Tasks

1. Ensure Android and iOS entry points both launch the same shared `App()` composable.
2. Set up dependency injection from the platform entry points.
3. Implement an application-level state holder responsible only for:
   - current authenticated/unauthenticated state;
   - top-level navigation;
   - app-wide snackbar messages;
   - current connectivity state.
4. Create type-safe navigation destinations. Do not use hand-built string routes.
5. Create these navigation graphs:

```text
Splash
Auth
  ├── SignIn
  └── SignUp
Main
  ├── Plants
  ├── Alerts
  └── Settings
PlantDetails
AddPlant
ClaimDevice
DeviceCalibration
```

6. Create a minimal reusable design system matching the style of OQUTurbo apps:
   - semantic colors;
   - typography;
   - spacing constants;
   - shapes;
   - app background;
   - primary/secondary buttons;
   - text fields;
   - loading indicator;
   - empty state;
   - error state;
   - offline banner;
   - metric card;
   - plant card.
7. Support light and dark themes.
8. Add localization infrastructure for:
   - Kazakh;
   - Russian;
   - English.
9. Use semantic resource names. Do not hardcode visible strings in composables.
10. Add previews for reusable components and main screen states.

## Navigation rules

- `Splash` decides where to navigate after session restoration.
- Authentication screens must not remain in the back stack after successful login.
- Logging out must clear the authenticated graph.
- Pass IDs through navigation, not full models.
- A screen must reload its model through its ViewModel/repository.

## Acceptance criteria

- Android and iOS compile and open the shared application.
- Type-safe navigation works for all placeholder destinations.
- Theme switching does not recreate business state.
- Kazakh, Russian, and English resources resolve correctly.
- Components have loading, empty, error, and normal previews.
- No feature contains its own duplicate colors or typography.

---

# Step 2 — Network client, session, and authentication

## Core network layer

Create one configured `HttpClient` per application process with:

- platform engine;
- JSON content negotiation;
- request timeout;
- response validation;
- request ID header;
- authorization header injection;
- redacted logging in debug builds only;
- no body logging for authentication requests;
- consistent mapping from backend errors to client errors.

Define a client error model equivalent to:

```kotlin
sealed interface AppError {
    data object NoInternet : AppError
    data object Unauthorized : AppError
    data object Timeout : AppError
    data class Validation(val message: String) : AppError
    data class Server(val code: String?, val message: String?) : AppError
    data class Unknown(val cause: Throwable?) : AppError
}
```

Do not show raw exception messages to users.

## Session storage

Create:

```kotlin
interface SecureTokenStorage {
    suspend fun readRefreshToken(): String?
    suspend fun writeRefreshToken(value: String)
    suspend fun clear()
}
```

Implement it using platform-secure storage:

- Android: Android Keystore-backed storage;
- iOS: Keychain.

Keep the access token in memory. Persist only what is required to restore the session.

## Token refresh

Implement a `SessionManager` that:

1. restores a refresh token at startup;
2. obtains a new access token;
3. serializes concurrent refresh attempts with a `Mutex`;
4. retries the failed request once after successful refresh;
5. never refreshes recursively for login/refresh endpoints;
6. clears the session when refresh fails with an authentication error;
7. emits session changes as a `StateFlow`.

## Authentication feature

Implement:

```text
SignInScreen
SignUpScreen
```

Each screen must have:

- immutable `UiState`;
- user actions;
- field validation;
- progress state;
- server error handling;
- password visibility control;
- keyboard actions;
- accessibility labels.

Use common ViewModels:

```kotlin
class SignInViewModel(...)
class SignUpViewModel(...)
```

Each ViewModel exposes:

```kotlin
val state: StateFlow<State>
fun onAction(action: Action)
```

Use a separate `SharedFlow<Effect>` only for true one-time effects such as navigation or snackbar messages.

## Acceptance criteria

- Login, registration, refresh, and logout use the backend contracts.
- Concurrent HTTP 401 responses trigger only one refresh request.
- Refresh tokens never appear in logs.
- Invalid fields are rejected before a network request.
- Session restoration selects the correct navigation graph.
- ViewModel tests cover success, validation, server error, and refresh failure.

---

# Step 3 — SQLDelight cache and offline-first repositories

## Local database

Create SQLDelight tables for cached client data:

### `cached_plants`

```text
id TEXT primary key
name TEXT not null
species TEXT null
image_url TEXT null
archived_at TEXT null
updated_at TEXT not null
```

### `cached_devices`

```text
id TEXT primary key
plant_id TEXT null
name TEXT not null
firmware_version TEXT null
last_seen_at TEXT null
is_disabled INTEGER not null
soil_dry_raw INTEGER null
soil_wet_raw INTEGER null
updated_at TEXT not null
```

### `cached_latest_measurements`

```text
device_id TEXT primary key
measurement_id TEXT
measured_at TEXT not null
received_at TEXT not null
soil_moisture_raw INTEGER null
soil_moisture_percent REAL null
air_temperature_celsius REAL null
air_humidity_percent REAL null
light_raw INTEGER null
updated_at TEXT not null
```

### `cached_measurement_history`

Cache only a bounded recent window or explicitly requested ranges. Add indexes needed for:

```text
device_id + measured_at
```

### `cached_alerts`

Store enough information to show the alert list offline.

## Repository contracts

Create interfaces such as:

```kotlin
interface PlantRepository {
    fun observePlants(): Flow<List<Plant>>
    fun observePlant(id: PlantId): Flow<Plant?>
    suspend fun refreshPlants()
    suspend fun createPlant(command: CreatePlantCommand): Plant
    suspend fun updatePlant(id: PlantId, command: UpdatePlantCommand)
    suspend fun archivePlant(id: PlantId)
}

interface MeasurementRepository {
    fun observeLatest(plantId: PlantId): Flow<PlantMeasurement?>
    fun observeHistory(
        plantId: PlantId,
        range: MeasurementRange,
        interval: MeasurementInterval,
    ): Flow<List<MeasurementPoint>>
    suspend fun refreshLatest(plantId: PlantId)
    suspend fun refreshHistory(
        plantId: PlantId,
        range: MeasurementRange,
        interval: MeasurementInterval,
    )
}
```

## Offline behavior

- Read observable UI data from SQLDelight.
- A refresh fetches remote data and writes it transactionally to SQLDelight.
- The UI continues displaying cached content during refresh.
- If refresh fails, retain cached data and expose a non-destructive error.
- Empty cache plus network failure shows a full-screen retry state.
- Existing cache plus network failure shows an offline banner/snackbar.
- Never delete valid cache before a successful replacement arrives.
- Clear user-owned cache on logout.

Do not implement general write synchronization or conflict resolution in this version. Mutating actions require a network connection and update the cache after server success.

## Acceptance criteria

- SQLDelight drivers work on Android and iOS.
- Plant and measurement flows update after database writes.
- Cached content appears before remote refresh completes.
- Failed refresh does not erase cached content.
- Logout clears user-specific cached data and tokens.
- Repository tests cover cache-first behavior and refresh failures.

---

# Step 4 — Plants home screen and plant management

## Plants screen

Implement the authenticated home screen with:

- app title;
- plant list;
- latest status summary for each plant;
- add-plant action;
- pull-to-refresh when supported by the project's chosen component;
- loading, empty, content, stale/offline, and error states.

Each plant card should show:

- plant name;
- optional species;
- optional image;
- soil moisture percentage when available;
- last measurement time;
- device online/offline state;
- a concise warning indicator when an active alert exists.

Do not classify a plant as healthy solely from one measurement. Use neutral descriptions such as:

```text
Soil moisture: 42%
Updated 3 minutes ago
Device offline
```

## Add plant

Implement:

```text
AddPlantScreen
EditPlantScreen
```

Fields:

- name, required;
- species, optional;
- image URL or image selection only if the backend supports uploads;
- save/cancel.

Do not implement image uploads if the backend task has not implemented storage.

## ViewModels

Implement common ViewModels:

```text
PlantsViewModel
AddPlantViewModel
EditPlantViewModel
```

Keep form state in the ViewModel when it must survive configuration changes and navigation recreation.

## Acceptance criteria

- Cached plants render immediately.
- Refresh updates the list without blanking the screen.
- Empty state clearly offers plant creation.
- Creating/editing a plant updates the local cache after server success.
- Archive/delete requires confirmation.
- Ownership or HTTP 404 errors return the user safely to the list.
- Tests cover all major `PlantsViewModel` states.

---

# Step 5 — Plant details, charts, and realtime measurements

## Plant details screen

Implement `PlantDetailsScreen` with:

1. plant header;
2. connection status;
3. latest measurement section;
4. history chart;
5. active alert summary;
6. device/calibration entry point.

Latest measurement cards:

- soil moisture, `%`;
- air temperature, `°C`;
- air humidity, `%`;
- light level, labeled as relative/raw until a lux sensor is used;
- last update time.

Handle missing values independently. One missing sensor must not hide the other readings.

## History

Support selectable periods:

```text
24 hours
7 days
30 days
```

Map them to backend intervals:

```text
24 hours → 5m or 1h
7 days   → 1h
30 days  → 1d
```

Use an existing multiplatform chart solution if the repository already has one. Otherwise implement a focused Compose Canvas line chart without introducing a large chart framework.

The chart must include:

- unit;
- time axis;
- selected series;
- empty state;
- loading overlay that preserves old data;
- error state;
- accessible textual summary of min/max/latest values.

## Realtime SSE

Use Ktor Client SSE for:

```text
GET /api/v1/plants/{plantId}/stream
```

Requirements:

1. Connect only while the authenticated app is active and the relevant plant needs updates.
2. Parse typed events.
3. Write each received latest measurement to SQLDelight.
4. Let the UI update through the existing database `Flow`.
5. Reconnect with capped exponential backoff.
6. Respect connectivity and app lifecycle.
7. After reconnecting, call the REST latest endpoint to recover events that may have been missed.
8. Do not treat SSE as the source of truth.
9. Do not keep multiple connections for the same plant.

## Acceptance criteria

- The screen renders cached latest values immediately.
- REST refresh updates latest values and history.
- SSE events update the UI through SQLDelight without manual refresh.
- Reconnection does not create duplicate collectors.
- Leaving the screen releases the plant-specific realtime subscription.
- Missing sensor fields and empty history do not crash the UI.
- Tests cover event handling, reconnect policy, and period selection.

---

# Step 6 — Device claiming and calibration

## Claim device flow

Implement a guided flow:

```text
Select plant
    → Enter/scan claim code
    → Confirm device
    → Success
    → Optional calibration
```

For the first version:

- provide manual claim-code entry;
- add QR scanning only if a suitable cross-platform scanner already exists in the repository;
- do not block the feature on QR scanning;
- validate code format locally;
- handle used, expired, and invalid codes;
- prevent duplicate submissions;
- associate the claimed device with the selected plant.

## Device screen

Show:

- device name;
- device ID in a copyable diagnostics section;
- firmware version;
- last-seen time;
- online/offline status;
- current upload interval if returned by the API;
- calibration status;
- rotate-token action only if the backend exposes it safely;
- unlink action with confirmation.

Never display the device token.

## Soil calibration wizard

Implement a beginner-friendly wizard:

1. Explain what calibration does.
2. Ask the user to place the sensor in dry soil and capture several readings.
3. Use a median of multiple samples, not one sample.
4. Ask the user to place the sensor in fully wet soil and repeat.
5. Validate that values differ by a minimum safe amount.
6. Explain and retry when values are equal or unstable.
7. Submit `dryRaw` and `wetRaw` to the backend.
8. Show the resulting percentage preview.

If the backend does not yet provide live calibration samples, implement the UI and repository interface, then document the missing API instead of inventing readings.

## Acceptance criteria

- A valid claim code attaches a device to the selected plant.
- Invalid/used/expired codes have distinct user-friendly messages.
- The device token is never shown or logged.
- Calibration handles normal and reversed ADC directions.
- Calibration cannot complete with insufficient value separation.
- ViewModel tests cover all claim and calibration states.

---

# Step 7 — Alerts, settings, localization, and platform behavior

## Alerts

Implement:

```text
AlertsScreen
AlertRulesScreen
```

Support:

- low soil moisture;
- high temperature;
- low temperature;
- device offline.

The alerts list must have:

- active and resolved states;
- timestamp;
- plant name;
- alert type;
- acknowledge action where supported;
- cached offline display;
- pagination or bounded loading if required by the API.

Alert-rule editing must:

- validate thresholds;
- allow duration configuration;
- explain that duration avoids warnings from one accidental reading;
- preserve server values on failed save.

## Settings

Implement:

- language: system/Kazakh/Russian/English;
- theme: system/light/dark;
- account information;
- app version;
- diagnostics section for debug builds;
- logout.

Persist non-sensitive preferences locally. Keep authentication secrets in secure storage only.

## Connectivity and lifecycle

Create small platform abstractions only where required:

```kotlin
interface ConnectivityObserver
interface AppLifecycleObserver
interface SecureTokenStorage
```

Use `expect/actual` only for platform APIs. Keep business behavior in `commonMain`.

Do not promise continuous background monitoring from the mobile app. The backend receives ESP32 readings independently; the mobile application may suspend networking in the background, especially on iOS.

## Push notifications

If backend push support is ready:

- define a common `PushTokenRegistrar`;
- implement platform registration;
- send the push token to the backend;
- route notification taps to the relevant plant/alert;
- handle token refresh;
- avoid logging push tokens.

If backend push support is not ready, keep the integration behind an interface and provide documentation, not a fake production implementation.

## Acceptance criteria

- Alerts remain visible from cache when offline.
- Rule edits validate thresholds and durations.
- Language changes update visible resources correctly.
- Theme and language persist across restarts.
- Logout clears session and user-specific cache.
- SSE/network work follows app lifecycle.
- Platform-specific implementations compile on Android and iOS.

---

# Step 8 — Testing, quality, and documentation

## Unit tests

Cover:

- session restoration;
- serialized token refresh;
- authentication validation;
- repository cache-first behavior;
- plant creation/editing;
- latest measurement mapping;
- history period mapping;
- SSE event processing and reconnection policy;
- device claim flow;
- calibration median and validation;
- alert rule validation;
- logout cleanup.

Use the repository's existing coroutine test tools. Add Turbine only if no equivalent Flow-testing utility exists.

## Integration tests

Test:

- Ktor client serialization against representative backend JSON fixtures;
- `ApiErrorResponse` mapping;
- SQLDelight migrations and queries;
- repository behavior with fake HTTP and a real local SQLDelight driver.

Store sanitized JSON fixtures under test resources. Keep them aligned with `:core:api-contract`.

## UI tests

Add focused tests for:

- login validation;
- empty plants state;
- populated plants state;
- plant details with partially missing readings;
- offline cached state;
- claim-code errors;
- calibration steps;
- alert rules.

Prefer semantics-based selectors over text-only selectors where localization would make tests fragile.

## Quality

- Run formatting and static-analysis tasks already used by the repository.
- Ensure no blocking I/O runs on the main thread.
- Collect flows using lifecycle-aware APIs.
- Avoid unstable parameters that cause unnecessary recomposition.
- Add `contentDescription` or semantic labels where needed.
- Ensure touch targets and contrast are accessible.
- Redact tokens and authorization headers in all logging.

## Documentation

Create or update frontend documentation with:

- architecture overview;
- package/module structure;
- dependency graph;
- local backend URL configuration;
- Android emulator backend address;
- iOS simulator backend address;
- session/token flow;
- offline-cache behavior;
- SSE lifecycle and reconnect behavior;
- localization instructions;
- Android and iOS run commands;
- tests and checks;
- known limitations.

Add a concise architecture decision record explaining:

1. why the server is the source of truth;
2. why SQLDelight is used as a cache;
3. why SSE is used instead of continuous polling;
4. why the app uses common ViewModels and shared Compose UI;
5. why feature packages are preferred over many Gradle modules at this stage.

## Final verification

Run the actual available equivalents of:

```bash
./gradlew :core:api-contract:allTests
./gradlew :app:composeApp:allTests
./gradlew :app:composeApp:assembleDebug
./gradlew :app:composeApp:compileKotlinIosSimulatorArm64
```

Also run repository formatting/static-analysis checks.

Report:

1. completed steps;
2. files and modules added;
3. screens implemented;
4. API endpoints consumed;
5. local database tables;
6. tests and build commands executed;
7. any iOS checks that could not run and why;
8. deviations from this task and why;
9. remaining backend dependencies or limitations.

---

## Non-goals

Do not implement in this task:

- backend or ESP32 firmware;
- MQTT;
- Bluetooth provisioning;
- AI plant diagnosis;
- automatic plant-species recognition;
- camera-based health analysis;
- social features;
- payments or subscriptions;
- complex multi-account sharing;
- background polling every few seconds;
- a custom navigation framework;
- a custom generic MVI framework;
- a full charting framework when a focused line chart is sufficient;
- production push credentials.

---

## Definition of done

The frontend task is complete when:

- Android and iOS launch the shared Compose Multiplatform application;
- a user can register, sign in, restore a session, and log out;
- cached plants and measurements are available offline;
- a user can create and edit a plant;
- a device can be claimed and calibrated;
- plant details show latest measurements and historical charts;
- SSE updates latest measurements while the app is active;
- alerts and alert rules are usable;
- Kazakh, Russian, and English localization is present;
- light and dark themes work;
- tokens are stored securely and never logged;
- common, Android, and iOS builds pass;
- automated tests cover critical state, repository, and session behavior;
- architecture and local development are documented.
