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
JWT_ACCESS_TOKEN_SECONDS=900
JWT_REFRESH_TOKEN_SECONDS=2592000
ALLOWED_ORIGINS=http://localhost:8081,http://localhost:3000
# Optional telemetry controls (shown with defaults)
TELEMETRY_PAST_WINDOW_SECONDS=2592000
TELEMETRY_FUTURE_WINDOW_SECONDS=300
TELEMETRY_MIN_TEMPERATURE_CELSIUS=-50
TELEMETRY_MAX_TEMPERATURE_CELSIUS=100
TELEMETRY_MIN_ADC=0
TELEMETRY_MAX_ADC=65535
TELEMETRY_NEXT_UPLOAD_SECONDS=60
HISTORY_MAX_RANGE_SECONDS=31536000
HISTORY_DEFAULT_RANGE_SECONDS=86400
HISTORY_MAX_POINTS=2000
DEVICE_ONLINE_WINDOW_SECONDS=180
SSE_HEARTBEAT_SECONDS=15
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

### Users, plants, and device claiming

User authentication is available under `/api/v1/auth` through `register`,
`login`, `refresh`, and authenticated `logout` endpoints. Passwords are stored as
Argon2id hashes. Access JWTs are short lived; opaque refresh tokens rotate on each
use, and replaying a rotated token is rejected.

Authenticated plant management uses `GET/POST /api/v1/plants` and
`GET/PATCH/DELETE /api/v1/plants/{plantId}`. Deletion archives a plant. Device
management uses `/api/v1/devices`, including claim, calibration, update, and
token-rotation operations. All lookups are scoped to the JWT subject and return
404 for resources owned by another account.

In development, provision an unattached sensor and a 24-hour one-time claim code:

```shell
DATABASE_URL=jdbc:postgresql://localhost:5432/jaiqal \
DATABASE_USER=jaiqal DATABASE_PASSWORD=local-password DEVICE_NAME="Living room" \
./gradlew :server:provisionDevice
```

The command prints the raw device token and claim code exactly for provisioning;
the database stores only their SHA-256 hashes. Claim the device by sending the
code and an owned plant ID to `POST /api/v1/devices/claim`. Rotating a device
token immediately invalidates the previous token.

### Plant telemetry reads and realtime updates

Authenticated clients can read the indexed latest state at
`GET /api/v1/plants/{plantId}/latest`. History is available at
`GET /api/v1/plants/{plantId}/history` with ISO-8601 `from`/`to` parameters and
an `interval` of `raw`, `5m`, `1h`, or `1d`. Bucketed values are averaged in
PostgreSQL and every query is constrained by configured range and point limits.

`GET /api/v1/plants/{plantId}/stream` is an authenticated Server-Sent Events
notification stream. Measurement events tell clients to refresh REST state;
heartbeat comments keep idle connections alive.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
