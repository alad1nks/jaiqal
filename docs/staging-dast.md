# Staging DAST gate

`.github/workflows/staging-dast.yml` is a non-destructive security gate for the
deployed staging commit. It can run manually, every Monday, or as a reusable
workflow from the deployment pipeline. Promotion must wait for its `dast` job.

The scanner tests only dedicated fixtures. It does not create, update, archive,
claim, restore, rotate, acknowledge, or ingest any valid application data. The
only POST bodies are malformed JSON or a body rejected by the configured size
limit before application persistence.

## Staging fixture

Create two dedicated Firebase test users and two inert plants:

- user A owns `STAGING_DAST_USER_PLANT_ID`; the scanner uses this plant only for
  ownership sanity and a bounded SSE connection;
- user B owns `STAGING_DAST_OTHER_USER_PLANT_ID`; user B must receive `200`, while
  user A must receive ownership-hiding `404` for the same resource;
- a dedicated provisioned device supplies `STAGING_DAST_DEVICE_TOKEN`; the
  scanner sends only malformed JSON with it, so no measurement can be accepted.

Do not reuse production identities, devices, plants, Firebase projects, refresh
tokens, or device tokens. Give the users no administrative role and keep the
device detached from real automation. Rotate all four credentials when a runner,
environment, or operator is suspected compromised.

For a short bounded check, the staging server must set:

```dotenv
SSE_MAX_LIFETIME_SECONDS=15
SSE_OWNERSHIP_RECHECK_SECONDS=10
```

The scanner refuses an expected lifetime above 60 seconds. Production may keep
its separately reviewed value.

## GitHub Environment

Create a protected GitHub Environment named `staging-security`. Limit deployment
branch access and require an appropriate reviewer for promotion runs.

Environment variables:

| Name | Value |
| --- | --- |
| `STAGING_BASE_URL` | Credential-free HTTPS API origin behind the real staging ingress. |
| `STAGING_ALLOWED_ORIGIN` | Exact HTTPS frontend origin from staging `ALLOWED_ORIGINS`. |
| `STAGING_EXPECTED_COMMIT` | Full lowercase SHA currently deployed; used by scheduled runs. |
| `STAGING_DAST_USER_PLANT_ID` | Canonical UUID owned by test user A. |
| `STAGING_DAST_OTHER_USER_PLANT_ID` | Canonical UUID owned by test user B. |
| `STAGING_DAST_RATE_LIMIT_ATTEMPTS` | Optional, defaults to 80 and is capped at 200. |
| `STAGING_DAST_MAX_BODY_BYTES` | Staging general body limit, defaults to 65536. |
| `STAGING_DAST_SSE_MAX_LIFETIME_SECONDS` | Staging SSE lifetime, defaults to 15 and is capped at 60. |

Environment secrets:

| Name | Purpose |
| --- | --- |
| `STAGING_DAST_FIREBASE_WEB_API_KEY` | Firebase staging web API key used only at the official Secure Token endpoint. |
| `STAGING_DAST_USER_REFRESH_TOKEN` | Dedicated user A refresh token. |
| `STAGING_DAST_OTHER_USER_REFRESH_TOKEN` | Dedicated user B refresh token. |
| `STAGING_DAST_DEVICE_TOKEN` | Dedicated device credential. |

The bootstrap exchanges refresh tokens for short-lived ID tokens without placing
credentials in command arguments or output. Curl user configuration, responses,
SSE data, and oversized bodies use a `0700` temporary directory with `0600`
files and are deleted on every exit. The workflow uploads no artifacts and uses
read-only repository permissions.

## Deployment binding and checks

Set `DEPLOYMENT_COMMIT_SHA` to the exact commit used for the image build. Production
startup rejects a missing, abbreviated, uppercase, or malformed value. The
liveness route exposes it in `X-Deployment-Commit`; DAST rejects a mismatch before
running authenticated checks.

The blocking checks are:

- `DAST-TLS-001` / `DAST-COMMIT-001`: HTTPS, HSTS, and exact deployed commit;
- `DAST-PROXY-001`: client-supplied forwarded headers cannot break the trusted
  ingress path;
- `DAST-CORS-001/002`: exact allowlisted origin accepted and arbitrary origin
  denied without reflection;
- `DAST-AUTH-001/002`: neutral invalid authentication and valid dedicated user;
- `DAST-OWNERSHIP-001`: another user's resource remains a neutral `404`;
- `DAST-JSON-001` / `DAST-DEVICE-AUTH-001`: malformed authenticated user/device
  JSON is rejected before persistence;
- `DAST-BODY-001`: oversized payload returns `413 PAYLOAD_TOO_LARGE`;
- `DAST-RATE-001`: changing `X-Forwarded-For` cannot evade the bounded readiness
  limiter, and `429` includes `Retry-After`;
- `DAST-SSE-001`: the server, rather than the client timeout, closes the stream
  within the reviewed staging lifetime.

Any failure blocks the workflow. There is currently no suppression or wildcard
baseline mechanism. If a future scanner introduces a false-positive exception,
it must name one check/finding, link an approving security review, state the
reason and owner, and contain an expiry date; an expired or broadened exception
must fail closed.

For manual verification after deployment:

```bash
gh workflow run staging-dast.yml --ref main -f expected_commit=<full-deployed-sha>
```

Scheduled runs compare against `STAGING_EXPECTED_COMMIT`. The deployment pipeline
should call this workflow with its immutable commit SHA and wait for success
before production promotion.
