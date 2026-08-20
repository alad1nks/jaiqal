# Production deployment

## Required runtime configuration

| Variable | Purpose |
| --- | --- |
| `APP_ENVIRONMENT` | Must be `production`; enables fail-closed security validation and HTTPS enforcement. |
| `DEPLOYMENT_COMMIT_SHA` | Full lowercase 40-character Git commit used to build the deployed image; required in production and exposed only through the `X-Deployment-Commit` liveness header for rollout/DAST binding. |
| `PUBLIC_API_URL` | Public API origin; must be `https://` without credentials, path, query, or fragment. |
| `TRUSTED_PROXY_TERMINATES_TLS` | Must be `true`; declares that the isolated ingress terminates verified client TLS and overwrites `X-Forwarded-Proto`. |
| `TRUSTED_PROXY_CIDRS` | Required comma-separated literal IPv4/IPv6 CIDRs of direct ingress peers. Hostnames, non-canonical networks and `/0` are rejected. |
| `DATABASE_URL` | JDBC URL used by the runtime server; must enable verified TLS. |
| `DATABASE_USER` | DML-only runtime role; must not own schema objects or have DDL privileges. |
| `DATABASE_PASSWORD` | Runtime-role password supplied only to the server workload; omit when using `DATABASE_PASSWORD_FILE`. |
| `DATABASE_PASSWORD_FILE` | Preferred absolute path to a bounded read-only UTF-8 password file. Setting both password sources is rejected. |
| `FIREBASE_PROJECT_ID` | Project whose Firebase ID Tokens the backend accepts. |
| `GOOGLE_APPLICATION_CREDENTIALS` | Optional path to an externally mounted service-account JSON when workload identity is unavailable. |
| `FIREBASE_AUTO_PROVISION_USERS` | Creates a passwordless internal user on the first valid Firebase login; defaults to `true`. |
| `FIREBASE_CHECK_REVOKED_TOKENS` | Must be `true` in production; enables the Firebase remote revocation/disabled-user check. |
| `HTTP_MAX_BODY_BYTES` | General request-body limit; defaults to 65536 bytes. |
| `TELEMETRY_BATCH_MAX_BODY_BYTES` | Telemetry batch body limit; defaults to 131072 bytes. |
| `RATE_LIMIT_PERIOD_SECONDS` | Refill period shared by application request limiters; defaults to 60 seconds. |
| `READINESS_RATE_LIMIT_REQUESTS` | Readiness requests per direct peer and period; defaults to 30. |
| `USER_API_RATE_LIMIT_REQUESTS` | User API requests per direct peer and period; defaults to 120. |
| `TELEMETRY_RATE_LIMIT_REQUESTS` | Device telemetry requests per direct peer and period; defaults to 120. |
| `SSE_MAX_CONNECTIONS_PER_USER` | Concurrent SSE connections per user and instance; defaults to 3. |
| `SSE_MAX_CONNECTIONS_PER_IP` | Concurrent SSE connections per direct peer and instance; defaults to 10. |
| `SSE_MAX_LIFETIME_SECONDS` | Hard stream lifetime, additionally bounded by Firebase token expiry; defaults to 300 and cannot exceed 3600 seconds. |
| `SSE_OWNERSHIP_RECHECK_SECONDS` | Plant ownership recheck interval; defaults to 30 and cannot exceed the maximum stream lifetime. |
| `TELEMETRY_DEVICE_QUOTA_PERIOD_SECONDS` | Atomic PostgreSQL per-device quota window; defaults to 86400 seconds. |
| `TELEMETRY_DEVICE_QUOTA_MAX_MEASUREMENTS` | Measurement items per device and window; defaults to 1440 and cannot be below the maximum batch size of 100. |
| `TELEMETRY_ANOMALY_BREACH_WINDOWS` | Distinct exhausted quota windows required for quarantine; defaults to 3 and cannot be lower. |
| `TELEMETRY_ANOMALY_WINDOW_SECONDS` | Observation horizon for breached windows; defaults to 604800 and must cover the required quota windows. |
| `TELEMETRY_QUARANTINE_SECONDS` | Temporary quarantine duration; defaults to 3600 and is bounded from 300 to 604800 seconds. |
| `TELEMETRY_RETENTION_DAYS` | Receipt-time retention window; defaults to 365 days and must cover `HISTORY_MAX_RANGE_SECONDS`. |
| `TELEMETRY_RETENTION_INTERVAL_SECONDS` | Delay between retention cycles; defaults to 3600 seconds. |
| `TELEMETRY_RETENTION_BATCH_SIZE` | Rows per short delete transaction; defaults to 5000. |
| `TELEMETRY_RETENTION_MAX_BATCHES_PER_RUN` | Maximum delete transactions per cycle; defaults to 20. |
| `CAPACITY_MONITOR_INTERVAL_SECONDS` | PostgreSQL capacity-check interval; defaults to 300 seconds. |
| `CAPACITY_MEASUREMENTS_WARN_ROWS` | Estimated measurements-row warning threshold; defaults to 10000000. |
| `CAPACITY_MEASUREMENTS_WARN_BYTES` | Measurements relation warning threshold; defaults to 5 GiB. |
| `CAPACITY_DATABASE_WARN_BYTES` | Whole-database warning threshold; defaults to 10 GiB. |

