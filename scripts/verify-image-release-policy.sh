#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
  echo "Usage: $0 [repository-root]" >&2
  exit 2
fi

repo_root="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"

ruby - "$repo_root" <<'RUBY'
require "yaml"

def assert_release(condition, message)
  abort("Image release policy violation: #{message}") unless condition
end

repo_root = ARGV.fetch(0)
workflow_path = File.join(repo_root, ".github/workflows/publish-production-image.yml")
policy_path = File.join(repo_root, "deploy/kubernetes/image-verification-policy.yaml")
runtime_path = File.join(repo_root, "deploy/kubernetes/runtime-policy.yaml")

assert_release(File.file?(workflow_path), "production image workflow is missing")
assert_release(File.file?(policy_path), "image admission policy is missing")
assert_release(File.file?(runtime_path), "runtime policy is missing")

workflow = File.read(workflow_path)
required_workflow_fragments = [
  "  workflow_run:\n",
  "    workflows: [CI]",
  "github.event.workflow_run.conclusion == 'success'",
  "github.event.workflow_run.event == 'push'",
  "github.event.workflow_run.head_branch == 'main'",
  "github.event.workflow_run.head_repository.full_name == github.repository",
  "    environment: production-signing",
  "      id-token: write",
  "      packages: write",
  "          persist-credentials: false",
  "          ref: ${{ github.event.workflow_run.head_sha }}",
  "Refusing to overwrite existing image tag",
  "          push: true",
  "          provenance: false",
  "          sbom: false",
  "          image-ref: ${{ env.IMAGE_NAME }}@${{ steps.image.outputs.digest }}",
  "          severity: CRITICAL,HIGH",
  "          exit-code: \"1\"",
  "cosign sign --yes",
  "cosign attest --yes --type https://slsa.dev/provenance/v1",
  "cosign attest --yes --type https://cyclonedx.org/bom",
  "cosign verify-attestation",
  "--certificate-identity \"$SIGNING_IDENTITY\"",
  "--certificate-oidc-issuer \"$SIGNING_ISSUER\"",
]
required_workflow_fragments.each do |fragment|
  assert_release(workflow.include?(fragment), "workflow control is missing: #{fragment.strip}")
end
assert_release(
  workflow.include?("SIGNING_IDENTITY: https://github.com/alad1nks/jaiqal/.github/workflows/publish-production-image.yml@refs/heads/main"),
  "workflow signing identity changed",
)
assert_release(
  workflow.include?("SIGNING_ISSUER: https://token.actions.githubusercontent.com"),
  "workflow signing issuer changed",
)
assert_release(workflow.scan('--certificate-identity "$SIGNING_IDENTITY"').length == 3, "all three release objects must verify exact identity")
assert_release(workflow.scan('--certificate-oidc-issuer "$SIGNING_ISSUER"').length == 3, "all three release objects must verify exact issuer")
assert_release(!workflow.include?("workflow_dispatch:"), "production signing must not bypass successful CI")
assert_release(
  workflow.scan(/\$\{\{\s*secrets\.([A-Za-z0-9_]+)\s*\}\}/).flatten.uniq == ["GITHUB_TOKEN"],
  "only the ephemeral GITHUB_TOKEN may be used by the release workflow",
)
assert_release(!workflow.match?(/set\s+-[^\n]*x/), "shell trace logging is forbidden in signing steps")

documents = YAML.load_stream(File.read(policy_path)).compact
find = lambda do |kind, name|
  documents.find { |document| document["kind"] == kind && document.dig("metadata", "name") == name }
end

digest_policy = find.call("ValidatingAdmissionPolicy", "jaiqal-server-requires-image-digest")
assert_release(digest_policy, "digest-only ValidatingAdmissionPolicy is missing")
assert_release(digest_policy.dig("spec", "failurePolicy") == "Fail", "digest policy must fail closed")
rule = Array(digest_policy.dig("spec", "matchConstraints", "resourceRules")).first || {}
assert_release(rule["apiGroups"] == ["apps"] && rule["apiVersions"] == ["v1"], "digest policy must target apps/v1")
assert_release(Array(rule["operations"]).sort == %w[CREATE UPDATE], "digest policy must cover create and update")
assert_release(rule["resources"] == ["deployments"], "digest policy must target Deployments")
validations = Array(digest_policy.dig("spec", "validations"))
assert_release(validations.length == 2, "digest policy must protect all references and the named server Deployment")
expression = validations.map { |validation| validation["expression"].to_s }.join("\n")
assert_release(expression.include?("ghcr\\\\.io/alad1nks/jaiqal-server@sha256:[0-9a-f]{64}"), "digest policy must reject tags and other registries")
assert_release(expression.include?("containers.all") && expression.include?("initContainers.all"), "digest policy must cover containers and initContainers")

