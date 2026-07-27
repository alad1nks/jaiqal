# Production deployment

## Required runtime configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | JDBC URL of the production PostgreSQL database. |
| `DATABASE_USER` | PostgreSQL user with access to the application schema. |
| `DATABASE_PASSWORD` | PostgreSQL password supplied by the deployment secret store. |
| `FIREBASE_PROJECT_ID` | Project whose Firebase ID Tokens the backend accepts. |
| `GOOGLE_APPLICATION_CREDENTIALS` | Optional path to an externally mounted service-account JSON when workload identity is unavailable. |
| `FIREBASE_AUTO_PROVISION_USERS` | Creates a passwordless internal user on the first valid Firebase login; defaults to `true`. |
| `FIREBASE_CHECK_REVOKED_TOKENS` | Enables the Firebase remote revocation/disabled-user check; defaults to `false`. |

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
`FIREBASE_CHECK_REVOKED_TOKENS` improves immediate revocation handling but adds a
Firebase network check to authentication requests.

## Database and deployment order

Back up PostgreSQL before rollout. Start one new server instance and allow Flyway
to apply pending migrations before shifting traffic to the new version. Migration
`V4__firebase_user_identities.sql` preserves `users.id UUID` and all existing
foreign keys while adding the Firebase identity mapping. Do not rewrite applied
migrations or remove `password_hash`/`refresh_tokens` as part of this rollout.

The server must run behind HTTPS. Restrict `ALLOWED_ORIGINS` to deployed client
origins, keep database and Firebase credentials in the platform secret manager,
and expose only the HTTP application port. Health responses intentionally contain
no Firebase configuration.

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

## Verification and rollback

Before shifting traffic, confirm that valid Firebase tokens can call `/auth/me`,
plant/device ownership remains isolated, and ESP32 ingestion still accepts only
`Authorization: Device <token>`. A rollback may deploy the previous application
version because V4 is additive. Do not automatically delete users, identities,
password hashes, refresh-token rows, or production volumes during rollback.