Use workload identity/Application Default Credentials when the platform supports it.
Otherwise inject a service-account JSON from the platform secret manager as a
read-only file and set `GOOGLE_APPLICATION_CREDENTIALS` to its container path. Do
not copy credentials into the image, repository, CI output, or environment
templates.

## Firebase Console and rollout checklist

1. Create or select the Firebase project used by this environment.
2. Enable the required sign-in providers in Firebase Authentication.
3. Grant the server workload permission to verify Firebase Authentication users,
   or create server credentials when workload identity is unavailable.
4. Configure Application Default Credentials. For a mounted JSON credential, set
   `GOOGLE_APPLICATION_CREDENTIALS` to the mounted file inside the server runtime.
5. Set `FIREBASE_PROJECT_ID` to the same Firebase project.
6. Deploy or restart the server and verify `/health/live` and `/health/ready`.
7. Obtain a test Firebase ID Token through a client or Firebase tooling outside
   the backend. Never add an email/password Firebase login endpoint to the server.
8. Call `GET /api/v1/auth/me` with `Authorization: Bearer <ID Token>` and verify
   that the response contains the expected internal UUID.

Keep `FIREBASE_AUTO_PROVISION_USERS=true` for the current empty-user rollout. Set
it to `false` only when unknown Firebase UIDs must be refused. Enabling
`FIREBASE_CHECK_REVOKED_TOKENS` adds a Firebase network check to authentication
requests; production startup refuses to run without it.

## Database and deployment order

Back up PostgreSQL before rollout. Production server instances never run Flyway
and fail startup if any `MIGRATION_DATABASE_*` variable is exposed to them. A
separate, one-shot deployment job must receive only these secrets:

| Migration-job variable | Purpose |
| --- | --- |
| `MIGRATION_DATABASE_URL` | JDBC URL with `sslmode=verify-full&channelBinding=require`. |
| `MIGRATION_DATABASE_USER` | Dedicated schema owner/Flyway role, distinct from `DATABASE_USER`. |
| `MIGRATION_DATABASE_PASSWORD` | Migration secret, unavailable to the runtime workload; omit when using the file variant. |
| `MIGRATION_DATABASE_PASSWORD_FILE` | Absolute path to a bounded read-only migration-password file. |

Run the job and wait for success before starting new server instances:

```bash
./gradlew :server:migrateDatabase
```

The job rejects a migration username equal to `DATABASE_USER` when both variables
are present and refuses a URL without verified TLS. Migration
`V4__firebase_user_identities.sql` preserves `users.id UUID` and all existing
foreign keys while adding the Firebase identity mapping. Migration
`V6__index_device_token_hash.sql` validates existing device hashes, normalizes
uppercase hex, narrows the column to 64 characters, and creates a unique lookup
index. It intentionally fails if legacy hashes are malformed or collide after
normalization; inspect and repair such rows through an approved credential-rotation
procedure before retrying. Do not rewrite applied migrations or remove
`password_hash`/`refresh_tokens` as part of this rollout.

