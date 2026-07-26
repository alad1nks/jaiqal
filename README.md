This is a Kotlin Multiplatform project targeting Android, iOS, Web, Desktop (JVM), Server.

* [/app/iosApp](./app/iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
  - Standard run: `./gradlew :app:desktopApp:run`
- Server: `./gradlew :server:run`
- Web app:
  - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.

### Server configuration

The server reads configuration from environment variables. `HTTP_PORT` defaults to
`8080`; all database and JWT values are required. `ALLOWED_ORIGINS` is a
comma-separated list of complete origins and can be empty for server-to-server use.

```shell
HTTP_PORT=8080
DATABASE_URL=jdbc:postgresql://localhost:5432/jaiqal
DATABASE_USER=jaiqal
DATABASE_PASSWORD=replace-with-local-password
JWT_ISSUER=https://auth.example.com
JWT_AUDIENCE=jaiqal-app
JWT_SECRET=replace-with-a-long-random-secret
ALLOWED_ORIGINS=http://localhost:8081,http://localhost:3000
# Optional telemetry controls (shown with defaults)
TELEMETRY_PAST_WINDOW_SECONDS=2592000
TELEMETRY_FUTURE_WINDOW_SECONDS=300
TELEMETRY_MIN_TEMPERATURE_CELSIUS=-50
TELEMETRY_MAX_TEMPERATURE_CELSIUS=100
TELEMETRY_MIN_ADC=0
TELEMETRY_MAX_ADC=65535
TELEMETRY_NEXT_UPLOAD_SECONDS=60
```

With the variables exported, start the server with `./gradlew :server:run`.
The liveness endpoint is `/health/live`; `/health/ready` additionally checks that
PostgreSQL accepts a `SELECT 1` query.

At server startup, HikariCP creates the PostgreSQL connection pool and Flyway
applies pending migrations from `server/src/main/resources/db/migration`. Flyway
records applied versions in `flyway_schema_history`, so restarting against an
up-to-date database is safe. Persistence adapters use Exposed and keep its table
types inside the `infrastructure.database` package; feature code depends on the
repository interfaces instead.

Repository and migration integration tests run against an ephemeral PostgreSQL
Testcontainer and therefore require a working Docker-compatible container runtime:

```shell
./gradlew :server:test
```

### Device telemetry

Provisioned devices authenticate with the separately issued raw token. Only its
SHA-256 hash is stored by the server. A device sends the raw value using the
`Device` authorization scheme; a device identifier in the JSON body is neither
needed nor trusted:

```http
POST /api/device/v1/measurements HTTP/1.1
Authorization: Device replace-with-provisioned-token
Content-Type: application/json

{"sequence":42,"firmwareVersion":"1.0.0","soilMoistureRaw":1530,"airTemperatureCelsius":23.5,"airHumidityPercent":51.0,"lightRaw":840}
```

For offline buffering, `POST /api/device/v1/measurements/batch` accepts an object
with a `measurements` array of 1–100 entries. Sequences are idempotent per device.
Missing timestamps, or timestamps outside the configured window, use receipt time
and record the fallback reason in measurement metadata. Successful inserts update
the latest state only when their measurement time is newer.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
