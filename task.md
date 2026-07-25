# Жайқал — backend implementation task

## Goal

Implement the first production-ready backend foundation for **Жайқал**, a Kotlin Multiplatform application that monitors a house plant through an ESP32 device.

The ESP32 periodically sends soil moisture, air temperature, air humidity, and light readings. The KMP application allows a user to register plants and devices, view the latest readings and history, and receive alerts.

Build this as a **modular monolith**, not as microservices.

## Working rules

1. Inspect the existing repository before changing anything:
   - read `README.md`, `settings.gradle.kts`, version catalogs, `AGENTS.md`, and existing modules;
   - reuse established package names, conventions, dependency injection, error handling, and testing style;
   - do not overwrite or reorganize unrelated code.
2. Create or update a short implementation plan before coding.
3. Complete the steps below in order.
4. After every step:
   - compile all affected modules;
   - run relevant tests;
   - fix failures before continuing.
5. Do not introduce Redis, Kafka, MQTT, Kubernetes, TimescaleDB, or microservices.
6. Never commit secrets. Read configuration from environment variables.
7. Prefer explicit interfaces and constructor injection. Use the repository's existing DI solution; otherwise use manual dependency wiring.
8. If the existing project structure conflicts with this task, preserve the existing structure and document the adjustment.

---

## Target architecture

Use these Gradle modules when they do not already have an equivalent:

```text
:core:api-contract
:app:composeApp
:server
```

`core:api-contract` is a Kotlin Multiplatform module shared by the KMP client and Ktor server. It must contain only:

- `@Serializable` API requests and responses;
- public enums;
- API validation primitives that are truly shared;
- no database tables, repositories, secrets, or server-only entities.

`server` is a JVM Ktor application organized by feature packages:

```text
server/
└── src/main/kotlin/<base-package>/
    ├── Application.kt
    ├── config/
    ├── auth/
    ├── users/
    ├── plants/
    ├── devices/
    ├── telemetry/
    ├── alerts/
    ├── notifications/
    └── infrastructure/
        ├── database/
        ├── security/
        └── monitoring/
```

Use:

- Ktor;
- kotlinx.serialization;
- PostgreSQL;
- Exposed with JDBC;
- HikariCP;
- Flyway SQL migrations;
- Testcontainers for PostgreSQL integration tests;
- the existing project logging solution, or SLF4J/Logback if none exists.

Use versions already defined in the version catalog. If a required dependency is absent, add a current stable version compatible with the project's Kotlin and Ktor versions.

---

# Step 1 — Server and shared-contract foundation

## Tasks

1. Create `:core:api-contract` if an equivalent shared API module does not exist.
2. Create the `:server` Ktor JVM module if it does not exist.
3. Add Ktor configuration for:
   - JSON content negotiation using kotlinx.serialization;
   - request logging with secrets and authorization headers redacted;
   - `StatusPages`;
   - CORS configurable by environment;
   - user JWT authentication;
   - a separate device-token authentication provider.
4. Add:

```text
GET /health/live
GET /health/ready
```

`live` confirms that the process is running.  
`ready` confirms that PostgreSQL is reachable.

5. Create a consistent API error contract:

```kotlin
@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val requestId: String? = null,
)
```

6. Add a request ID to responses and logs.
7. Add environment-based configuration for:

```text
HTTP_PORT
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
JWT_ISSUER
JWT_AUDIENCE
JWT_SECRET
ALLOWED_ORIGINS
```

## Acceptance criteria

- The server starts locally.
- `GET /health/live` returns HTTP 200.
- `GET /health/ready` reflects database availability.
- Invalid JSON and unhandled errors use `ApiErrorResponse`.
- No secrets are printed in logs.
- Server and shared-contract tests pass.

---

# Step 2 — PostgreSQL schema and persistence

## Tasks

Configure HikariCP, Exposed, and Flyway.

Create an initial Flyway migration containing:

### `users`

```text
id UUID primary key
email VARCHAR unique not null
password_hash VARCHAR not null
created_at TIMESTAMPTZ not null
```

### `plants`

```text
id UUID primary key
user_id UUID references users(id)
name VARCHAR not null
species VARCHAR null
image_url VARCHAR null
created_at TIMESTAMPTZ not null
archived_at TIMESTAMPTZ null
```

### `devices`

```text
id UUID primary key
plant_id UUID references plants(id) null
name VARCHAR not null
token_hash VARCHAR not null
firmware_version VARCHAR null
last_seen_at TIMESTAMPTZ null
soil_dry_raw INTEGER null
soil_wet_raw INTEGER null
disabled_at TIMESTAMPTZ null
created_at TIMESTAMPTZ not null
```

### `measurements`

```text
id BIGSERIAL primary key
device_id UUID references devices(id)
sequence BIGINT not null
measured_at TIMESTAMPTZ not null
received_at TIMESTAMPTZ not null
soil_moisture_raw INTEGER null
soil_moisture_percent DOUBLE PRECISION null
air_temperature_celsius DOUBLE PRECISION null
air_humidity_percent DOUBLE PRECISION null
light_raw INTEGER null
extra JSONB not null default '{}'
```

