# AGENTS.md

Before changing code, read `README.md`, `task.md`, and the relevant implementation
and tests. Preserve unrelated work.

## Project-specific constraints

- Treat `app/shared` as the existing equivalent of `app:composeApp`; do not create
  or rename a duplicate module.
- Keep shared wire DTOs and public enums in `core/api-contract`; server and
  persistence types stay in `server`.
- Follow the existing manual constructor wiring in `server/.../Application.kt`.
  Repository interfaces stay in feature packages and JDBC/Exposed implementations
  stay under `server/.../infrastructure/database`.
- Add a new numbered Flyway migration for schema changes; never rewrite an applied
  migration.
- Preserve the established transaction boundaries: publish measurement events
  only after ingestion commits, and write alert transitions with their outbox row
  atomically.
- Preserve ownership-hiding behavior (`404` for another user's resources), derive
  device identity only from `Authorization: Device <token>`, and never log or
  persist raw credentials or tokens.
- Return established `ApiErrorResponse` bodies through `StatusPages`; do not add
  route-specific error formats.
- Update `.env.example`, `README.md`, and `api.http` when configuration or API
  behavior changes. Never commit `.env`.

## Verification

Run focused tests while developing, then run:

```bash
./gradlew :core:api-contract:allTests
./gradlew :server:test
./gradlew :server:build
```

`allTests` needs an Android SDK, and persistence tests need a Docker-compatible
runtime for Testcontainers. Report either missing dependency as an environment
limitation rather than hiding skipped or failed checks.
