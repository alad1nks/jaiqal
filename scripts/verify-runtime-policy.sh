#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
  echo "Usage: $0 [repository-root]" >&2
  exit 2
fi

repo_root="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"

ruby - "$repo_root" <<'RUBY'
require "yaml"

def assert_policy(condition, message)
  abort("Runtime policy violation: #{message}") unless condition
end

repo_root = ARGV.fetch(0)
manifest_path = File.join(repo_root, "deploy/kubernetes/runtime-policy.yaml")
kubelet_path = File.join(repo_root, "deploy/kubernetes/kubelet-config.yaml")
assert_policy(File.file?(manifest_path), "#{manifest_path} is missing")
assert_policy(File.file?(kubelet_path), "#{kubelet_path} is missing")

documents = YAML.load_stream(File.read(manifest_path)).compact
find = lambda do |kind, name|
  documents.find { |doc| doc["kind"] == kind && doc.dig("metadata", "name") == name }
end

namespace = find.call("Namespace", "jaiqal-production")
assert_policy(namespace, "restricted production Namespace is missing")
%w[enforce audit warn].each do |mode|
  assert_policy(
    namespace.dig("metadata", "labels", "pod-security.kubernetes.io/#{mode}") == "restricted",
    "Pod Security #{mode} must be restricted",
  )
end
assert_policy(
  namespace.dig("metadata", "labels", "policy.sigstore.dev/include") == "true",
  "Sigstore image policy enforcement must be enabled",
)

account = find.call("ServiceAccount", "jaiqal-server")
assert_policy(account && account["automountServiceAccountToken"] == false, "ServiceAccount token automount must be disabled")

deployment = find.call("Deployment", "jaiqal-server")
assert_policy(deployment, "server Deployment is missing")
pod = deployment.dig("spec", "template", "spec") || {}
container = Array(pod["containers"]).find { |candidate| candidate["name"] == "server" } || {}
pod_security = pod["securityContext"] || {}
container_security = container["securityContext"] || {}

assert_policy(pod["serviceAccountName"] == "jaiqal-server", "dedicated ServiceAccount is required")
assert_policy(pod["automountServiceAccountToken"] == false, "Pod token automount must be disabled")
assert_policy(pod["enableServiceLinks"] == false, "service-link environment injection must be disabled")
%w[hostNetwork hostPID hostIPC].each do |field|
  assert_policy(pod[field] == false, "#{field} must be false")
end
assert_policy(pod_security["runAsNonRoot"] == true, "Pod must run as non-root")
assert_policy(pod_security["runAsUser"] == 10001, "Pod UID must remain 10001")
assert_policy(pod_security["runAsGroup"] == 10001, "Pod GID must remain 10001")
assert_policy(pod_security["fsGroup"] == 10001, "Pod fsGroup must remain 10001")
assert_policy(pod_security["fsGroupChangePolicy"] == "OnRootMismatch", "fsGroupChangePolicy must remain OnRootMismatch")
assert_policy(pod_security.dig("seccompProfile", "type") == "RuntimeDefault", "Pod seccomp must be RuntimeDefault")
assert_policy(container_security["allowPrivilegeEscalation"] == false, "privilege escalation must be disabled")
assert_policy(container_security["privileged"] == false, "privileged mode must be disabled")
assert_policy(container_security["readOnlyRootFilesystem"] == true, "root filesystem must be read-only")
assert_policy(container_security["runAsNonRoot"] == true, "container must run as non-root")
assert_policy(container_security["runAsUser"] == 10001, "container UID must remain 10001")
assert_policy(container_security["runAsGroup"] == 10001, "container GID must remain 10001")
assert_policy(container_security.dig("capabilities", "drop") == ["ALL"], "all Linux capabilities must be dropped")
assert_policy(container_security.dig("seccompProfile", "type") == "RuntimeDefault", "container seccomp must be RuntimeDefault")
assert_policy(
  container["image"].to_s.match?(%r{\Aghcr\.io/alad1nks/jaiqal-server@sha256:[0-9a-f]{64}\z}),
  "server image must use the policy-covered GHCR repository and an immutable SHA-256 digest",
)

