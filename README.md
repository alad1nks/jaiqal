# Жайқал

Жайқал is a Kotlin Multiplatform application and a production-oriented Ktor backend for monitoring house plants with ESP32 sensors. The backend is a **modular monolith**: feature packages share one process and one PostgreSQL database, while explicit repository and service boundaries keep business logic independent of persistence.

## Architecture and modules

| Module | Purpose |
| --- | --- |
| `:core:api-contract` | KMP-safe serializable requests, responses, and public API enums. |
| `:core:data` | Shared Firebase session boundary, Ktor client, SQLDelight cache, connectivity/lifecycle abstractions, and their Koin bindings. |
| `:core:designsystem` | Shared Material 3 theme and reusable UI components. |
| `:core:testing` | Reusable client test fixtures and fakes. |
| `:resources` | Public Compose Multiplatform resources shared by UI modules. |
| `:feature:auth` | Authentication screens, view models, routes, and feature DI. |
| `:feature:devices` | Device claiming, device details, soil calibration wizard, routes, and feature DI. |
| `:feature:plants` | Plant data/domain/presentation layers, routes, and feature DI. |
| `:feature:alerts` | Cached alert events, acknowledgement, rule editing, routes, and feature DI. |
| `:feature:settings` | Settings UI, view model, routes, and feature DI. |
| `:server` | JVM Ktor API, authentication, telemetry, alerts, notification worker, Exposed/JDBC persistence, and Flyway migrations. |
| `:app:shared` | Thin shared application shell: root navigation, product composition, platform bootstrap, and launcher-facing APIs. |

The client follows the dependency direction `launcher -> app:shared -> feature -> core`. A feature owns its routes, UI, view models, and feature-specific data/domain code; `:app:shared` only assembles features into the product. Modules are split by substantial product feature or shared responsibility, not by every individual architectural layer.

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

The runtime server requires `DATABASE_URL`, `DATABASE_USER`, either
`DATABASE_PASSWORD` or `DATABASE_PASSWORD_FILE`, and `FIREBASE_PROJECT_ID`.
`DATABASE_*` must identify a DML-only runtime role.
Production also requires `DEPLOYMENT_COMMIT_SHA`, the full lowercase Git commit
used for the deployed image; `/health/live` returns it as
`X-Deployment-Commit` for rollout and DAST correlation.
Production migrations use a separate one-shot process with
`MIGRATION_DATABASE_URL`, `MIGRATION_DATABASE_USER`, and
`MIGRATION_DATABASE_PASSWORD`. `HTTP_PORT` defaults to `8080`;
`ALLOWED_ORIGINS` is a comma-separated allowlist of complete origins and may be
empty. `APP_ENVIRONMENT` defaults to `development`.

| Firebase variable | Purpose |
| --- | --- |
| `FIREBASE_PROJECT_ID` | Firebase project whose ID Tokens are accepted. |
| `GOOGLE_APPLICATION_CREDENTIALS` | Optional path to an external service-account JSON for local/non-workload-identity environments. |
| `FIREBASE_AUTO_PROVISION_USERS` | Allow creation of an internal user after the first valid token; defaults to `true`. |
| `FIREBASE_CHECK_REVOKED_TOKENS` | Check token revocation and disabled users remotely; defaults to `false`. |

