#!/usr/bin/env bash
set -euo pipefail

if (( $# > 0 )); then
  echo "Usage: $0" >&2
  exit 2
fi

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

ruby - "$repo_root" <<'RUBY'
require "fileutils"
require "open3"
require "tmpdir"

root = ARGV.fetch(0)
guard = File.join(root, "scripts/verify-security-observability-policy.sh")
files = %w[
  deploy/observability/security-observability-policy.yaml
  server/src/main/kotlin/com/alad1nks/jaiqal/infrastructure/security/SecurityAuditTrail.kt
  server/src/main/kotlin/com/alad1nks/jaiqal/infrastructure/security/SecurityEventLog.kt
  server/src/main/kotlin/com/alad1nks/jaiqal/infrastructure/database/DatabaseCapacityMonitor.kt
]

def run_guard(guard, fixture, expected)
  _, stderr, status = Open3.capture3("bash", guard, fixture)
  abort "Observability guard expectation failed: #{stderr}" unless status.success? == expected
end

Dir.mktmpdir("jaiqal-observability-policy-") do |temporary|
  copy = lambda do |destination|
    files.each do |relative|
      target = File.join(destination, relative)
      FileUtils.mkdir_p(File.dirname(target))
      FileUtils.cp(File.join(root, relative), target)
    end
  end
  positive = File.join(temporary, "positive")
  copy.call(positive)
  run_guard(guard, positive, true)

  mutations = {
    "mutable storage" => ["appendOnly: true", "appendOnly: false"],
    "short retention" => ["retentionDays: 365", "retentionDays: 30"],
    "reader expansion" => ["security-incident-response", "all-developers"],
    "missing redaction" => ["    - firebaseUid\n", ""],
    "provisioning alert disabled" => ["action: PROVISION_USER", "action: LOGIN_USER"],
    "delivery fail open" => ["signal: collector-export-failures", "signal: none"],
    "delivery test skipped" => ["simulateDeliveryFailure: true", "simulateDeliveryFailure: false"],
    "unsafe correlation" => ["field: requestId", "field: firebaseUid"],
  }
  mutations.each do |name, (before, after)|
    fixture = File.join(temporary, name.tr(" ", "-"))
    copy.call(fixture)
    path = File.join(fixture, "deploy/observability/security-observability-policy.yaml")
    content = File.read(path)
    abort "Mutation target is absent: #{before}" unless content.include?(before)
    File.write(path, content.sub(before, after))
    run_guard(guard, fixture, false)
  end
end

puts "Security observability policy positive/negative self-tests passed"
RUBY
