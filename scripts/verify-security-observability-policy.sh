#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
  echo "Usage: $0 [repository-root]" >&2
  exit 2
fi

repo_root="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"

ruby - "$repo_root" <<'RUBY'
require "yaml"

def require_control(condition, message)
  abort("Security observability policy violation: #{message}") unless condition
end

root = ARGV.fetch(0)
path = File.join(root, "deploy/observability/security-observability-policy.yaml")
require_control(File.file?(path), "policy manifest is missing")
policy = YAML.safe_load(File.read(path), aliases: false)
spec = policy.fetch("spec", {})

require_control(policy["apiVersion"] == "observability.jaiqal.io/v1alpha1", "apiVersion changed")
require_control(policy["kind"] == "SecurityObservabilityPolicy", "kind changed")
require_control(spec["schemaVersion"] == 1, "schema version must remain 1")
ingestion = spec.fetch("ingestion", {})
require_control(ingestion["source"] == "kubernetes-stdout", "production source must remain Kubernetes stdout")
require_control(ingestion["messageEncoding"] == "json", "security payloads must remain JSON")
require_control(Array(ingestion["eventTypes"]).sort == %w[SECURITY_AUDIT SECURITY_CAPACITY_ALERT], "required event types changed")

storage = spec.fetch("storage", {})
require_control(storage["appendOnly"] == true, "storage must be append-only")
require_control(storage["immutableRetention"] == true, "retention must be immutable")
require_control(storage["retentionDays"].to_i >= 365, "retention must be at least 365 days")
require_control(storage["writerCanRead"] == false, "ingestion identity must not read audit data")
require_control(Array(storage["readerGroups"]) == ["security-incident-response"], "read access must stay restricted")
require_control(storage["exportRequiresApproval"] == true && storage["exportAuditLog"] == true, "exports must be approved and audited")

correlation = spec.fetch("correlation", {})
require_control(correlation == {"field" => "requestId", "pattern" => "^[A-Za-z0-9._-]{1,128}$"}, "safe requestId correlation contract changed")
forbidden = Array(spec["forbiddenFields"])
%w[authorization rawToken claimCode firebaseUid email requestBody].each do |field|
  require_control(forbidden.include?(field), "forbidden field #{field} is missing")
end

alerts = Array(spec["alerts"])
alerts_by_id = alerts.to_h { |alert| [alert["id"], alert] }
required_alerts = {
  "authentication-rejection-burst" => ["SECURITY_AUDIT", "AUTHENTICATION", ["REJECTED"]],
  "rate-limit-burst" => ["SECURITY_AUDIT", "RATE_LIMIT", ["REJECTED"]],
  "device-quarantine-transition" => ["SECURITY_AUDIT", "QUARANTINE_DEVICE", ["SUCCESS"]],
  "user-provisioning-anomaly" => ["SECURITY_AUDIT", "PROVISION_USER", %w[REJECTED FAILURE]],
  "database-capacity-threshold" => ["SECURITY_CAPACITY_ALERT", nil, nil],
}
required_alerts.each do |id, (event_type, action, results)|
  alert = alerts_by_id[id]
  require_control(alert, "required alert #{id} is missing")
  require_control(alert["eventType"] == event_type, "#{id} event type changed")
  require_control(alert["action"] == action, "#{id} action changed") if action
  require_control(Array(alert["results"]).sort == results.sort, "#{id} results changed") if results
  require_control(alert["threshold"].to_i > 0, "#{id} threshold must be positive")
  require_control(alert["windowSeconds"].to_i.between?(1, 3600), "#{id} window must be bounded")
  require_control(alert["deduplicationSeconds"].to_i >= alert["windowSeconds"].to_i, "#{id} must be deduplicated")
end
delivery = alerts_by_id["audit-delivery-failure"]
require_control(delivery && delivery["signal"] == "collector-export-failures" && delivery["threshold"] == 1, "delivery failure must page on the first failure")
require_control(delivery["windowSeconds"].to_i <= 300, "delivery failure detection must take at most five minutes")

tests = spec.fetch("acceptanceTests", {})
require_control(Array(tests["generateActions"]).sort == %w[AUTHENTICATION PROVISION_USER QUARANTINE_DEVICE RATE_LIMIT].sort, "signal generation matrix is incomplete")
require_control(Array(tests["generateEventTypes"]).sort == %w[SECURITY_AUDIT SECURITY_CAPACITY_ALERT], "event generation matrix is incomplete")
%w[simulateDeliveryFailure verifyEveryAlert queryByRequestId verifyForbiddenFieldsAbsent].each do |control|
  require_control(tests[control] == true, "acceptance test #{control} must be mandatory")
end

audit_source = File.read(File.join(root, "server/src/main/kotlin/com/alad1nks/jaiqal/infrastructure/security/SecurityAuditTrail.kt"))
capacity_source = File.read(File.join(root, "server/src/main/kotlin/com/alad1nks/jaiqal/infrastructure/database/DatabaseCapacityMonitor.kt"))
event_source = File.read(File.join(root, "server/src/main/kotlin/com/alad1nks/jaiqal/infrastructure/security/SecurityEventLog.kt"))
require_control(audit_source.include?("PROVISION_USER"), "provisioning signal is absent from server schema")
require_control(audit_source.include?('eventType = "SECURITY_AUDIT"'), "audit logger is not structured")
require_control(capacity_source.include?('eventType = "SECURITY_CAPACITY_ALERT"'), "capacity logger is not structured")
require_control(event_source.include?("SECURITY_EVENT_SCHEMA_VERSION = 1"), "server event schema version differs from policy")

puts "Security observability policy is complete and structurally enforced"
RUBY