Production is fail-closed. With `APP_ENVIRONMENT=production`, startup requires
`FIREBASE_CHECK_REVOKED_TOKENS=true`, an HTTPS `PUBLIC_API_URL`,
`TRUSTED_PROXY_TERMINATES_TLS=true`, a non-empty `TRUSTED_PROXY_CIDRS` containing
only the direct ingress peer networks, HTTPS-only explicit CORS origins, and a
`DATABASE_URL` containing `sslmode=verify-full&channelBinding=require`. Plain HTTP
requests are rejected with `426 HTTPS_REQUIRED`; accepted responses include HSTS.
The trusted ingress must remove any client-supplied `X-Forwarded-Proto`, set it to
`https`, and be the only network peer allowed to reach the Ktor port. The server
honours that header only when the socket peer is a literal IPv4/IPv6 address inside
`TRUSTED_PROXY_CIDRS`; hostnames, non-canonical CIDRs and `/0` are rejected without
DNS resolution, and `X-Forwarded-For` is never an authority source.
The production server never runs Flyway and rejects migration credentials in its
environment, so a compromised runtime process does not inherit the DDL role.
`DATABASE_PASSWORD_FILE` may replace `DATABASE_PASSWORD` with an absolute,
read-only UTF-8 file up to 4096 bytes; configuring both is rejected. The same
rule applies to migration jobs through `MIGRATION_DATABASE_PASSWORD_FILE`.

HTTP abuse controls are configurable without changing API contracts:

| Variable | Default | Purpose |
| --- | ---: | --- |
| `HTTP_MAX_BODY_BYTES` | `65536` | Maximum request body outside the telemetry batch endpoint. |
| `TELEMETRY_BATCH_MAX_BODY_BYTES` | `131072` | Maximum body for `/api/device/v1/measurements/batch`. |
| `RATE_LIMIT_PERIOD_SECONDS` | `60` | Refill period for the in-process token buckets. |
| `READINESS_RATE_LIMIT_REQUESTS` | `30` | Requests to `/health/ready` per peer and period. |
| `READINESS_CACHE_TTL_MILLISECONDS` | `1000` | Shared readiness-result cache TTL; constrained to 1–5000 ms and protected by single-flight. |
| `USER_API_RATE_LIMIT_REQUESTS` | `120` | Requests to `/api/v1` per peer and period, applied before Firebase verification. |
| `TELEMETRY_RATE_LIMIT_REQUESTS` | `120` | Telemetry requests per peer and period, applied before device authentication. |
| `SSE_MAX_CONNECTIONS_PER_USER` | `3` | Concurrent telemetry streams per authenticated user and server instance. |
| `SSE_MAX_CONNECTIONS_PER_IP` | `10` | Concurrent telemetry streams per direct peer and server instance. |
| `SSE_MAX_LIFETIME_SECONDS` | `300` | Hard maximum lifetime of one telemetry stream; limited to one hour. |
| `SSE_OWNERSHIP_RECHECK_SECONDS` | `30` | Interval for confirming that the user still owns the streamed plant. |
| `TELEMETRY_DEVICE_QUOTA_PERIOD_SECONDS` | `86400` | PostgreSQL-backed per-device ingestion quota window. |
| `TELEMETRY_DEVICE_QUOTA_MAX_MEASUREMENTS` | `1440` | Validated measurement items allowed per device and quota window; must be at least 100. |
| `TELEMETRY_ANOMALY_BREACH_WINDOWS` | `3` | Distinct exhausted quota windows required before temporary quarantine; cannot be below 3. |
| `TELEMETRY_ANOMALY_WINDOW_SECONDS` | `604800` | Observation horizon for distinct breached windows; must cover every required quota window. |
| `TELEMETRY_QUARANTINE_SECONDS` | `3600` | Temporary ingestion quarantine; bounded between 5 minutes and 7 days. |
| `TELEMETRY_RETENTION_DAYS` | `365` | Storage lifetime based on server receipt time; cannot be shorter than the history API range. |
| `TELEMETRY_RETENTION_INTERVAL_SECONDS` | `3600` | Delay between retention cycles. |
| `TELEMETRY_RETENTION_BATCH_SIZE` | `5000` | Rows locked and deleted per short retention transaction. |
| `TELEMETRY_RETENTION_MAX_BATCHES_PER_RUN` | `20` | Work cap per retention cycle. |
| `CAPACITY_MONITOR_INTERVAL_SECONDS` | `300` | Interval for PostgreSQL capacity checks. |
| `CAPACITY_MEASUREMENTS_WARN_ROWS` | `10000000` | Estimated measurement-row threshold for an operational warning. |
| `CAPACITY_MEASUREMENTS_WARN_BYTES` | `5368709120` | Measurements table/index size threshold for an operational warning. |
| `CAPACITY_DATABASE_WARN_BYTES` | `10737418240` | Whole-database size threshold for an operational warning. |