Constraints and indexes:

```text
UNIQUE(device_id, sequence)
INDEX(device_id, measured_at DESC)
INDEX(plant_id) on devices
INDEX(user_id) on plants
```

### `device_latest_state`

```text
device_id UUID primary key references devices(id)
measurement_id BIGINT references measurements(id)
updated_at TIMESTAMPTZ not null
```

Also create:

```text
refresh_tokens
alert_rules
alert_events
notification_outbox
```

Choose sensible columns, foreign keys, indexes, and timestamps for these tables. Store refresh tokens and device tokens only as secure hashes.

Create repository interfaces and Exposed implementations. Do not expose Exposed entities outside the infrastructure layer.

## Acceptance criteria

- Migrations run on a clean PostgreSQL database.
- Running migrations a second time makes no changes.
- Repository integration tests use Testcontainers.
- A measurement cannot be inserted twice for the same `(device_id, sequence)`.
- Deleting a user cannot silently orphan plants or devices.

---

# Step 3 — Device provisioning and telemetry ingestion

## Shared API contracts

Add serializable contracts equivalent to:

```kotlin
@Serializable
data class DeviceMeasurementRequest(
    val sequence: Long,
    val firmwareVersion: String? = null,
    val measuredAt: Instant? = null,
    val soilMoistureRaw: Int? = null,
    val airTemperatureCelsius: Double? = null,
    val airHumidityPercent: Double? = null,
    val lightRaw: Int? = null,
)

@Serializable
data class DeviceMeasurementResponse(
    val accepted: Boolean,
    val duplicate: Boolean,
    val serverTime: Instant,
    val nextUploadSeconds: Int,
)
```

Use the time type and serializer already established by the project. If none exists, use `kotlinx.datetime.Instant`.

## Endpoints

```text
POST /api/device/v1/measurements
POST /api/device/v1/measurements/batch
```

Authentication:

```text
Authorization: Device <token>
```

The token identifies the device. Do not trust a `deviceId` supplied in the JSON body.

## Ingestion behavior

1. Authenticate the device using a constant-time hash comparison.
2. Reject disabled devices.
3. Validate:
   - `sequence >= 0`;
   - batch size between 1 and 100;
   - timestamps are within a configurable acceptable window;
   - humidity is between 0 and 100;
   - temperature and ADC values are within safe configurable bounds;
   - at least one sensor value is present.
4. When `measuredAt` is missing or invalid, use server time and preserve enough information to diagnose the fallback.
5. Calculate soil moisture percentage from `soilDryRaw` and `soilWetRaw`:
   - clamp the result to `0..100`;
   - leave it null until calibration exists;
   - handle reversed ADC direction.
6. Insert the measurement idempotently using `(device_id, sequence)`.
7. Upsert `device_latest_state` only when the new measurement is newer.
8. Update `devices.last_seen_at` and `firmware_version`.
9. Return a configurable `nextUploadSeconds`, defaulting to 60.
10. Publish an in-process `MeasurementReceived` event after the transaction commits.

## Acceptance criteria

- A valid device can upload a measurement.
- An invalid token receives HTTP 401.
- A disabled device receives HTTP 403.
- Repeating the same sequence returns a successful duplicate response without creating another row.
- Batch insertion is transactional.
- Calibration is covered by unit tests, including reversed and equal wet/dry values.
- Telemetry endpoints have integration tests.

---

# Step 4 — User authentication, plants, and device claiming

