# Frontend

The client uses the existing `:app:shared` module as the shared Compose Multiplatform application. Android and iOS keep thin platform entry points; API DTOs continue to come from `:core:api-contract`, so backend contracts are not duplicated in client modules.

## Step 1 architecture

- `app/` owns startup state, the shared snackbar host, and type-safe root/auth/main navigation.
- `core/designsystem/` contains the small Material 3 theme and reusable UI states/components.
- `core/network/` owns environment and backend URL configuration. Repositories must receive `BackendConfig`; they must not contain URLs.
- `core/database/` contains the SQLDelight driver boundary and account-scoped cache metadata schema.
- `core/connectivity/` and `core/lifecycle/` define shared state boundaries for later features.
- `di/` provides the Koin application module.

The initial authenticated features are placeholders. Firebase session handling and real repositories belong to later steps in `frontend-task.md`.

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