Migration `V7__partition_measurements.sql` takes `ACCESS EXCLUSIVE` locks on
`measurements` and `device_latest_state`, copies the complete measurements table,
and rebuilds it as 16 hash partitions. Treat it as a maintenance migration:

1. stop or drain telemetry writes and all server instances;
2. verify a current backup and enough free space for a second copy plus indexes;
3. record the current row count and latest sequence per device sample;
4. run the dedicated migration job and wait for the copy/index/FK validation;
5. verify 16 leaf partitions, row count, duplicate-sequence rejection, latest and
   history reads, then start the new server version;
6. monitor disk, WAL, replication lag, locks, retention logs, and capacity alerts.

Do not cancel the migration merely because the copy is slow. PostgreSQL/Flyway
rolls the transactional DDL back on failure, but disk/WAL headroom and backup are
still mandatory. Schedule a larger window for large existing datasets.

Before applying `V8__notification_error_codes.sql`, stop or drain notification
workers from the previous server version. V8 replaces historical free-form
`notification_outbox.last_error` values with `DELIVERY_FAILED` and adds an
allowlist constraint; an old worker would still attempt to write arbitrary
exception messages and its retry transaction would be rejected. After the
migration succeeds, start only the updated server version. Pending outbox rows
remain pending and are retried normally.

For a new database, create objects with the migration role and grant the runtime
role only `CONNECT`, schema `USAGE`, required table `SELECT/INSERT/UPDATE/DELETE`,
and sequence `USAGE/SELECT/UPDATE`. Revoke schema `CREATE` from the runtime role.
Configure `ALTER DEFAULT PRIVILEGES FOR ROLE <migration-role>` so future
application tables and sequences receive the same runtime grants. Do not grant
runtime writes to `flyway_schema_history`.

For an existing database where the former application role owns objects, a DBA
must transfer application object ownership to the migration role before revoking
DDL. Perform this as a reviewed maintenance operation after backup; then verify
from a runtime connection:

```sql
SELECT current_user,
       has_schema_privilege(current_user, current_schema(), 'CREATE') AS can_create;
```

`can_create` must be `false`. Also verify that the runtime role cannot alter an
application table, while normal API read/write smoke tests still pass. Keep role
creation, ownership transfer, and grants in infrastructure code outside this
repository because role names and database ownership are deployment-specific.
The server repeats the catalog checks across the active application schema search
path at production startup and refuses elevated roles, schema `CREATE`, and
ownership or inherited ownership of application tables, sequences, views, or
materialized views.

Before startup, set at minimum:

```dotenv
APP_ENVIRONMENT=production
DEPLOYMENT_COMMIT_SHA=0123456789abcdef0123456789abcdef01234567
PUBLIC_API_URL=https://api.example.com
TRUSTED_PROXY_TERMINATES_TLS=true
TRUSTED_PROXY_CIDRS=10.42.0.0/16
FIREBASE_CHECK_REVOKED_TOKENS=true
DATABASE_URL=jdbc:postgresql://db.example.com:5432/jaiqal?sslmode=verify-full&channelBinding=require
ALLOWED_ORIGINS=https://app.example.com
```

Mount the PostgreSQL CA through the secret/configuration mechanism expected by
pgJDBC (`sslrootcert` or a dedicated JVM truststore). Startup validation requires
the URL properties; the actual first connection additionally fails if the CA or
database hostname cannot be verified.

The server must run behind the trusted HTTPS ingress. Restrict `ALLOWED_ORIGINS`
to deployed HTTPS origins, keep database and Firebase credentials in the platform
secret manager, and expose the Ktor HTTP port only to that ingress. The ingress
must strip client-supplied `X-Forwarded-Proto` and set exactly `https`; otherwise
the application returns `426 HTTPS_REQUIRED`. The application accepts that header
only when the socket peer belongs to `TRUSTED_PROXY_CIDRS`; it performs literal
address matching without DNS and never uses `X-Forwarded-For` as authority. Set
the narrowest ranges that cover the addresses the server actually observes after
the platform's ingress/SNAT path, and verify trusted and untrusted peers in staging.
Production responses include
`Strict-Transport-Security: max-age=31536000; includeSubDomains`. Health responses
intentionally contain no Firebase configuration.

## Edge request controls

