# Production security observability

The repository defines the provider-independent, fail-closed contract in
`deploy/observability/security-observability-policy.yaml`. Application security
events are emitted to stdout as a JSON message with `eventType` and
`schemaVersion=1`; the surrounding Logback prefix is transport metadata, not part
of the event schema. The production collector must parse the message JSON and
route only the two allowlisted event types to a dedicated security destination.

## Required production controls

- Use a write-only collector identity and append-only storage with immutable
  retention of at least 365 days. Only the `security-incident-response` group may
  read it. Audit every approved export and do not grant bulk export to application
  or collector identities.
- Preserve the exact JSON fields. Do not enrich security records with HTTP headers,
  request bodies, Firebase UID, email, token/claim material, or arbitrary exception
  messages. `requestId` is the only client-correlatable field and must match the
  application pattern in the policy.
- Materialize every alert from the policy with its threshold, window, and
  deduplication interval. Page on the first collector export failure within five
  minutes; a healthy application with a broken audit pipeline is a security alert.
- Protect changes to collectors, indexes/buckets, retention locks, readers,
  exporters, alert rules, and notification routes through reviewed infrastructure
  as code. Separate provider administrators from normal readers where possible.

## Acceptance test before promotion

Use isolated staging resources and synthetic UUIDs/request IDs; never use real
credentials as canaries. Record the deployed commit and evidence timestamps, then:

1. Generate rejected authentication and rate-limit events, one quarantine
   transition, successful and rejected/failed user provisioning, and a capacity
   threshold event.
2. Confirm every payload has `schemaVersion=1`, contains only the allowlisted
   schema, reaches immutable storage, and is searchable by its safe `requestId`.
3. Confirm the corresponding deduplicated notification reaches the intended
   on-call route exactly once within its policy window.
4. Block collector egress or point a staging exporter at an intentional failing
   sink. Confirm `collector-export-failures` pages within five minutes, then restore
   delivery and verify buffered records arrive without duplication or loss.
5. Search stored fields and exported evidence for `authorization`, `rawToken`,
   `claimCode`, `firebaseUid`, `email`, and `requestBody`; all must be absent.
6. Exercise an approved incident export with two-person approval, verify its audit
   record, expire the export, and confirm the collector identity cannot read it.

Run these repository checks before provider testing:

```bash
bash scripts/verify-security-observability-policy.sh
bash scripts/test-security-observability-policy.sh
```

The repository checks prove schema and policy integrity, not provider state. P3.17
is complete only after the owner attaches production evidence for ingestion,
immutability/retention, access, every alert, delivery-failure detection, safe
search, and controlled export.