resources = container["resources"] || {}
assert_policy(resources["requests"] == {"cpu" => "250m", "memory" => "512Mi", "ephemeral-storage" => "64Mi"}, "resource requests changed without review")
assert_policy(resources["limits"] == {"cpu" => "1", "memory" => "1Gi", "ephemeral-storage" => "256Mi"}, "resource limits changed without review")

env = Array(container["env"]).to_h { |entry| [entry["name"], entry] }
assert_policy(env.dig("APP_ENVIRONMENT", "value") == "production", "APP_ENVIRONMENT must remain production")
assert_policy(
  env.dig("DEPLOYMENT_COMMIT_SHA", "valueFrom", "configMapKeyRef") == {
    "name" => "jaiqal-runtime-config",
    "key" => "deployment-commit-sha",
  },
  "DEPLOYMENT_COMMIT_SHA must come from the reviewed runtime ConfigMap",
)
assert_policy(env.dig("TRUSTED_PROXY_TERMINATES_TLS", "value") == "true", "trusted proxy TLS termination must remain enabled")
assert_policy(env.dig("FIREBASE_CHECK_REVOKED_TOKENS", "value") == "true", "Firebase revocation checking must remain enabled")
assert_policy(env.keys.none? { |name| name.start_with?("MIGRATION_DATABASE_") }, "migration credentials must not enter the runtime Pod")
assert_policy(!env.key?("DATABASE_PASSWORD"), "database password must not be injected directly into env")
assert_policy(
  env.dig("DATABASE_PASSWORD_FILE", "value") == "/var/run/secrets/jaiqal/database-password",
  "DATABASE_PASSWORD_FILE must reference the projected secret",
)
assert_policy(
  env.dig("TRUSTED_PROXY_CIDRS", "valueFrom", "configMapKeyRef") == {
    "name" => "jaiqal-runtime-config",
    "key" => "trusted-proxy-cidrs",
  },
  "TRUSTED_PROXY_CIDRS must come from the reviewed runtime ConfigMap",
)

secret_mount = Array(container["volumeMounts"]).find { |mount| mount["name"] == "runtime-secrets" }
assert_policy(
  secret_mount && secret_mount["mountPath"] == "/var/run/secrets/jaiqal" && secret_mount["readOnly"] == true,
  "runtime secret mount must use the fixed read-only path",
)
secret_volume = Array(pod["volumes"]).find { |volume| volume["name"] == "runtime-secrets" }
assert_policy(secret_volume&.dig("projected", "defaultMode") == 0o440, "projected secrets must use mode 0440")
secret_items = Array(secret_volume&.dig("projected", "sources")).flat_map do |source|
  Array(source.dig("secret", "items")).map { |item| item["path"] }
end
%w[database-password firebase-service-account.json postgres-ca.crt].each do |path|
  assert_policy(secret_items.include?(path), "projected secret #{path} is required")
end

temporary_mount = Array(container["volumeMounts"]).find { |mount| mount["mountPath"] == "/tmp" }
assert_policy(temporary_mount&.dig("name") == "temporary-files", "writable /tmp must use the bounded temporary volume")
temporary_volume = Array(pod["volumes"]).find { |volume| volume["name"] == temporary_mount&.dig("name") }
assert_policy(temporary_volume&.dig("emptyDir", "medium") == "Memory", "writable /tmp must be memory-backed")
assert_policy(temporary_volume&.dig("emptyDir", "sizeLimit") == "64Mi", "writable /tmp must retain its size limit")
assert_policy(
  container["startupProbe"] == {
    "httpGet" => {"path" => "/health/live", "port" => "http"},
    "periodSeconds" => 5,
    "failureThreshold" => 30,
  },
  "startupProbe safety bounds changed",
)
assert_policy(
  container["readinessProbe"] == {
    "httpGet" => {"path" => "/health/ready", "port" => "http"},
    "periodSeconds" => 10,
    "timeoutSeconds" => 3,
    "failureThreshold" => 3,
  },
  "readinessProbe safety bounds changed",
)
assert_policy(
  container["livenessProbe"] == {
    "httpGet" => {"path" => "/health/live", "port" => "http"},
    "periodSeconds" => 30,
    "timeoutSeconds" => 3,
    "failureThreshold" => 3,
  },
  "livenessProbe safety bounds changed",
)