Do not expose the Ktor port directly in production. The trusted HTTPS ingress or
API gateway must enforce body limits no weaker than `HTTP_MAX_BODY_BYTES` and
`TELEMETRY_BATCH_MAX_BODY_BYTES`, and per-client token buckets no weaker than the
readiness, user API, and telemetry limits above. It must also cap concurrent and
idle SSE connections. Strip untrusted forwarded headers and generate the client
address at the ingress; the application intentionally keys its secondary limiter
by the direct socket peer and does not accept `X-Forwarded-For` as authority.
Network policy/firewall rules must make spoofing the trusted HTTPS marker
impossible by preventing clients from connecting directly to Ktor.

## Security audit trail and device provisioning

Route JSON event type `SECURITY_AUDIT` to append-only, access-controlled storage and alert
on bursts of rejected `AUTHENTICATION` and `RATE_LIMIT` events. Sensitive actions
(`CLAIM_DEVICE`, `ROTATE_DEVICE_TOKEN`, `UPDATE_DEVICE_CALIBRATION`,
`UPDATE_ALERT_RULES`, and `ACKNOWLEDGE_ALERT`) record `SUCCESS`, `REJECTED`, or
`FAILURE`, a fixed target class, the authenticated internal user UUID, resource
UUID when known, and the validated request ID. The schema deliberately excludes
raw device tokens, claim codes, Authorization values, request bodies, Firebase
UIDs, email addresses and client-controlled free text. Restrict audit-log readers,
define retention/incident-export procedures in the logging platform, and correlate
events with normal request logs only through `requestId`.

User identity creation emits `PROVISION_USER`: success contains only the new
internal UUID, while rejected/failed attempts contain no Firebase UID, email or
credential material. Route `SECURITY_CAPACITY_ALERT` through the same protected
pipeline. Apply the exact storage, alert/deduplication and delivery-failure contract
from [`security-observability.md`](security-observability.md); the production gate
requires provider-side evidence and is not satisfied by repository policy alone.

Device provisioning is an operator-only maintenance action, not a runtime or CI
job. Run it from a controlled workstation with database access and a private
non-symlink directory. Set `PROVISIONING_CONFIRM=I_UNDERSTAND_DEVICE_SECRETS` and
an absolute, non-existing `DEVICE_CREDENTIALS_FILE`; the task creates that file
with `0600`, never overwrites it, and prints no token or claim code. Transfer the
token to the device and claim code to the intended owner through approved secret
channels, then remove the operator copy under the environment's credential
handling policy. Never collect the file as a CI artifact or application log.
If database provisioning reports failure, the protected file is retained because
the commit outcome may be ambiguous; reconcile its device ID against PostgreSQL
before either retrying or removing the file.

The application rejects invalid DTO boundaries before persistence: externally
stored image URLs must be credential-free HTTPS URLs, claim codes must match the
provisioned 32-character lowercase hex format, firmware versions are capped at the
database width, and alert rule replacement is capped at the four public types.
These limits are fixed security controls rather than deployment tuning knobs.

For multiple server replicas, edge limits are the cluster-wide control. Ktor's
in-memory buckets and SSE counters are per process and serve only as a fallback
bulkhead. Alert on sustained `413`/`429` rates and verify that the gateway keeps
`Retry-After` and `X-RateLimit-*` response headers.

Every telemetry SSE connection closes at the earlier of Firebase ID Token expiry
and `SSE_MAX_LIFETIME_SECONDS`. The server also rechecks plant ownership at
`SSE_OWNERSHIP_RECHECK_SECONDS` and closes the stream if ownership is lost. Clients
must obtain a fresh ID Token and reconnect; the new handshake repeats Firebase
revocation/disabled-user verification. Keep ingress idle timeouts slightly above
the configured heartbeat, but never use the ingress to extend the application
deadline.

## Staging DAST promotion gate

Before production promotion, run `.github/workflows/staging-dast.yml` against the
commit deployed behind the real staging TLS ingress. The deployment pipeline must
pass its immutable full SHA and wait for the `dast` job; a header mismatch fails
before authenticated tests. Manual and weekly scheduled entry points use the same
scanner. Configure only dedicated least-privileged staging identities, plants and
device, and never expose their refresh/device tokens to production or workflow
artifacts. The complete setup and check inventory are in
[`staging-dast.md`](staging-dast.md).