Exceeded body and request limits return the established `ApiErrorResponse` with
`413 PAYLOAD_TOO_LARGE` or `429 RATE_LIMITED`; rate-limit responses include
`Retry-After`. The application keys pre-authentication limits by the direct peer
address and deliberately does not trust client-supplied forwarded headers.
Production must expose the service only through a trusted HTTPS ingress that
enforces equivalent per-client limits. The in-process limiter is a secondary
bulkhead and is not a cluster-wide quota.

Readiness caches only the final `ready`/`unavailable` result for the configured
short TTL. Concurrent probes share one PostgreSQL check; after expiry the first
request performs a fresh check, so an expired successful result is never served.

The device quota is cluster-wide: PostgreSQL serializes consumption per authenticated
device, and both single uploads and batch items consume it before measurement
persistence. Exceeding it returns `429 RATE_LIMITED` with `Retry-After`. The server
counts at most one anomaly per exhausted quota window. Three distinct breached
windows within the configured observation horizon trigger a temporary quarantine,
returning `403 DEVICE_QUARANTINED` with `Retry-After`; repeated requests in one
burst cannot accelerate the transition. The transition emits a credential-free
`SECURITY_AUDIT` event. The owner can reset the anomaly state and quarantine with
`POST /api/v1/devices/{deviceId}/restore`; other users receive ownership-hiding
`404`, and restore attempts are audited. The server also logs
`SECURITY_CAPACITY_ALERT` at `WARN` when a configured measurements-row,
measurements-size, or database-size threshold is reached. Production log monitoring
must page the database owner on that event; tune thresholds to provisioned storage.

CI scans the clean checkout before building it. Any detected committed secret and
any High/Critical supported Docker/IaC misconfiguration fail the
supply-chain job. Dependency and final-image vulnerability scans remain separate
release gates. The resolved server runtime also has a reviewed Medium baseline at
`scripts/trivy-medium-baseline.txt`: CI rejects every new exact vulnerability,
package and installed-version tuple. Dependency updates must review the resulting
diff instead of broadening the baseline speculatively. The server `check` lifecycle
also scans its resolved runtime JARs and fails if the excluded Google Cloud Storage
client graph or classes return. Do not suppress a finding without a scoped,
documented, expiring exception; exposed credentials must be removed and rotated.
Before verification, SAST or supply-chain work starts, an isolated self-test job
proves that the Medium comparison accepts only exact reviewed tuples, Kubernetes
policy weakening is rejected, and mandatory immutable CI gates cannot be removed
or relaxed. Its fixtures live only in a temporary directory and the test verifies
that the repository worktree is unchanged.

The separate least-privilege `sast` job manually builds the server JVM classes
under CodeQL extraction and runs the `security-extended` Java/Kotlin query suite.
Its results are uploaded to GitHub code scanning; production promotion requires
this job as well as `verification` and `supply-chain`.

Pull requests also run a SHA-pinned dependency review and fail when a newly
introduced dependency has Moderate-or-higher known severity. Every checkout
disables persisted GitHub credentials, and `.github/CODEOWNERS` requires owner
review for authentication, migrations, deployment manifests, workflows and
security scripts. After these files have landed on `main`, apply and verify the
matching hosted branch protection, secret scanning and push protection with
[`scripts/configure-github-security.sh`](scripts/configure-github-security.sh),
following [`docs/github-security-settings.md`](docs/github-security-settings.md).

