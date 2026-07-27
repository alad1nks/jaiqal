# Жайқал

Жайқал is a Kotlin Multiplatform application and a production-oriented Ktor backend for monitoring house plants with ESP32 sensors. The backend is a **modular monolith**: feature packages share one process and one PostgreSQL database, while explicit repository and service boundaries keep business logic independent of persistence.

## Architecture and modules

| Module | Purpose |
| --- | --- |
| `:core:api-contract` | KMP-safe serializable requests, responses, and public API enums. |
| `:server` | JVM Ktor API, authentication, telemetry, alerts, notification worker, Exposed/JDBC persistence, and Flyway migrations. |
| `:app:shared` | Shared Compose UI and client code for Android, iOS, desktop, and web launchers. |

The server is organized by `auth`, `users`, `plants`, `devices`, `telemetry`, `alerts`, and `notifications` features. PostgreSQL access is isolated in `infrastructure/database`. Measurements are committed before an in-process event is published. Alert transitions and notification outbox records are committed atomically; a background worker claims and retries delivery. The bundled sender logs notifications, so local development needs no FCM or APNs credentials.

## Requirements

- Docker with Docker Compose (recommended), or JDK 21 and PostgreSQL 14+
- A Docker-compatible runtime to execute Testcontainers integration tests

Copy the development template before starting locally:

```bash
cp .env.example .env
```

`.env.example` contains fake local values only. Never use its database password in a deployed environment and never commit `.env`.

### Environment variables

The required variables are `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `FIREBASE_PROJECT_ID`. `HTTP_PORT` defaults to `8080`; `ALLOWED_ORIGINS` is a comma-separated allowlist of complete origins and may be empty.

Firebase Admin uses Application Default Credentials. In Google-hosted environments, use workload identity. For local JVM execution, set `GOOGLE_APPLICATION_CREDENTIALS` to a service-account JSON stored outside this repository. Startup fails before opening the HTTP port if the project ID or ADC is unavailable. `FIREBASE_CHECK_REVOKED_TOKENS` defaults to `false`; enabling it adds the Firebase remote check for token revocation and disabled users.

The project has no pre-existing users, so `FIREBASE_AUTO_PROVISION_USERS` defaults to `true`. The first valid Firebase token atomically creates a passwordless internal `users` row and its identity; tokens without an email are supported. Set the flag to `false` to refuse unknown Firebase UIDs without creating an account.

The `firebase-user` authentication provider verifies Bearer ID tokens, resolves them to internal user UUIDs, and returns neutral `401` responses for authentication failures. All protected user routes use this provider. The `device-token` provider and ESP32 authentication remain separate and unchanged. Optional controls, with defaults, are documented in `.env.example`: Firebase revocation checking and user auto-provisioning; telemetry time/temperature/ADC limits and upload interval; history range, point, online, and SSE heartbeat limits; and alert/outbox polling, batching, and retry limits.

## Local startup

Start PostgreSQL and the server, rebuilding the image when sources change:

```bash
docker compose up --build
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
```

Compose waits for PostgreSQL's `pg_isready` health check rather than using a fixed sleep. Stop the stack with `docker compose down`; add `--volumes` to delete local database data.

To run against a PostgreSQL instance outside Compose, export the variables from `.env.example`, change `DATABASE_URL` to use `localhost`, and run:

```bash
./gradlew :server:run
```

## Database migrations

Flyway migrations live in `server/src/main/resources/db/migration`. Server startup automatically applies pending migrations and records them in `flyway_schema_history`; rerunning the server is idempotent. To apply migrations locally, start the server against the target database:

```bash
./gradlew :server:run
```

The schema covers users and external identities, plants, devices, one-time device claim codes, measurements and latest state, rotating refresh tokens, alert rules/events/processing state, and the reliable notification outbox. Firebase identities map `(provider, Firebase UID)` to the internal user UUID; existing foreign-key structure is preserved.

## API quick start

An IntelliJ HTTP Client collection with health, authentication, plant, claiming, telemetry, history, and alert examples is available at [`api.http`](api.http).

### Firebase-authenticated request

```bash
curl http://localhost:8080/api/v1/plants \
  -H 'Authorization: Bearer paste-firebase-id-token'
