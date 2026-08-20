#!/usr/bin/env bash
set -euo pipefail

if (( $# > 0 )); then
  echo "Usage: $0" >&2
  exit 2
fi

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

ruby - "$repo_root" <<'RUBY'
require "fileutils"
require "json"
require "open3"
require "tmpdir"
require "yaml"

REPO_ROOT = ARGV.fetch(0)
MEDIUM_GUARD = File.join(REPO_ROOT, "scripts/verify-medium-vulnerability-baseline.sh")
RUNTIME_GUARD = File.join(REPO_ROOT, "scripts/verify-runtime-policy.sh")
SUPPLY_GUARD = File.join(REPO_ROOT, "scripts/verify-supply-chain-inputs.sh")
IMAGE_GUARD = File.join(REPO_ROOT, "scripts/verify-image-release-policy.sh")

def run_guard(*command, expect_success:)
  stdout, stderr, status = Open3.capture3(*command)
  return if status.success? == expect_success

  expectation = expect_success ? "succeed" : "reject the negative fixture"
  abort <<~MESSAGE
    Security guard self-test expected #{command.join(" ")} to #{expectation}, exit=#{status.exitstatus}
    stdout:
    #{stdout}
    stderr:
    #{stderr}
  MESSAGE
end

def write_runtime_fixture(root)
  destination = File.join(root, "deploy/kubernetes")
  FileUtils.mkdir_p(destination)
  %w[runtime-policy.yaml kubelet-config.yaml].each do |name|
    FileUtils.cp(File.join(REPO_ROOT, "deploy/kubernetes", name), File.join(destination, name))
  end
end

def mutate_runtime_fixture(root)
  manifest = File.join(root, "deploy/kubernetes/runtime-policy.yaml")
  kubelet_path = File.join(root, "deploy/kubernetes/kubelet-config.yaml")
  documents = YAML.load_stream(File.read(manifest)).compact
  kubelet = YAML.load_file(kubelet_path)
  yield documents, kubelet
  File.write(manifest, documents.map { |document| YAML.dump(document) }.join("---\n"))
  File.write(kubelet_path, YAML.dump(kubelet))
end

def resource(documents, kind, name)
  documents.find { |document| document["kind"] == kind && document.dig("metadata", "name") == name } or
    abort "Self-test fixture is missing #{kind}/#{name}"
end

def runtime_parts(documents)
  deployment = resource(documents, "Deployment", "jaiqal-server")
  pod = deployment.dig("spec", "template", "spec")
  container = pod.fetch("containers").find { |candidate| candidate["name"] == "server" }
  [pod, container]
end

def write_supply_fixture(root)
  {
    ".github/workflows/ci.yml" => ".github/workflows/ci.yml",
    ".github/workflows/staging-dast.yml" => ".github/workflows/staging-dast.yml",
    ".github/workflows/publish-production-image.yml" => ".github/workflows/publish-production-image.yml",
    ".github/CODEOWNERS" => ".github/CODEOWNERS",
    "server/Dockerfile" => "server/Dockerfile",
    "gradle/verification-metadata.xml" => "gradle/verification-metadata.xml",
    "gradle/wrapper/gradle-wrapper.properties" => "gradle/wrapper/gradle-wrapper.properties",
    "scripts/trivy-medium-baseline.txt" => "scripts/trivy-medium-baseline.txt",
    "scripts/verify-medium-vulnerability-baseline.sh" => "scripts/verify-medium-vulnerability-baseline.sh",
    "scripts/test-security-guards.sh" => "scripts/test-security-guards.sh",
    "scripts/configure-github-security.sh" => "scripts/configure-github-security.sh",
    "scripts/staging-dast.sh" => "scripts/staging-dast.sh",
    "scripts/run-staging-dast.sh" => "scripts/run-staging-dast.sh",
    "scripts/test-staging-dast.sh" => "scripts/test-staging-dast.sh",
    "scripts/verify-image-release-policy.sh" => "scripts/verify-image-release-policy.sh",
    "scripts/verify-security-observability-policy.sh" => "scripts/verify-security-observability-policy.sh",
    "scripts/test-security-observability-policy.sh" => "scripts/test-security-observability-policy.sh",
    "deploy/observability/security-observability-policy.yaml" => "deploy/observability/security-observability-policy.yaml",
    "docs/security-audit.md" => "docs/security-audit.md",
    "docs/security-operations-runbook.md" => "docs/security-operations-runbook.md",
    "scripts/verify-security-documentation.sh" => "scripts/verify-security-documentation.sh",
    "scripts/test-security-documentation.sh" => "scripts/test-security-documentation.sh",
  }.each do |source, destination|
    target = File.join(root, destination)
    FileUtils.mkdir_p(File.dirname(target))
    FileUtils.cp(File.join(REPO_ROOT, source), target)
  end
  Dir.glob(File.join(REPO_ROOT, "compose*.yaml")).each { |source| FileUtils.cp(source, root) }
end

def write_image_fixture(root)
  {
    ".github/workflows/publish-production-image.yml" => ".github/workflows/publish-production-image.yml",
    "deploy/kubernetes/image-verification-policy.yaml" => "deploy/kubernetes/image-verification-policy.yaml",
    "deploy/kubernetes/runtime-policy.yaml" => "deploy/kubernetes/runtime-policy.yaml",
  }.each do |source, destination|
    target = File.join(root, destination)
    FileUtils.mkdir_p(File.dirname(target))
    FileUtils.cp(File.join(REPO_ROOT, source), target)
  end
end

def replace_once(path, before, after)
  content = File.read(path)
  abort "Self-test mutation target not found: #{before}" unless content.include?(before)
  File.write(path, content.sub(before, after))
end

def mutate_step(path, step_name, before, after)
  content = File.read(path)
  pattern = /(      - name: #{Regexp.escape(step_name)}\n.*?)(?=      - name:|\n  [a-zA-Z0-9_-]+:|\z)/m
  match = content.match(pattern) or abort "Self-test CI step not found: #{step_name}"
  step = match[1]
  abort "Self-test setting not found in #{step_name}: #{before}" unless step.include?(before)
  File.write(path, content.sub(step, step.sub(before, after)))
end

before_status, git_error, git_status = Open3.capture3("git", "status", "--porcelain=v1", "-uall", chdir: REPO_ROOT)
abort "Cannot snapshot working tree: #{git_error}" unless git_status.success?

Dir.mktmpdir("jaiqal-security-guard-tests-") do |temporary_root|
  medium_root = File.join(temporary_root, "medium")
  FileUtils.mkdir_p(medium_root)
  empty_report = File.join(medium_root, "empty.json")
  reviewed_report = File.join(medium_root, "reviewed.json")
  new_report = File.join(medium_root, "new.json")
  empty_baseline = File.join(medium_root, "empty-baseline.txt")
  reviewed_baseline = File.join(medium_root, "reviewed-baseline.txt")
  wildcard_baseline = File.join(medium_root, "wildcard-baseline.txt")
  File.write(empty_report, JSON.generate("Results" => []))
  finding = {
    "VulnerabilityID" => "CVE-2099-0001",
    "PkgName" => "example-runtime",
    "InstalledVersion" => "1.2.3",
    "Severity" => "MEDIUM",
  }
  File.write(reviewed_report, JSON.generate("Results" => [{"Vulnerabilities" => [finding]}]))
  File.write(new_report, JSON.generate("Results" => [{"Vulnerabilities" => [finding.merge("VulnerabilityID" => "CVE-2099-9999")]}]))
  File.write(empty_baseline, "# intentionally empty\n")
  File.write(reviewed_baseline, "CVE-2099-0001|example-runtime|1.2.3\n")
  File.write(wildcard_baseline, "CVE-*|example-runtime|1.2.3\n")
  run_guard("bash", MEDIUM_GUARD, empty_report, empty_baseline, expect_success: true)
  run_guard("bash", MEDIUM_GUARD, reviewed_report, reviewed_baseline, expect_success: true)
  run_guard("bash", MEDIUM_GUARD, new_report, reviewed_baseline, expect_success: false)
  run_guard("bash", MEDIUM_GUARD, reviewed_report, wildcard_baseline, expect_success: false)

  runtime_cases = {
    "namespace enforce" => ->(docs, _) { resource(docs, "Namespace", "jaiqal-production").dig("metadata", "labels")["pod-security.kubernetes.io/enforce"] = "baseline" },
    "namespace audit" => ->(docs, _) { resource(docs, "Namespace", "jaiqal-production").dig("metadata", "labels")["pod-security.kubernetes.io/audit"] = "baseline" },
    "namespace warn" => ->(docs, _) { resource(docs, "Namespace", "jaiqal-production").dig("metadata", "labels")["pod-security.kubernetes.io/warn"] = "baseline" },
    "sigstore admission" => ->(docs, _) { resource(docs, "Namespace", "jaiqal-production").dig("metadata", "labels")["policy.sigstore.dev/include"] = "false" },
    "service account token" => ->(docs, _) { resource(docs, "ServiceAccount", "jaiqal-server")["automountServiceAccountToken"] = true },
    "dedicated service account" => ->(docs, _) { runtime_parts(docs)[0]["serviceAccountName"] = "default" },
    "pod token" => ->(docs, _) { runtime_parts(docs)[0]["automountServiceAccountToken"] = true },
    "service links" => ->(docs, _) { runtime_parts(docs)[0]["enableServiceLinks"] = true },
    "host network" => ->(docs, _) { runtime_parts(docs)[0]["hostNetwork"] = true },
    "host pid" => ->(docs, _) { runtime_parts(docs)[0]["hostPID"] = true },
    "host ipc" => ->(docs, _) { runtime_parts(docs)[0]["hostIPC"] = true },
    "pod root" => ->(docs, _) { runtime_parts(docs)[0].dig("securityContext")["runAsNonRoot"] = false },
    "pod uid" => ->(docs, _) { runtime_parts(docs)[0].dig("securityContext")["runAsUser"] = 10002 },
    "pod gid" => ->(docs, _) { runtime_parts(docs)[0].dig("securityContext")["runAsGroup"] = 10002 },
    "pod fs group" => ->(docs, _) { runtime_parts(docs)[0].dig("securityContext")["fsGroup"] = 10002 },
    "pod fs group policy" => ->(docs, _) { runtime_parts(docs)[0].dig("securityContext")["fsGroupChangePolicy"] = "Always" },
    "pod seccomp" => ->(docs, _) { runtime_parts(docs)[0].dig("securityContext", "seccompProfile")["type"] = "Unconfined" },
    "privilege escalation" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext")["allowPrivilegeEscalation"] = true },
    "privileged" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext")["privileged"] = true },
    "writable root" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext")["readOnlyRootFilesystem"] = false },
    "container root" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext")["runAsNonRoot"] = false },
    "container uid" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext")["runAsUser"] = 10002 },
    "container gid" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext")["runAsGroup"] = 10002 },
    "capabilities" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext", "capabilities")["drop"] = [] },
    "container seccomp" => ->(docs, _) { runtime_parts(docs)[1].dig("securityContext", "seccompProfile")["type"] = "Unconfined" },
    "mutable image" => ->(docs, _) { runtime_parts(docs)[1]["image"] = "registry.example.invalid/jaiqal/server:latest" },
    "uncovered image registry" => ->(docs, _) { runtime_parts(docs)[1]["image"] = "registry.example.invalid/jaiqal/server@sha256:#{'0' * 64}" },
    "resource requests" => ->(docs, _) { runtime_parts(docs)[1].dig("resources", "requests")["memory"] = "256Mi" },
    "resource limits" => ->(docs, _) { runtime_parts(docs)[1].dig("resources", "limits").delete("ephemeral-storage") },
    "development environment" => ->(docs, _) { runtime_parts(docs)[1]["env"].find { |entry| entry["name"] == "APP_ENVIRONMENT" }["value"] = "development" },
    "deployment commit source" => ->(docs, _) { runtime_parts(docs)[1]["env"].find { |entry| entry["name"] == "DEPLOYMENT_COMMIT_SHA" }.delete("valueFrom") },
    "untrusted TLS marker" => ->(docs, _) { runtime_parts(docs)[1]["env"].find { |entry| entry["name"] == "TRUSTED_PROXY_TERMINATES_TLS" }["value"] = "false" },
    "revocation disabled" => ->(docs, _) { runtime_parts(docs)[1]["env"].find { |entry| entry["name"] == "FIREBASE_CHECK_REVOKED_TOKENS" }["value"] = "false" },
    "migration secret" => ->(docs, _) { runtime_parts(docs)[1]["env"] << {"name" => "MIGRATION_DATABASE_PASSWORD", "value" => "unsafe"} },
    "database password env" => ->(docs, _) { runtime_parts(docs)[1]["env"] << {"name" => "DATABASE_PASSWORD", "value" => "unsafe"} },
    "database password file" => ->(docs, _) { runtime_parts(docs)[1]["env"].find { |entry| entry["name"] == "DATABASE_PASSWORD_FILE" }["value"] = "/tmp/password" },
    "trusted proxy source" => ->(docs, _) { runtime_parts(docs)[1]["env"].find { |entry| entry["name"] == "TRUSTED_PROXY_CIDRS" }.delete("valueFrom") },
    "secret mount" => ->(docs, _) { runtime_parts(docs)[1]["volumeMounts"].find { |mount| mount["name"] == "runtime-secrets" }["readOnly"] = false },
    "secret mount path" => ->(docs, _) { runtime_parts(docs)[1]["volumeMounts"].find { |mount| mount["name"] == "runtime-secrets" }["mountPath"] = "/tmp/secrets" },
    "secret mode" => ->(docs, _) { runtime_parts(docs)[0]["volumes"].find { |volume| volume["name"] == "runtime-secrets" }.dig("projected")["defaultMode"] = 0o644 },
    "database secret" => ->(docs, _) { runtime_parts(docs)[0]["volumes"].find { |volume| volume["name"] == "runtime-secrets" }.dig("projected", "sources", 0, "secret", "items").reject! { |item| item["path"] == "database-password" } },
    "firebase secret" => ->(docs, _) { runtime_parts(docs)[0]["volumes"].find { |volume| volume["name"] == "runtime-secrets" }.dig("projected", "sources", 0, "secret", "items").reject! { |item| item["path"] == "firebase-service-account.json" } },
    "postgres ca" => ->(docs, _) { runtime_parts(docs)[0]["volumes"].find { |volume| volume["name"] == "runtime-secrets" }.dig("projected", "sources", 0, "secret", "items").reject! { |item| item["path"] == "postgres-ca.crt" } },
    "disk tmp" => ->(docs, _) { runtime_parts(docs)[0]["volumes"].find { |volume| volume["name"] == "temporary-files" }.dig("emptyDir")["medium"] = "" },
    "wrong tmp volume" => ->(docs, _) { runtime_parts(docs)[1]["volumeMounts"].find { |mount| mount["mountPath"] == "/tmp" }["name"] = "runtime-secrets" },
    "unbounded tmp" => ->(docs, _) { runtime_parts(docs)[0]["volumes"].find { |volume| volume["name"] == "temporary-files" }.dig("emptyDir").delete("sizeLimit") },
    "startup probe" => ->(docs, _) { runtime_parts(docs)[1].delete("startupProbe") },
    "startup probe bounds" => ->(docs, _) { runtime_parts(docs)[1].dig("startupProbe")["failureThreshold"] = 300 },
    "readiness probe" => ->(docs, _) { runtime_parts(docs)[1].delete("readinessProbe") },
    "readiness probe bounds" => ->(docs, _) { runtime_parts(docs)[1].dig("readinessProbe")["timeoutSeconds"] = 30 },
    "liveness probe" => ->(docs, _) { runtime_parts(docs)[1].delete("livenessProbe") },
    "liveness probe path" => ->(docs, _) { runtime_parts(docs)[1].dig("livenessProbe", "httpGet")["path"] = "/health/ready" },
    "default deny selector" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-default-deny").dig("spec")["podSelector"] = {"matchLabels" => {"app" => "server"}} },
    "default deny types" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-default-deny").dig("spec")["policyTypes"] = ["Ingress"] },
    "allow policy selector" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "podSelector")["matchLabels"] = {} },
    "allow policy types" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec")["policyTypes"] = ["Ingress"] },
    "extra ingress" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "ingress") << {} },
    "ingress port" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "ingress", 0, "ports", 0)["port"] = 80 },
    "ingress namespace" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "ingress", 0, "from", 0, "namespaceSelector", "matchLabels").clear },
    "ingress pod" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "ingress", 0, "from", 0, "podSelector", "matchLabels").clear },
    "extra egress" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress") << {} },
    "cidr egress" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 0, "to") << {"ipBlock" => {"cidr" => "0.0.0.0/0"}} },
    "dns selector" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 0, "to", 0, "podSelector", "matchLabels").clear },
    "dns namespace" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 0, "to", 0, "namespaceSelector", "matchLabels").clear },
    "dns ports" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 0, "ports").pop },
    "database selector" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 1, "to", 0, "podSelector", "matchLabels").clear },
    "database namespace" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 1, "to", 0, "namespaceSelector", "matchLabels").clear },
    "database port" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 1, "ports", 0)["port"] = 3306 },
    "proxy selector" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 2, "to", 0, "podSelector", "matchLabels").clear },
    "proxy namespace" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 2, "to", 0, "namespaceSelector", "matchLabels").clear },
    "proxy port" => ->(docs, _) { resource(docs, "NetworkPolicy", "jaiqal-server-allow-required").dig("spec", "egress", 2, "ports", 0)["port"] = 443 },
    "pid limit" => ->(_, kubelet) { kubelet["podPidsLimit"] = 0 },
  }

  runtime_positive = File.join(temporary_root, "runtime-positive")
  write_runtime_fixture(runtime_positive)
  run_guard("bash", RUNTIME_GUARD, runtime_positive, expect_success: true)
  runtime_cases.each do |name, mutation|
    fixture = File.join(temporary_root, "runtime-negative-#{name.gsub(/[^a-z0-9]+/i, "-")}")
    write_runtime_fixture(fixture)
    mutate_runtime_fixture(fixture, &mutation)
    run_guard("bash", RUNTIME_GUARD, fixture, expect_success: false)
  end

  image_cases = {
    "workflow bypass" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "  workflow_run:", "  workflow_dispatch:") },
    "failed CI allowed" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "github.event.workflow_run.conclusion == 'success'", "github.event.workflow_run.conclusion != 'cancelled'") },
    "unprotected environment" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "    environment: production-signing", "    environment: production") },
    "OIDC disabled" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "      id-token: write", "      id-token: read") },
    "tag overwrite" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "Refusing to overwrite existing image tag", "Overwriting existing image tag") },
    "missing image signature" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "cosign sign --yes", "cosign version #") },
    "missing provenance" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "cosign attest --yes --type https://slsa.dev/provenance/v1", "cosign attest --yes --type custom") },
    "missing SBOM" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), "cosign attest --yes --type https://cyclonedx.org/bom", "cosign attest --yes --type custom") },
    "wrong verification issuer" => ->(root) { replace_once(File.join(root, ".github/workflows/publish-production-image.yml"), '--certificate-oidc-issuer "$SIGNING_ISSUER"', '--certificate-oidc-issuer-regexp ".*"') },
    "digest policy fail open" => ->(root) { replace_once(File.join(root, "deploy/kubernetes/image-verification-policy.yaml"), "  failurePolicy: Fail", "  failurePolicy: Ignore") },
    "digest binding warn" => ->(root) { replace_once(File.join(root, "deploy/kubernetes/image-verification-policy.yaml"), "  validationActions: [Deny]", "  validationActions: [Warn]") },
    "mutable image admitted" => ->(root) { path = File.join(root, "deploy/kubernetes/image-verification-policy.yaml"); File.write(path, File.read(path).gsub("@sha256:[0-9a-f]{64}", ":.*")) },
    "wrong signing issuer" => ->(root) { replace_once(File.join(root, "deploy/kubernetes/image-verification-policy.yaml"), "issuer: https://token.actions.githubusercontent.com", "issuer: https://accounts.google.com") },
    "wrong signing subject" => ->(root) { replace_once(File.join(root, "deploy/kubernetes/image-verification-policy.yaml"), "subject: https://github.com/alad1nks/jaiqal/.github/workflows/publish-production-image.yml@refs/heads/main", "subjectRegExp: .*") },
    "missing provenance claim" => ->(root) { replace_once(File.join(root, "deploy/kubernetes/image-verification-policy.yaml"), "path: \".github/workflows/publish-production-image.yml\"", "path: \".github/workflows/other.yml\"") },
    "SBOM content unchecked" => ->(root) { replace_once(File.join(root, "deploy/kubernetes/image-verification-policy.yaml"), "bomFormat: \"CycloneDX\"", "bomFormat: string") },
    "namespace enforcement off" => ->(root) { replace_once(File.join(root, "deploy/kubernetes/runtime-policy.yaml"), "policy.sigstore.dev/include: \"true\"", "policy.sigstore.dev/include: \"false\"") },
  }

  image_positive = File.join(temporary_root, "image-positive")
  write_image_fixture(image_positive)
  run_guard("bash", IMAGE_GUARD, image_positive, expect_success: true)
  image_cases.each do |name, mutation|
    fixture = File.join(temporary_root, "image-negative-#{name.gsub(/[^a-z0-9]+/i, "-")}")
    write_image_fixture(fixture)
    mutation.call(fixture)
    run_guard("bash", IMAGE_GUARD, fixture, expect_success: false)
  end

  supply_cases = {
    "mutable action" => ->(root, _) { replace_once(File.join(root, ".github/workflows/ci.yml"), "actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5", "actions/checkout@v4") },
    "mutable Docker base" => ->(root, _) { replace_once(File.join(root, "server/Dockerfile"), /@sha256:[0-9a-f]{64}/.match(File.read(File.join(root, "server/Dockerfile")))[0], ":latest") },
    "mutable Compose image" => ->(root, _) { path = Dir.glob(File.join(root, "compose*.yaml")).find { |candidate| File.read(candidate).include?("@sha256:") }; replace_once(path, /@sha256:[0-9a-f]{64}/.match(File.read(path))[0], ":latest") },
    "missing verification metadata" => ->(root, _) { File.delete(File.join(root, "gradle/verification-metadata.xml")) },
    "missing wrapper checksum" => ->(root, _) { path = File.join(root, "gradle/wrapper/gradle-wrapper.properties"); File.write(path, File.read(path).lines.reject { |line| line.start_with?("distributionSha256Sum=") }.join) },
    "persisted checkout credentials" => ->(_, ci) { mutate_step(ci, "Check out repository", "persist-credentials: false", "persist-credentials: true") },
    "mutable dependency review" => ->(_, ci) { mutate_step(ci, "Review dependency changes", "actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294", "actions/dependency-review-action@v5") },
    "dependency review severity" => ->(_, ci) { mutate_step(ci, "Review dependency changes", "fail-on-severity: moderate", "fail-on-severity: critical") },
    "dependency review event" => ->(_, ci) { replace_once(ci, "    if: github.event_name == 'pull_request'", "    if: always()") },
    "missing CODEOWNERS" => ->(root, _) { File.delete(File.join(root, ".github/CODEOWNERS")) },
    "missing authentication owner" => ->(root, _) { path = File.join(root, ".github/CODEOWNERS"); File.write(path, File.read(path).lines.reject { |line| line.start_with?("/server/src/main/kotlin/com/alad1nks/jaiqal/auth/") }.join) },
    "missing security docs owner" => ->(root, _) { path = File.join(root, ".github/CODEOWNERS"); File.write(path, File.read(path).lines.reject { |line| line.start_with?("/docs/security") }.join) },
    "GitHub required check" => ->(root, _) { path = File.join(root, "scripts/configure-github-security.sh"); replace_once(path, '{"context": "sast"}', '{"context": "unit-test"}') },
    "GitHub push protection" => ->(root, _) { path = File.join(root, "scripts/configure-github-security.sh"); replace_once(path, '"secret_scanning_push_protection": {"status": "enabled"}', '"secret_scanning_push_protection": {"status": "disabled"}') },
    "missing DAST workflow" => ->(root, _) { File.delete(File.join(root, ".github/workflows/staging-dast.yml")) },
    "DAST deployment hook" => ->(root, _) { path = File.join(root, ".github/workflows/staging-dast.yml"); replace_once(path, "  workflow_call:", "  disabled_workflow_call:") },
    "DAST environment" => ->(root, _) { path = File.join(root, ".github/workflows/staging-dast.yml"); replace_once(path, "    environment: staging-security", "    environment: production") },
    "DAST artifacts" => ->(root, _) { path = File.join(root, ".github/workflows/staging-dast.yml"); File.write(path, File.read(path) + "\n# actions/upload-artifact is forbidden here\n") },
    "DAST commit binding" => ->(root, _) { path = File.join(root, "scripts/staging-dast.sh"); File.write(path, File.read(path).gsub("DAST-COMMIT-001", "DAST-COMMIT-REMOVED")) },
    "DAST HTTPS only" => ->(root, _) { path = File.join(root, "scripts/staging-dast.sh"); File.write(path, File.read(path).gsub("--proto '=https'", "--proto '=http,https'")) },
    "DAST bounded SSE" => ->(root, _) { path = File.join(root, "scripts/staging-dast.sh"); replace_once(path, "sse_max_lifetime <= 60", "sse_max_lifetime <= 600") },
    "DAST refresh token cleanup" => ->(root, _) { path = File.join(root, "scripts/run-staging-dast.sh"); replace_once(path, "unset DAST_FIREBASE_WEB_API_KEY DAST_USER_REFRESH_TOKEN DAST_OTHER_USER_REFRESH_TOKEN", ": # token cleanup removed") },
    "DAST token TLS" => ->(root, _) { path = File.join(root, "scripts/run-staging-dast.sh"); replace_once(path, "https://securetoken.googleapis.com/v1/token", "http://securetoken.googleapis.com/v1/token") },
    "DAST self-test" => ->(root, _) { File.delete(File.join(root, "scripts/test-staging-dast.sh")) },
    "secret scanner" => ->(_, ci) { mutate_step(ci, "Scan checkout for committed secrets", "scanners: secret", "scanners: vuln") },
    "secret severity" => ->(_, ci) { mutate_step(ci, "Scan checkout for committed secrets", "severity: UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL", "severity: HIGH,CRITICAL") },
    "secret fail closed" => ->(_, ci) { mutate_step(ci, "Scan checkout for committed secrets", 'exit-code: "1"', 'exit-code: "0"') },
    "misconfig scanner" => ->(_, ci) { mutate_step(ci, "Scan repository security configuration", "scanners: misconfig", "scanners: vuln") },
    "misconfig severity" => ->(_, ci) { mutate_step(ci, "Scan repository security configuration", "severity: CRITICAL,HIGH", "severity: CRITICAL") },
    "misconfig fail closed" => ->(_, ci) { mutate_step(ci, "Scan repository security configuration", 'exit-code: "1"', 'exit-code: "0"') },
    "CodeQL language" => ->(_, ci) { mutate_step(ci, "Initialize CodeQL for Java and Kotlin", "languages: java-kotlin", "languages: java") },
    "CodeQL mode" => ->(_, ci) { mutate_step(ci, "Initialize CodeQL for Java and Kotlin", "build-mode: manual", "build-mode: autobuild") },
    "CodeQL queries" => ->(_, ci) { mutate_step(ci, "Initialize CodeQL for Java and Kotlin", "queries: security-extended", "queries: security-and-quality") },
    "CodeQL build" => ->(_, ci) { mutate_step(ci, "Build server classes for CodeQL", ":server:classes", ":core:api-contract:classes") },
    "CodeQL analyze" => ->(_, ci) { mutate_step(ci, "Analyze Java and Kotlin", "github/codeql-action/analyze@", "github/codeql-action/upload-sarif@") },
    "runtime policy gate" => ->(_, ci) { mutate_step(ci, "Verify production runtime policy", "verify-runtime-policy.sh", "true") },
    "observability policy gate" => ->(_, ci) { mutate_step(ci, "Verify security observability policy", "verify-security-observability-policy.sh", "true") },
    "security documentation gate" => ->(_, ci) { mutate_step(ci, "Verify security audit documentation", "verify-security-documentation.sh", "true") },
    "image release policy gate" => ->(_, ci) { mutate_step(ci, "Verify production image release policy", "verify-image-release-policy.sh", "true") },
    "Firebase graph gate" => ->(_, ci) { mutate_step(ci, "Verify Firebase Storage runtime graph is absent", ":server:verifyFirebaseStorageRuntimeGraph", ":server:classes") },
    "Medium scan severity" => ->(_, ci) { mutate_step(ci, "Capture Medium server runtime findings", "severity: MEDIUM", "severity: LOW") },
    "Medium scan output" => ->(_, ci) { mutate_step(ci, "Capture Medium server runtime findings", "output: trivy-medium.json", "output: ignored.json") },
    "Medium reject gate" => ->(_, ci) { mutate_step(ci, "Reject new Medium server runtime findings", "verify-medium-vulnerability-baseline.sh", "true") },
    "self-test command" => ->(_, ci) { mutate_step(ci, "Run security guard self-tests", "test-security-guards.sh", "true") },
    "self-test dependency" => ->(_, ci) { content = File.read(ci); File.write(ci, content.gsub("    needs: security-guard-self-tests\n", "")) },
  }

  supply_positive = File.join(temporary_root, "supply-positive")
  write_supply_fixture(supply_positive)
  run_guard("bash", SUPPLY_GUARD, supply_positive, expect_success: true)
  supply_cases.each do |name, mutation|
    fixture = File.join(temporary_root, "supply-negative-#{name.gsub(/[^a-z0-9]+/i, "-")}")
    write_supply_fixture(fixture)
    ci = File.join(fixture, ".github/workflows/ci.yml")
    mutation.call(fixture, ci)
    run_guard("bash", SUPPLY_GUARD, fixture, expect_success: false)
  end
end

after_status, git_error, git_status = Open3.capture3("git", "status", "--porcelain=v1", "-uall", chdir: REPO_ROOT)
abort "Cannot verify working tree: #{git_error}" unless git_status.success?
abort "Security guard self-tests changed the working tree" unless before_status == after_status

puts "Security guard positive/negative self-tests passed"
RUBY

bash "$repo_root/scripts/test-staging-dast.sh"
bash "$repo_root/scripts/test-security-observability-policy.sh"
bash "$repo_root/scripts/test-security-documentation.sh"