default_deny = find.call("NetworkPolicy", "jaiqal-default-deny")
assert_policy(default_deny&.dig("spec", "podSelector") == {}, "default-deny NetworkPolicy is missing")
assert_policy(
  Array(default_deny.dig("spec", "policyTypes")).sort == %w[Egress Ingress],
  "default-deny must cover ingress and egress",
)
allow_required = find.call("NetworkPolicy", "jaiqal-server-allow-required")
assert_policy(allow_required, "allow-required NetworkPolicy is missing")
assert_policy(
  allow_required.dig("spec", "podSelector", "matchLabels") == {"app.kubernetes.io/name" => "jaiqal-server"},
  "allow-required policy must select only server Pods",
)
assert_policy(
  Array(allow_required.dig("spec", "policyTypes")).sort == %w[Egress Ingress],
  "allow-required policy must cover ingress and egress",
)
ingress = Array(allow_required.dig("spec", "ingress"))
assert_policy(ingress.length == 1, "only trusted ingress rule is expected")
assert_policy(ingress.dig(0, "ports") == [{"protocol" => "TCP", "port" => 8080}], "ingress must expose only TCP/8080")
assert_policy(
  ingress.dig(0, "from", 0, "namespaceSelector", "matchLabels", "networking.jaiqal.io/ingress") == "true" &&
    ingress.dig(0, "from", 0, "podSelector", "matchLabels", "app.kubernetes.io/component") == "ingress-controller",
  "ingress selector must remain restricted to the labelled controller",
)

egress = Array(allow_required.dig("spec", "egress"))
assert_policy(egress.length == 3, "egress must be limited to DNS, PostgreSQL, and proxy")
assert_policy(egress.none? { |rule| Array(rule["to"]).any? { |target| target.key?("ipBlock") } }, "CIDR-wide egress is forbidden")
dns = egress.find { |rule| rule.dig("to", 0, "namespaceSelector", "matchLabels", "kubernetes.io/metadata.name") == "kube-system" }
assert_policy(dns&.dig("to", 0, "podSelector", "matchLabels", "k8s-app") == "kube-dns", "DNS egress selector changed")
assert_policy(Array(dns&.dig("ports")).sort_by { |port| port["protocol"] } == [{"protocol" => "TCP", "port" => 53}, {"protocol" => "UDP", "port" => 53}], "DNS egress must be TCP/UDP 53")
database = egress.find { |rule| rule.dig("to", 0, "namespaceSelector", "matchLabels", "networking.jaiqal.io/database") == "true" }
assert_policy(database&.dig("to", 0, "podSelector", "matchLabels", "app.kubernetes.io/name") == "postgresql", "database egress selector changed")
assert_policy(database&.dig("ports") == [{"protocol" => "TCP", "port" => 5432}], "database egress must be TCP/5432")
proxy = egress.find { |rule| rule.dig("to", 0, "namespaceSelector", "matchLabels", "networking.jaiqal.io/egress") == "true" }
assert_policy(proxy&.dig("to", 0, "podSelector", "matchLabels", "app.kubernetes.io/name") == "jaiqal-egress-proxy", "proxy egress selector changed")
assert_policy(proxy&.dig("ports") == [{"protocol" => "TCP", "port" => 8443}], "proxy egress must be TCP/8443")

kubelet = YAML.load_file(kubelet_path)
pids_limit = kubelet["podPidsLimit"]
assert_policy(pids_limit == 256, "podPidsLimit changed without review")

puts "Production runtime policy is structurally hardened"
RUBY