## Telemetry quota, retention, partitioning, and capacity alerts

Migration `V5__device_ingestion_quotas.sql` creates one quota row per device. The
server locks that row while consuming the fixed-window quota, so the limit remains
atomic across replicas. A batch consumes one unit per validated measurement; a
request rejected with `429 RATE_LIMITED` writes no measurements and includes the
remaining window in `Retry-After`. Quota consumption is fail-closed: a later
persistence failure can consume allowance, but cannot bypass the storage guard.

Migration `V9__device_anomaly_quarantine.sql` adds bounded anomaly state to each
device. Only the first rejection in a distinct exhausted quota window increments
the counter, so request floods within one window cannot manufacture multiple
signals. At least three breached windows inside the observation horizon are
required. The resulting quarantine expires automatically, emits one
`QUARANTINE_DEVICE` audit event after commit, and returns `403 DEVICE_QUARANTINED`
with `Retry-After`. An authenticated owner may reset all anomaly state through
`POST /api/v1/devices/{deviceId}/restore`; the operation emits `RESTORE_DEVICE`,
is idempotent for an owned device, and preserves `404` for another user's device.
Alert on quarantine events and investigate token compromise before restoring.

Migration V7 distributes measurements across 16 fixed hash partitions by
`device_id`. The partition key remains part of both the composite primary key and
the `(device_id, sequence)` uniqueness constraint, preserving device idempotency.
Fixed hash partitions require no runtime DDL or future partition creation.

The runtime worker removes rows whose `received_at` is older than
`TELEMETRY_RETENTION_DAYS`. Every transaction locks at most
`TELEMETRY_RETENTION_BATCH_SIZE` candidates with `SKIP LOCKED`, and each cycle is
capped by `TELEMETRY_RETENTION_MAX_BATCHES_PER_RUN`. Multiple replicas may run the
worker safely. Rows referenced by `device_latest_state` are excluded, preserving
one last-known sample for an offline device. Retention is based on receipt time so
a legitimate delayed/offline upload is not immediately removed. Monitor
`telemetry_retention` logs for persistent backlog and keep autovacuum healthy.

The background capacity check emits a structured `SECURITY_CAPACITY_ALERT` warning
with `metric`, `observed`, and `threshold`; it never includes credentials, device
tokens, or user identifiers. Configure the production log/observability platform
to page the database owner when this event occurs. Alert separately on sustained
growth of `database_capacity` values, keep storage-provider free-space alerts
enabled, and set thresholds below the provider's hard disk limit. This monitor is
partition-aware and aggregates row estimates and relation sizes across all leaf
measurement partitions. It remains an early warning rather than a disk quota.

## Container credentials

The main `compose.yaml` contains no credentials. For local container verification
with a service-account file stored outside the repository, set
`FIREBASE_CREDENTIALS_FILE` to its absolute host path and use the read-only
override:

```bash
docker compose -f compose.yaml -f compose.firebase.yaml up --build
```

Production orchestrators should express the equivalent secret mount natively, or
use workload identity and omit both the mount and `GOOGLE_APPLICATION_CREDENTIALS`.

## Restricted Kubernetes runtime reference

`deploy/kubernetes/runtime-policy.yaml` provides a production baseline with
restricted Pod Security, non-root UID/GID 10001, `readOnlyRootFilesystem`,
`allowPrivilegeEscalation: false`, `privileged: false`, RuntimeDefault seccomp,
all capabilities dropped, no host namespaces, and no mounted service-account
token. CPU, memory, ephemeral-storage, and the memory-backed writable `/tmp` are
bounded. Database/Firebase/CA credentials are projected read-only; the database
password is read via `DATABASE_PASSWORD_FILE` rather than injected into env.

The manifest applies default-deny ingress and egress, then permits only the
labelled ingress controller, cluster DNS, labelled PostgreSQL pods, and a
controlled HTTPS CONNECT proxy. The proxy must restrict destinations to the
reviewed Firebase/Google APIs. Adapt namespace and pod selectors to the platform;
never broaden egress to all destinations merely to make a failed smoke test pass.

