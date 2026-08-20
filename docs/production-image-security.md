# Production image signing and admission

Production images are created only by
`.github/workflows/publish-production-image.yml`. The workflow starts after the
`CI` workflow succeeds for a same-repository push to `main`, then waits for the
protected `production-signing` Environment. It builds and pushes one
`sha-<40-character-commit>` tag, records the registry digest, scans that exact
digest, creates a CycloneDX SBOM, and uses GitHub OIDC with Cosign to publish:

- a keyless signature for the image digest;
- a signed `https://slsa.dev/provenance/v1` in-toto attestation;
- a signed `https://cyclonedx.org/bom` in-toto attestation containing the SBOM.

No long-lived signing key or registry password is stored in the repository. The
job alone receives `id-token: write` and `packages: write`; ordinary CI remains
read-only. The expected certificate identity is exactly
`https://github.com/alad1nks/jaiqal/.github/workflows/publish-production-image.yml@refs/heads/main`
from issuer `https://token.actions.githubusercontent.com`.

## GitHub and registry setup

Create the `production-signing` GitHub Environment after the workflow is merged
to `main`:

1. Require at least one production owner as reviewer, prevent self-review, and
   limit deployment branches to `main`.
2. Do not add signing keys, cloud credentials, Firebase credentials, or registry
   passwords. The workflow uses only its ephemeral OIDC token and
   `GITHUB_TOKEN`.
3. Grant the repository-created GHCR package the minimum permission that allows
   this repository's release workflow to publish it. Do not grant package-write
   to ordinary CI workflows or user PATs.
4. Treat every `sha-<commit>` tag as write-once. The workflow refuses to overwrite
   an existing tag. Restrict package administration so no other actor can move or
   delete release tags during normal operation.

The final workflow line `Deploy only: ghcr.io/...@sha256:...` is the only valid
deployment input. A tag is a discovery label, not an authorization boundary.
Never put `:latest` or even `:sha-...` into a production manifest.

If a run fails after the image was pushed but before signing completed, that
digest is not releasable. Investigate the failed run. An authorized registry
owner may remove only that exact orphan commit tag/version and rerun the same
verified commit; never overwrite it or deploy the partially published digest.

## Kubernetes admission rollout

The reference policies require Kubernetes 1.30 or later because
`admissionregistration.k8s.io/v1` `ValidatingAdmissionPolicy` is used to reject
mutable tags before workload creation. Install the Sigstore Policy Controller
in its own namespace using the reviewed pinned chart version, following the
[official GitHub admission guide](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/enforce-artifact-attestations):

```bash
helm upgrade policy-controller --install --atomic \
  --create-namespace --namespace artifact-attestations \
  oci://ghcr.io/sigstore/helm-charts/policy-controller \
  --version 0.10.5
```

Review the rendered controller resources and confirm its validating webhook
uses `failurePolicy: Fail`. The release workflow uses the public Sigstore trust
root through GitHub OIDC; it does not depend on a repository key. If the signing
trust domain changes, add the new exact issuer/subject as a reviewed transition,
test both paths, then remove the old identity after the last approved rollback
digest no longer needs it. Never replace exact identities with `.*`.

Apply `deploy/kubernetes/image-verification-policy.yaml`, then the runtime
manifest. The production Namespace carries
`policy.sigstore.dev/include: "true"`. Three matching `ClusterImagePolicy`
resources are ANDed by the controller and require, independently, the image
signature, SLSA provenance, and CycloneDX SBOM from the exact release workflow.
The CEL policy and binding additionally require the approved GHCR repository and
`@sha256:<64 lowercase hex>` syntax.

Before traffic is shifted, prove all of these in staging with server-side dry
runs or disposable Deployments:

- the released signed digest is admitted;
- `:latest`, `:sha-<commit>`, another registry, and an uppercase/malformed digest
  are denied;
- an unsigned digest is denied;
- a digest signed by another issuer, repository, workflow, branch, or person is
  denied;
- removing either the provenance or CycloneDX attestation causes denial;
- stopping the policy-controller or making the registry verification path
  unavailable fails closed rather than admitting the workload.

Run the repository checks before applying any overlay:

```bash
bash scripts/verify-image-release-policy.sh
bash scripts/verify-runtime-policy.sh
```

## Rollback, trust rotation, and break-glass

Record each promoted digest, commit SHA, workflow run, scan result, and approval
in the deployment system. Normal rollback means deploying a previously promoted
digest whose signature and both attestations still pass the current admission
policies; admission is re-evaluated during rollback.

Keyless signing has no repository-held private key to rotate. Rotation concerns
the Sigstore trust root and accepted workload identity. Update the controller and
trust material through a reviewed cluster change, test a newly signed canary and
at least one retained rollback digest, and monitor verification failures before
retiring old trust material.

Break-glass must not mean changing a policy to `warn`, adding a wildcard identity,
removing the Namespace label, or deploying a tag. Prefer rollback to a known-good
signed digest. If admission itself is the incident, require two authorized
operators, an incident record, a narrowly scoped and time-bounded cluster change,
continuous audit logging, and immediate restoration plus a post-incident review.
The bypass must never grant CI a long-lived key or make an unverified image an
approved rollback candidate.