The separate [`staging-dast.yml`](.github/workflows/staging-dast.yml) workflow is
a manual, scheduled, and deployment-callable promotion gate against the real TLS
staging ingress. It verifies the exact deployed commit, proxy/header trust, CORS,
body and JSON limits, authentication, ownership-hiding `404`, rate limiting, and
server-bounded SSE using isolated minimal test users/plants/device. Fresh Firebase
ID tokens are minted from protected staging refresh tokens without logging them;
no scan responses or credentials are uploaded as artifacts. Configure the
`staging-security` GitHub Environment as described in
[`docs/staging-dast.md`](docs/staging-dast.md).

After a successful same-repository `CI` push to `main`, the protected
[`publish-production-image.yml`](.github/workflows/publish-production-image.yml)
workflow waits for the `production-signing` Environment, publishes the server to
GHCR, scans the exact digest, and creates a keyless image signature plus signed
SLSA provenance and CycloneDX attestations. The production Kubernetes baseline
rejects tags, other registries, wrong signing identities, unsigned digests, and
digests missing either attestation. Configure and test the registry/admission
rollout using
[`docs/production-image-security.md`](docs/production-image-security.md).

Sensitive user operations emit structured `SECURITY_AUDIT` events for device
claiming, token rotation, calibration, alert-rule replacement and acknowledgement.
Authentication and rate-limit rejections are audited too. Firebase user
provisioning emits a distinct `PROVISION_USER` result without Firebase UID or
email. Security audit and capacity payloads are versioned JSON messages suitable
for strict collector parsing. Events contain only fixed
action/result/target values, internal UUIDs when authenticated, resource UUIDs and
the validated request ID; raw tokens, claim codes, Firebase UID, email and request
bodies are excluded. Route these events to access-controlled, append-only log
storage with an environment-appropriate retention policy.
The enforced provider-independent policy, alert matrix, delivery-failure control,
and production acceptance procedure are documented in
[`docs/security-observability.md`](docs/security-observability.md).
All security work that requires human access to GitHub, Firebase, PostgreSQL,
ingress, GHCR, Kubernetes or the observability backend is consolidated in the
Russian-language [`docs/security-operations-runbook.md`](docs/security-operations-runbook.md).

Measurements are distributed over 16 fixed PostgreSQL hash partitions by
`device_id`; `(device_id, sequence)` remains the idempotency boundary. The runtime
retention worker deletes rows older than `TELEMETRY_RETENTION_DAYS` by
`received_at` using bounded `SKIP LOCKED` batches. It always preserves the row
referenced by `device_latest_state`, so an offline device retains one latest sample.
Capacity metrics aggregate all leaf partitions.

The reference Kubernetes production policy is in
[`deploy/kubernetes`](deploy/kubernetes/README.md). It runs the digest-pinned
server as UID/GID 10001 with a read-only root filesystem, no privilege escalation,
no Linux capabilities, RuntimeDefault seccomp, bounded CPU/RAM/ephemeral storage,
a size-limited memory-backed `/tmp`, disabled service-account token mounting,
read-only projected credentials, restricted Pod Security, and default-deny
networking. Standard Kubernetes PID limits are node-level, so the accompanying
KubeletConfiguration pins `podPidsLimit=256`. Adapt and verify this reference in
staging; the example image and selectors are intentionally not production values.

Firebase Admin uses Application Default Credentials. In Google-hosted environments, use workload identity. For local JVM execution, set `GOOGLE_APPLICATION_CREDENTIALS` to a service-account JSON stored outside this repository. Startup fails before opening the HTTP port if the project ID or ADC is unavailable. `FIREBASE_CHECK_REVOKED_TOKENS` defaults to `false` only for development and is mandatory in production; enabling it adds the Firebase remote check for token revocation and disabled users.

The project has no pre-existing users, so `FIREBASE_AUTO_PROVISION_USERS` defaults to `true`. The first valid Firebase token atomically creates a passwordless internal `users` row and its identity; tokens without an email are supported. Set the flag to `false` to refuse unknown Firebase UIDs without creating an account.

