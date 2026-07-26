# Firebase Authentication migration

## Previous design and migration scope

The original backend stored an Argon2 password hash in `users`, issued an HMAC
access JWT, and rotated opaque tokens stored as hashes in `refresh_tokens`.
`user-jwt` populated a JWT principal whose subject was the internal user UUID.
All plant, device, telemetry-read, and alert ownership checks used that UUID.

User authentication is now replaced by the `firebase-user` provider. It verifies
Firebase ID tokens with the Firebase Admin SDK and passes the existing internal
UUID to unchanged ownership-aware services. The ESP32 `device-token` provider,
claiming flow, telemetry payloads, transaction boundaries, and ownership-hiding
404 responses are unchanged.

The schema may contain existing users because the initial migrations and legacy
tables predate this change. Consequently, users are **never linked by email**.
Migration `V4__firebase_user_identities.sql` preserves all rows and adds an
explicit mapping:

```text
(provider = firebase, external_subject = Firebase UID) -> users.id UUID
```

Both `(provider, external_subject)` and `(user_id, provider)` are unique. A
concurrent first sign-in is handled transactionally: a losing insert rolls back
and reloads the winner. `FIREBASE_AUTO_PROVISION_USERS=false` rejects an unknown
UID. When explicitly enabled, the first verified token creates a `users` row and
identity atomically. Existing password users require an explicit administrative
mapping inserted into `user_identities`; matching email alone is insufficient.

`password_hash` and `email` become nullable for Firebase accounts. Existing
password hashes, refresh-token rows, and all old migrations remain for rollback
and later controlled cleanup. The old auth endpoints return `410 Gone` and no
longer issue application JWTs or refresh tokens.

## Runtime setup

1. Create a Firebase project and enable the desired providers in Authentication.
2. Configure Application Default Credentials for the server runtime, or create a
   service-account credential and set `GOOGLE_APPLICATION_CREDENTIALS` to its
   runtime path. Do not add the JSON file to this repository or image.
3. Set `FIREBASE_PROJECT_ID`.
4. Choose `FIREBASE_AUTO_PROVISION_USERS=true` only if automatic creation is
   intended. Keep it false while explicitly mapping pre-existing accounts.
5. Set `FIREBASE_CHECK_REVOKED_TOKENS=true` to check disabled users and revoked
   sessions (this performs the additional Admin SDK revocation lookup).
6. Restart the server. Startup fails clearly if the project ID or Application
   Default Credentials are unavailable.
7. Obtain a test Firebase ID token outside this backend and call:

   ```bash
   curl http://localhost:8080/api/v1/auth/me \
     -H "Authorization: Bearer $FIREBASE_ID_TOKEN"
   ```

The health endpoints reveal only liveness/database readiness and never Firebase
configuration. For Docker Compose, mount the credential into the server with a
local override or use workload identity; the repository intentionally does not
define or mount a secret file.

## Follow-up

After clients have migrated and production identities have been explicitly
audited, create a separate migration to remove legacy password and refresh-token
data. Do not perform that destructive cleanup as part of this migration.