## Authentication endpoints

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
```

Requirements:

- normalize email addresses;
- hash passwords using Argon2id or the existing secure password-hashing solution;
- short-lived access JWT;
- rotating refresh tokens;
- store only refresh-token hashes;
- invalidate the previous refresh token after successful rotation;
- never include password hashes in API responses.

## Plant endpoints

```text
GET    /api/v1/plants
POST   /api/v1/plants
GET    /api/v1/plants/{plantId}
PATCH  /api/v1/plants/{plantId}
DELETE /api/v1/plants/{plantId}
```

Use soft deletion or archiving for plants.

## Device endpoints

```text
POST  /api/v1/devices/claim
GET   /api/v1/devices
GET   /api/v1/devices/{deviceId}
PATCH /api/v1/devices/{deviceId}
PATCH /api/v1/devices/{deviceId}/calibration
POST  /api/v1/devices/{deviceId}/rotate-token
```

For the first version, implement device claiming using a one-time claim code:

1. An administrator or development command creates a device and one-time claim code.
2. The user submits the claim code and `plantId`.
3. The server verifies that the plant belongs to the authenticated user.
4. The code is consumed exactly once.
5. The device is attached to the plant.

Every user endpoint must verify resource ownership. Returning HTTP 404 instead of exposing the existence of another user's resource is preferred.

## Acceptance criteria

- A user can register, log in, refresh, and log out.
- Refresh-token replay is rejected.
- A user can manage only their own plants and devices.
- A claim code cannot be used twice.
- Device-token rotation invalidates the previous token.
- Unit and integration tests cover authorization boundaries.

---

# Step 5 — Latest state, history, and realtime updates

## Endpoints

```text
GET /api/v1/plants/{plantId}/latest
GET /api/v1/plants/{plantId}/history
GET /api/v1/plants/{plantId}/stream
```

### Latest response

Return:

- plant and device identifiers;
- measured time;
- server receipt time;
- soil moisture percentage and raw value;
- air temperature;
- air humidity;
- light raw value;
- device online/offline state;
- calibration state.

### History query

Support:

```text
from=<ISO-8601>
to=<ISO-8601>
interval=raw|5m|1h|1d
```

Rules:

- limit the maximum time range;
- limit the number of returned points;
- use SQL aggregation for bucketed history;
- do not load all raw rows into application memory before aggregation.

### Realtime

Use Server-Sent Events for one-way server-to-app updates.

Send an event after a committed measurement for a plant owned by the authenticated user. Include heartbeat events and clean up subscriptions when clients disconnect.

The REST endpoint remains the source of truth. SSE is only for notifying the UI about updates.

## Acceptance criteria

- The latest endpoint does not scan the full measurement history.
- History intervals return correctly bucketed values.
- Invalid or excessive date ranges return HTTP 400.
- One user cannot subscribe to another user's plant.
- An inserted measurement generates an SSE update.

---

# Step 6 — Alert rules and reliable notifications

## Endpoints

```text
GET /api/v1/plants/{plantId}/alert-rules
PUT /api/v1/plants/{plantId}/alert-rules
GET /api/v1/plants/{plantId}/alerts
POST /api/v1/plants/{plantId}/alerts/{alertId}/acknowledge
```

Initially support:

- low soil moisture;
- high temperature;
- low temperature;
- device offline.

Requirements:

1. Rules must support a threshold and required duration.
2. Do not create an alert from one transient measurement when a duration is configured.
3. Deduplicate active alerts of the same type for the same plant.
4. Close an alert when the condition recovers for a configurable duration.
5. Write notification work to `notification_outbox` in the same transaction as the alert.
6. Implement a background worker that:
   - claims pending outbox rows safely;
   - retries with exponential backoff;
   - records attempts and the last error;
   - marks a row completed after successful delivery;
   - does not send the same notification concurrently from two workers.
7. Define a notification sender interface.
8. Provide a logging/fake implementation for development and tests.
9. Keep FCM/APNs integrations optional and configuration-driven.

## Acceptance criteria

- Alert creation, deduplication, recovery, and duration logic have unit tests.
- Notification failures do not lose the outbox row.
- Retrying the worker is safe.
- The development environment works without real FCM/APNs credentials.

---

# Step 7 — Local environment, tests, and documentation

## Docker Compose

Provide a local setup with:

```text
server
postgres
```

Add a health check for PostgreSQL and make server startup wait for readiness without fixed sleeps.

Provide `.env.example` with fake development values only.

## Tests

At minimum, cover:

- authentication and token rotation;
- plant/device ownership;
- device-token authentication;
- telemetry validation;
- duplicate sequence handling;
- calibration;
- latest-state upsert;
- history aggregation;
- alert duration and deduplication;
- notification outbox retries.

Use:

- unit tests for pure business logic;
- Ktor application tests for routing;
- Testcontainers for repository and migration tests.

## Documentation

Update or create `README.md` with:

- architecture overview;
- module descriptions;
- required environment variables;
- local startup commands;
- migration commands;
- test commands;
- example user login request;
- example device telemetry request;
- device pairing flow;
- known limitations.

Add an API request collection using the repository's existing format. If none exists, add an `.http` file runnable from IntelliJ IDEA.

## Final verification

Run all applicable checks, for example:

```bash
./gradlew :core:api-contract:allTests
./gradlew :server:test
./gradlew :server:build
```

Use the actual task names available in the repository.

Report:

1. implemented steps;
2. files and modules added;
3. migrations created;
4. API endpoints;
5. tests executed and results;
6. any deviations from this task and why;
7. remaining work.

---

## Non-goals

Do not implement in this task:

- automatic plant-species recognition;
- AI recommendations;
- camera uploads unless existing infrastructure already supports them;
- MQTT;
- Redis;
- Kafka;
- TimescaleDB;
- microservices;
- Kubernetes;
- a production FCM/APNs account configuration;
- firmware implementation beyond documenting the required HTTP contract.

## Definition of done

The task is complete when:

- the Ktor server starts with PostgreSQL;
- migrations run successfully;
- a user can register and create a plant;
- a device can be claimed and securely upload measurements;
- the user can retrieve latest and historical readings;
- authorized SSE clients receive new readings;
- alert logic creates reliable outbox entries;
- automated tests pass;
- local setup and API usage are documented.