The `firebase-user` authentication provider verifies Bearer ID tokens, resolves them to internal user UUIDs, and returns neutral `401` responses for authentication failures. All protected user routes use this provider. The `device-token` provider and ESP32 authentication remain separate and unchanged. Optional controls, with defaults, are documented in `.env.example`: Firebase revocation checking and user auto-provisioning; telemetry time/temperature/ADC limits and upload interval; history range, point, online, and SSE heartbeat/lifetime limits; and alert/outbox polling, batching, and retry limits.

An SSE telemetry stream closes no later than the Firebase ID Token expiry or the
configured five-minute maximum lifetime, whichever comes first. Ownership is
rechecked every 30 seconds by default; archiving or transferring the plant closes
the stream. Clients must reconnect with a freshly obtained ID Token. Existing
per-user and per-peer connection caps remain enforced for every connection.

## Local startup

With workload identity/Application Default Credentials available to the container,
start PostgreSQL and the server as usual. For a local service-account file, set
`FIREBASE_CREDENTIALS_FILE` to its absolute host path and include the read-only
credentials override:

```bash
docker compose -f compose.yaml -f compose.firebase.yaml up --build
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
```

Compose waits for PostgreSQL's `pg_isready` health check rather than using a fixed sleep. Stop the stack with `docker compose down`; add `--volumes` to delete local database data.

To run against a PostgreSQL instance outside Compose, export the variables from `.env.example`, change `DATABASE_URL` to use `localhost`, and run:

```bash
./gradlew :server:run
```

## Database migrations

Flyway migrations live in `server/src/main/resources/db/migration`. Development
startup automatically applies pending migrations before creating the runtime pool;
when `MIGRATION_DATABASE_*` is absent it falls back to local `DATABASE_*` values.
Production startup never applies migrations. Run the dedicated deployment job
before starting or updating production server instances:

```bash
MIGRATION_DATABASE_URL='jdbc:postgresql://db.example.com:5432/jaiqal?sslmode=verify-full&channelBinding=require' \
MIGRATION_DATABASE_USER='jaiqal_migrator' \
MIGRATION_DATABASE_PASSWORD='from-secret-store' \
./gradlew :server:migrateDatabase
```

The migration entry point requires verified TLS. Give its role schema DDL and
Flyway-history rights, but do not inject that secret into the production server.
The runtime role must not own schema objects or have `CREATE` on the application
schema; grant only the DML and sequence privileges needed by the repositories.
Production startup queries PostgreSQL catalogs and fails before opening HTTP if
the runtime role is elevated, can create in the schema, or owns/inherits ownership
of application objects.
See [the production deployment guide](docs/production-deployment.md) for rollout
and privilege checks.

The schema covers users and external identities, plants, devices, one-time device claim codes, partitioned measurements and latest state, alert rules/events/processing state, and the reliable notification outbox. The legacy refresh-token table remains physically present but is no longer used by the application. Firebase identities map `(provider, Firebase UID)` to the internal user UUID; existing foreign-key structure is preserved.

## API quick start

An IntelliJ HTTP Client collection with health, authentication, plant, claiming, telemetry, history, and alert examples is available at [`api.http`](api.http).

### Firebase-authenticated request

```bash
curl http://localhost:8080/api/v1/plants \
  -H 'Authorization: Bearer paste-firebase-id-token'
```

The shared client obtains the ID Token from Firebase Authentication for every protected request. After one `401` it force-refreshes the token and retries exactly once; concurrent refreshes are serialized. The token is never persisted or logged by the application. The backend verifies it, maps the Firebase UID to an internal UUID, and uses that UUID for ownership checks. `/api/v1/auth/register`, `/login`, `/refresh`, and `/logout` return `410 Gone` with `LEGACY_AUTH_DISABLED`; they never issue or process application-owned tokens.

`GET /api/v1/auth/me` returns the authenticated internal user's UUID, email, and email-verification status. It does not expose the Firebase UID, ID Token, auth claims, or server credentials. Health endpoints continue to return only service/database readiness state.