Kubernetes has no standard per-Pod PID resource field. Merge the pinned
`podPidsLimit: 256` from `deploy/kubernetes/kubelet-config.yaml` into every worker
node's complete KubeletConfiguration, or use the managed-provider equivalent.
The partial file is documentation/input for cluster configuration and must not
replace an existing kubelet configuration wholesale.

Before applying any overlay:

1. replace the zero example digest with the reviewed signed workflow output;
2. provision the referenced ConfigMap and Secret outside Git, ensuring the JDBC
   URL points `sslrootcert` at the projected CA;
3. run `bash scripts/verify-runtime-policy.sh`, render the final manifests, and
   run server-side dry-run plus admission and Trivy checks against that rendering;
4. verify the effective PID limit on every node and test read-only filesystem,
   resource exhaustion, probes, PostgreSQL TLS, Firebase egress, and denied
   lateral traffic in staging.

The CI verification job executes the structural runtime-policy guard, while the
existing Trivy misconfiguration gate scans the Kubernetes YAML. Actual admission,
node PID configuration, network enforcement, and secret-manager integration are
deployment-environment gates and cannot be proven from the repository alone.

## Verification and rollback

### Signed production image and admission

The repository now owns a dedicated production image workflow. It runs only for
a successful same-repository `CI` push to `main`, waits for approval in the
protected `production-signing` Environment, scans the published digest, then
creates an exact-workflow keyless signature and signed SLSA/CycloneDX
attestations. Deploy only the `ghcr.io/alad1nks/jaiqal-server@sha256:...` value
printed by a completely successful run. Setup, admission tests, trust rotation,
rollback and break-glass procedures are in
[`production-image-security.md`](production-image-security.md).

### Supply-chain release gate

Do not promote a server image unless `verification`, `sast`, and `supply-chain` CI
jobs passed for the exact commit. The SAST job runs CodeQL's extended Java/Kotlin
security suite over manually built server classes. The supply-chain job builds from
digest-pinned JDK and JRE images, verifies Gradle artifacts against the committed SHA-256 baseline,
blocks High/Critical findings in both the resolved JVM runtime and final image,
rejects new Medium runtime findings relative to the exact reviewed baseline, and
fails if the excluded Firebase/Google Cloud Storage runtime graph returns. It also
publishes `server-sbom-<commit>` as CycloneDX. Retain or copy that SBOM beside
the promoted image according to the release retention policy so incident response
can map a deployed digest back to components.

GitHub Actions are referenced by full commit SHA with their release tag in a
same-line comment. Enable the repository policy that requires full-length SHA
pinning. Dependabot proposes weekly Gradle, Actions, Dockerfile and Compose
updates, but every digest/checksum change still requires human review and a green
security scan. Update `gradle/verification-metadata.xml` only as part of the same
reviewed dependency PR; an unrelated checksum change is a release blocker.
Review additions to `scripts/trivy-medium-baseline.txt` by vulnerability ID,
package, installed version, reachability and available fix. Never add a wildcard;
an upgraded package version is intentionally a new tuple that requires a fresh
review. Remove stale entries after the scanner no longer reports them.

The earlier CI SBOM remains a review artifact. The release workflow regenerates
the CycloneDX SBOM from the published digest and attaches it as a signed in-toto
attestation, so admission binds the component inventory to the exact deployed
image rather than to a tag or a separate mutable file.

The supply-chain job also scans the clean checkout before any build step. Every
detected committed secret and every High/Critical repository configuration finding
blocks promotion. Treat a secret finding as potential credential exposure: remove
it from the repository, rotate it, and review Git history and CI logs instead of
merely adding an ignore entry. Any unavoidable configuration exception must be
limited to the exact finding and path, documented, approved, and time-bounded.

Before shifting traffic, confirm that valid Firebase tokens can call `/auth/me`,
revoked and disabled Firebase users receive the neutral `401`, plain HTTP receives
`426`, HTTPS responses contain HSTS, and the live PostgreSQL connection reports
verified TLS with the expected server certificate. Then confirm that
plant/device ownership remains isolated, and ESP32 ingestion still accepts only
`Authorization: Device <token>`. A rollback may deploy the previous application
version because V4 is additive. Do not automatically delete users, identities,
password hashes, refresh-token rows, or production volumes during rollback.