```

The future client obtains the ID Token from Firebase Authentication. The backend verifies it, maps the Firebase UID to an internal UUID, and uses that UUID for ownership checks. `/api/v1/auth/register`, `/login`, `/refresh`, and `/logout` return `410 Gone` with `LEGACY_AUTH_DISABLED`; they never issue or process application-owned tokens.

`GET /api/v1/auth/me` returns the authenticated internal user's UUID, email, and email-verification status. It does not expose the Firebase UID, ID Token, auth claims, or server credentials. Health endpoints continue to return only service/database readiness state.

### Device telemetry

```bash
curl -X POST http://localhost:8080/api/device/v1/measurements \
  -H 'Authorization: Device replace-with-provisioned-token' \
  -H 'Content-Type: application/json' \
  -d '{"sequence":42,"firmwareVersion":"1.0.0","soilMoistureRaw":1530,"airTemperatureCelsius":23.5,"airHumidityPercent":51.0,"lightRaw":840}'
```

The device is identified only by the token. Sequences are idempotent per device; batches accept 1–100 measurements. Invalid or absent device timestamps fall back to receipt time with diagnostic metadata. Soil percentage remains null until dry/wet calibration exists.

## Device pairing flow

1. With the database environment exported, create an unattached device and 24-hour one-time code:
   ```bash
   DEVICE_NAME='Living room' ./gradlew :server:provisionDevice
   ```
2. Securely transfer the printed raw device token to the ESP32. Only its SHA-256 hash is stored.
3. An authenticated user creates a plant and submits the claim code plus owned plant ID to `POST /api/v1/devices/claim`.
4. The claim code is consumed once and the device is attached to that plant.
5. The device uploads using `Authorization: Device <token>`. Token rotation immediately invalidates the previous token.

## Main endpoints

- Health: `GET /health/live`, `GET /health/ready`
- Current user: `GET /api/v1/auth/me` with a Firebase ID Token
- Disabled legacy authentication: `POST /api/v1/auth/{register,login,refresh,logout}` returns `410 Gone`
- Plants: CRUD under `/api/v1/plants`
- Devices: claim, list, update, calibrate, and rotate token under `/api/v1/devices`
- Telemetry ingestion: `POST /api/device/v1/measurements` and `/batch`
- Reads: `/api/v1/plants/{plantId}/{latest,history,stream}`; history intervals are `raw`, `5m`, `1h`, and `1d`, and stream is SSE
- Alerts: rules, history, and acknowledgement under `/api/v1/plants/{plantId}`

All user-owned resources are scoped to the internal UUID from `UserPrincipal`. Cross-user lookups return 404 where possible to avoid disclosing resource existence.

## Tests and verification

```bash
./gradlew :core:api-contract:allTests
./gradlew :server:test
./gradlew :server:build
```

The suite combines pure unit tests, Ktor route tests, and PostgreSQL Testcontainers integration tests. It covers Firebase verification and identity provisioning, concurrent first login, ownership boundaries, device authentication, telemetry validation/idempotency/calibration, latest-state ordering and history aggregation, alert duration/deduplication/recovery, migrations, and outbox retry safety. Firebase tests use a fake verifier and never call the real service.

## Client applications

- Android: `./gradlew :app:androidApp:assembleDebug`
- Desktop: `./gradlew :app:desktopApp:run`
- Web: `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
- iOS: open `app/iosApp` in Xcode

## Known limitations

- Notification delivery uses the logging sender; production FCM/APNs adapters and credentials are intentionally not included.
- The in-process measurement event bus and SSE subscriptions are node-local; the server is currently designed as one modular-monolith instance.
- Offline-device alert evaluation is polling-based, and telemetry aggregation uses standard PostgreSQL rather than TimescaleDB.
- Device provisioning is an operator Gradle command; there is no administrator UI.
- Firmware, MQTT, image uploads, species recognition, and AI recommendations are outside the current scope.