`DELETE /api/v1/auth/me` atomically tombstones the SHA-256 hash of the Firebase UID and removes the internal user together with owned plants, devices, measurements, latest state, alerts, outbox rows, and related account data. The operation is idempotent and a tombstoned Firebase identity cannot be automatically provisioned again. The endpoint returns the standard JSON contract and all failures continue through the shared `ApiErrorResponse`/`StatusPages` handling.

### Manual Firebase setup

1. Create or select a Firebase project.
2. Enable the required sign-in methods in Firebase Authentication.
3. Create credentials for the server environment when workload identity is unavailable.
4. Configure Application Default Credentials or `GOOGLE_APPLICATION_CREDENTIALS`.
5. Set `FIREBASE_PROJECT_ID` to the selected project.
6. Restart or redeploy the server.
7. Obtain a test Firebase ID Token outside the backend through a client or Firebase tooling.
8. Call `GET /api/v1/auth/me` with that token and verify the internal user UUID.

The backend only verifies Firebase ID Tokens. It must not accept an email/password
to sign users into Firebase; that flow belongs in the client. See
[`docs/production-deployment.md`](docs/production-deployment.md) for credential,
rollout, rollback, and production hardening guidance.

### Device telemetry

```bash
curl -X POST http://localhost:8080/api/device/v1/measurements \
  -H 'Authorization: Device replace-with-provisioned-token' \
  -H 'Content-Type: application/json' \
  -d '{"sequence":42,"firmwareVersion":"1.0.0","soilMoistureRaw":1530,"airTemperatureCelsius":23.5,"airHumidityPercent":51.0,"lightRaw":840}'
```

The device is identified only by the token. Sequences are idempotent per device; batches accept 1–100 measurements. Invalid or absent device timestamps fall back to receipt time with diagnostic metadata. Soil percentage remains null until dry/wet calibration exists.

## Device pairing flow

1. Create a private operator-owned directory, choose a new absolute output path,
   export the database environment, and explicitly confirm the operation:
   ```bash
   PROVISIONING_CONFIRM=I_UNDERSTAND_DEVICE_SECRETS \
   DEVICE_CREDENTIALS_FILE=/absolute/private/path/living-room.credentials \
   DEVICE_NAME='Living room' \
   ./gradlew :server:provisionDevice
   ```

   `DEVICE_CREDENTIALS_FILE` must end with `.credentials`, its parent directory
   must already exist, and its canonical path must resolve outside the project
   checkout. The provisioning task follows symlink ancestors before applying this
   check and fails closed when the repository root is unavailable. Keep the
   generated file in an operator-only secret directory; if it ever appears inside
   a checkout or build context, rotate the device token before using the device.
   The task refuses CI, symbolic-link parent directories, relative paths and
   existing files. It writes the device ID, token and 24-hour claim code only to a
   newly created `0600` file; stdout contains no credentials.
   On a database error the file is retained for operator reconciliation rather
   than risking loss of credentials after an ambiguous commit.
2. Securely transfer the raw device token from that file to the ESP32 and then
   remove the operator copy according to the secret-handling policy. Only its canonical
   lowercase SHA-256 hash is stored; authentication performs an indexed lookup on
   the unique 64-character hash and never scans or exposes raw tokens.
3. An authenticated user creates a plant and submits the claim code plus owned plant ID to `POST /api/v1/devices/claim`.
4. The claim code is consumed once and the device is attached to that plant.
5. The device uploads using `Authorization: Device <token>`. Token rotation immediately invalidates the previous token.

## Main endpoints