binding = find.call("ValidatingAdmissionPolicyBinding", "jaiqal-server-requires-image-digest")
assert_release(binding, "digest policy binding is missing")
assert_release(binding.dig("spec", "validationActions") == ["Deny"], "digest policy binding must deny")
assert_release(
  binding.dig("spec", "matchResources", "namespaceSelector", "matchLabels", "kubernetes.io/metadata.name") == "jaiqal-production",
  "digest policy binding must select only the production namespace",
)

expected_image = "ghcr.io/alad1nks/jaiqal-server**"
expected_issuer = "https://token.actions.githubusercontent.com"
expected_subject = "https://github.com/alad1nks/jaiqal/.github/workflows/publish-production-image.yml@refs/heads/main"

policies = {
  "jaiqal-server-signature" => nil,
  "jaiqal-server-provenance" => "https://slsa.dev/provenance/v1",
  "jaiqal-server-cyclonedx-sbom" => "https://cyclonedx.org/bom",
}
policies.each do |name, predicate_type|
  policy = find.call("ClusterImagePolicy", name)
  assert_release(policy, "ClusterImagePolicy/#{name} is missing")
  assert_release(!policy.dig("spec", "mode") || policy.dig("spec", "mode") == "enforce", "#{name} must enforce")
  assert_release(policy.dig("spec", "images") == [{"glob" => expected_image}], "#{name} image scope changed")
  authorities = Array(policy.dig("spec", "authorities"))
  assert_release(authorities.length == 1, "#{name} must have exactly one authority")
  identities = Array(authorities.first.dig("keyless", "identities"))
  assert_release(
    identities == [{"issuer" => expected_issuer, "subject" => expected_subject}],
    "#{name} must use the exact release workflow identity and issuer",
  )
  attestations = Array(authorities.first["attestations"])
  if predicate_type
    assert_release(attestations.length == 1, "#{name} must require exactly one attestation")
    assert_release(attestations.first["predicateType"] == predicate_type, "#{name} predicate type changed")
    cue = attestations.first.dig("policy", "data").to_s
    assert_release(attestations.first.dig("policy", "type") == "cue", "#{name} must validate its predicate")
    assert_release(cue.include?(predicate_type), "#{name} CUE policy must bind the predicate type")
  else
    assert_release(attestations.empty?, "signature policy must verify a plain image signature")
  end
end

provenance = find.call("ClusterImagePolicy", "jaiqal-server-provenance").dig("spec", "authorities", 0, "attestations", 0, "policy", "data")
%w[Attestations/GitHubActionsWorkflow publish-production-image.yml refs/heads/main git+https://github.com/alad1nks/jaiqal].each do |claim|
  assert_release(provenance.include?(claim), "provenance policy is missing claim #{claim}")
end
sbom = find.call("ClusterImagePolicy", "jaiqal-server-cyclonedx-sbom").dig("spec", "authorities", 0, "attestations", 0, "policy", "data")
assert_release(sbom.include?('bomFormat: "CycloneDX"'), "SBOM policy must validate CycloneDX content")
assert_release(sbom.include?("components: [...]"), "SBOM policy must require a components array")

runtime = YAML.load_stream(File.read(runtime_path)).compact
namespace = runtime.find { |document| document["kind"] == "Namespace" && document.dig("metadata", "name") == "jaiqal-production" }
deployment = runtime.find { |document| document["kind"] == "Deployment" && document.dig("metadata", "name") == "jaiqal-server" }
container = Array(deployment&.dig("spec", "template", "spec", "containers")).find { |candidate| candidate["name"] == "server" }
assert_release(namespace&.dig("metadata", "labels", "policy.sigstore.dev/include") == "true", "Sigstore enforcement is not enabled for production")
assert_release(
  container&.dig("image").to_s.match?(%r{\Aghcr\.io/alad1nks/jaiqal-server@sha256:[0-9a-f]{64}\z}),
  "runtime image must be an immutable digest covered by admission policies",
)

puts "Production image release and admission policies are structurally enforced"
RUBY
