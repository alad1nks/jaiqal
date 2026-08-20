# Production runtime policy reference

This directory is a security baseline, not a turnkey environment definition.
Replace the zero example digest with the reviewed and signed server digest,
create the referenced ConfigMap and Secret through the deployment secret manager,
and adapt selectors to the cluster before applying the manifest.

`image-verification-policy.yaml` is the matching admission baseline. It requires
Kubernetes 1.30+, the Sigstore Policy Controller, exact GitHub Actions OIDC
issuer/workflow identity, a plain image signature, SLSA provenance and a signed
CycloneDX SBOM. The production Namespace opts into Sigstore enforcement, while a
fail-closed native CEL policy rejects tags and images outside
`ghcr.io/alad1nks/jaiqal-server@sha256:...`. Installation, negative tests,
rollback and break-glass controls are documented in
[`../../docs/production-image-security.md`](../../docs/production-image-security.md).

Required `jaiqal-runtime-config` keys are `database-url`, `database-user`,
`firebase-project-id`, `public-api-url`, `allowed-origins`,
`deployment-commit-sha`, and
`trusted-proxy-cidrs`. The last value must contain the smallest literal CIDRs of
the ingress addresses observed as direct peers by Ktor after any platform SNAT;
it cannot be a hostname or `/0`. The database URL
must retain `sslmode=verify-full`, `channelBinding=require`, and point
`sslrootcert` to `/var/run/secrets/jaiqal/postgres-ca.crt`.

`deployment-commit-sha` must be the full lowercase 40-character Git commit used
to build the digest-pinned image. `/health/live` exposes it only as the
`X-Deployment-Commit` response header so the staging DAST gate can reject a stale
or wrong deployment before testing it.

The externally managed `jaiqal-runtime-secrets` Secret must contain
`database-password`, `firebase-service-account.json`, and `postgres-ca.crt`.
Never put their values in this repository or a ConfigMap. The projected volume is
read-only and the server reads the database password through
`DATABASE_PASSWORD_FILE`. Prefer workload identity where it can be used without a
node metadata endpoint; then remove the Firebase key item and
`GOOGLE_APPLICATION_CREDENTIALS` together.

The NetworkPolicy requires these namespace/workload labels:

- trusted ingress namespace: `networking.jaiqal.io/ingress=true`, with ingress
  pods labelled `app.kubernetes.io/component=ingress-controller`;
- PostgreSQL namespace: `networking.jaiqal.io/database=true`, with database pods
  labelled `app.kubernetes.io/name=postgresql`;
- controlled egress namespace: named `security-egress` and labelled
  `networking.jaiqal.io/egress=true`, with an HTTPS CONNECT proxy labelled
  `app.kubernetes.io/name=jaiqal-egress-proxy` on port 8443. If the namespace or
  Service name differs, update the proxy hostname and policy together. Restrict
  that proxy to the reviewed Firebase/Google API destinations.

Standard Kubernetes Pod resources do not expose a per-Pod PID field. Merge
`podPidsLimit: 256` from `kubelet-config.yaml` into every worker node's reviewed
KubeletConfiguration, or configure the managed-platform equivalent. Verify the
effective limit on every node before promotion; merely storing this reference
file does not change cluster state.

Run the repository policy check before deployment:

```bash
bash scripts/verify-runtime-policy.sh
bash scripts/verify-image-release-policy.sh
```

Application logs must also be collected according to
[`../observability/security-observability-policy.yaml`](../observability/security-observability-policy.yaml).
The collector is intentionally not embedded as an application sidecar: production
clusters should use their centrally managed node/namespace collector and a
write-only identity. The provider rollout and acceptance test are described in
[`../../docs/security-observability.md`](../../docs/security-observability.md).

After rendering platform overlays, run server-side validation, the platform's
policy/admission checks, and Trivy against the rendered manifests. Test ingress,
DNS, PostgreSQL TLS, Firebase verification through the egress proxy, probes,
temporary-file bounds, and PID exhaustion in staging before shifting traffic.