- Health: `GET /health/live`, `GET /health/ready`
- Current user: `GET /api/v1/auth/me` with a Firebase ID Token
- Account deletion: `DELETE /api/v1/auth/me` with a Firebase ID Token
- Disabled legacy authentication: `POST /api/v1/auth/{register,login,refresh,logout}` returns `410 Gone`
- Plants: CRUD under `/api/v1/plants`
- Devices: claim, list, update, calibrate, rotate token, and restore temporary quarantine under `/api/v1/devices`
- Telemetry ingestion: `POST /api/device/v1/measurements` and `/batch`
- Reads: `/api/v1/plants/{plantId}/{latest,history,stream}`; history intervals are `raw`, `5m`, `1h`, and `1d`, and stream is SSE
- Alerts: rules, history, and acknowledgement under `/api/v1/plants/{plantId}`

All user-owned resources are scoped to the internal UUID from `UserPrincipal`. Cross-user lookups return 404 where possible to avoid disclosing resource existence.

Every `/api/v1/**` and `/api/device/**` response, including errors and one-time
credential responses, carries `Cache-Control: no-store`, `Pragma: no-cache`,
`X-Content-Type-Options: nosniff`, and `Referrer-Policy: no-referrer`. Health
responses are not forced to use the sensitive API cache policy.

Write endpoints validate request boundaries before persistence. Plant and device
names and plant species are limited to 255 characters; image URLs are limited to
2048 characters and must use HTTPS without embedded credentials. Provisioned
claim codes are exactly 32 lowercase hexadecimal characters, firmware versions
are limited to 100 characters, and alert replacement accepts at most one rule for
each of the four public alert types. Stored text fields reject control characters.
Invalid values return the established `400 ApiErrorResponse` codes such as
`INVALID_SPECIES`, `INVALID_IMAGE_URL`, `INVALID_CLAIM_CODE`,
`INVALID_FIRMWARE_VERSION`, or `INVALID_ALERT_RULE_COUNT`.

## Tests and verification

```bash
./gradlew :core:api-contract:allTests
./gradlew :server:test
./gradlew :server:build
```

The suite combines pure unit tests, Ktor route tests, and PostgreSQL Testcontainers integration tests. It covers Firebase verification and identity provisioning, concurrent first login, ownership boundaries, device authentication, telemetry validation/idempotency/calibration, latest-state ordering and history aggregation, alert duration/deduplication/recovery, migrations, and outbox retry safety. Firebase tests use a fake verifier and never call the real service.

`AbuseLoadTest` sends concurrent invalid-token, readiness, oversized-body and
telemetry bursts through the Ktor test host, and stresses the SSE limiter with
128 simultaneous acquisition attempts. It asserts that rejected work does not
reach Firebase verification, database readiness, device authentication or
persistence. The PostgreSQL integration suite also verifies that a readiness
burst returns every borrowed connection to the bounded Hikari pool.

### Supply-chain verification

Gradle dependency verification runs in strict mode automatically from
`gradle/verification-metadata.xml`. The committed baseline contains SHA-256 for
external artifacts, metadata and plugins used by the complete multi-module build;
an unexpected or modified artifact fails resolution. When intentionally upgrading
dependencies, regenerate the baseline with
`./gradlew --write-verification-metadata sha256`, review both coordinates and
checksums in the diff, and accept it only after the security scans pass.

CI references third-party Actions by full commit SHA and builds the server from
digest-pinned Temurin images. Its `supply-chain` job scans the resolved server
runtime and final image for all fixed or unfixed High/Critical vulnerabilities,
failing the workflow on a finding. It also uploads a CycloneDX SBOM for the exact
commit for 30 days. Dependabot checks Gradle, GitHub Actions, Dockerfile and Docker
Compose inputs weekly; pinned tags remain next to SHA/digests so update PRs stay
reviewable. Do not merge an automated update merely because it was generated by
Dependabot—review the upstream release, immutable reference and resulting SBOM.

## Client applications

The shared client architecture, local/production backend configuration, and physical-device overrides are documented in [`docs/frontend.md`](docs/frontend.md).

- Manual Firebase/Apple/CI actions: [`docs/firebase-frontend-checklist.md`](docs/firebase-frontend-checklist.md)
- Frontend architecture decisions: [`docs/architecture-decisions.md`](docs/architecture-decisions.md)

The Android/iOS client uses an account-scoped SQLDelight offline cache. Reads follow documented cache-first or network-first policies, while mutations remain server-first and are never queued as false offline server state. Firebase and device credentials are not stored in this database.

The shared client includes cache-first plant list/details screens and server-first create/edit forms backed by the existing `/api/v1/plants`, device, telemetry, and alert contracts. Details show only measurements and alert states supplied by the backend; the client does not invent plant-health diagnoses.

Plant details provide server-aggregated 24-hour, 7-day, and 30-day charts and authenticated foreground-only SSE updates. Realtime events refresh the shared SQLDelight cache; reconnect is bounded with exponential backoff and stops on background or logout.

Device claiming uses only the authenticated user endpoint and a manually entered one-time claim code. The client never receives, displays, stores, or uses an ESP32 Device Token. If a claim response is lost, retry first reconciles the authoritative device list before resubmitting the code. Device details expose firmware, last-seen, online, and calibration state. The five-step soil calibration wizard captures the backend's latest raw measurement for dry and wet conditions, accepts either ADC direction, rejects equal values, and sends values only after explicit confirmation.

The alerts tab reads active and recovered events from the account-scoped offline cache and refreshes every owned plant from the backend. Acknowledgement and rule replacement are server-first and never fabricate an offline success. Rule drafts validate the same threshold and duration ranges as the backend and remain editable after rejection; reset restores the last server-backed values. The current alert-event DTO does not expose the measured value or historical threshold, so the client states that limitation instead of presenting the current rule as historical event data.

Settings persist non-secret language (`system`, `kk`, `ru`, or `en`) and theme (`system`, `light`, or `dark`) preferences in SQLDelight. The screen also exposes account and email-verification state, resend verification, logout, permanent account deletion, app version, and an optional privacy-policy link; non-secret diagnostics are debug-only. Deletion requires an explicit destructive confirmation and recent authentication with the current password, Google, or Apple method. The client persists a recovery marker, deletes server data first, clears the account-scoped cache, and then deletes the Firebase user. A retry, including after an app restart, safely repeats the idempotent server request and resumes cleanup. On iOS, Apple reauthentication obtains a fresh authorization code and revokes the Apple token before deletion. Russian is the default resource locale, and complete Kazakh and English resource sets cover all client screens. Configure the Android privacy URL with `-PJAIQAL_PRIVACY_POLICY_URL=https://example.com/privacy` and the iOS URL with `PRIVACY_POLICY_URL` in `app/iosApp/Configuration/Config.xcconfig`; an empty value produces a localized placeholder.

Crashlytics is integrated for Android and iOS with collection disabled in debug and release mapping/dSYM upload configured. Common non-fatal reporting accepts only deduplicated, non-personal issue codes; credentials, tokens, email addresses, and user IDs are never passed to it. Firebase Messaging is intentionally not linked because the backend has no user push-token registration endpoint. The common `PushTokenRegistrar` boundary and the required backend contract are documented in [`docs/frontend.md`](docs/frontend.md); FCM/APNs permission, token, rotation, logout deactivation, and notification deep links must wait for that API.

- Android: `./gradlew :app:androidApp:assembleDebug`
- Desktop: `./gradlew :app:desktopApp:run`
- Web: `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
- iOS: open `app/iosApp` in Xcode

## Known limitations

- Notification delivery uses the logging sender, and the backend has no user push-token registration endpoint; production FCM/APNs delivery remains blocked on that server contract.
- The in-process measurement event bus and SSE subscriptions are node-local; the server is currently designed as one modular-monolith instance.
- Offline-device alert evaluation is polling-based, and telemetry aggregation uses standard PostgreSQL rather than TimescaleDB.
- Device provisioning is an operator Gradle command; there is no administrator UI.
- Firmware, MQTT, image uploads, species recognition, and AI recommendations are outside the current scope.
