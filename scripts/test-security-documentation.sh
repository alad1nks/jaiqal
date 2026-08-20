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
guard = File.join(root, "scripts/verify-security-documentation.sh")

def run_guard(guard, fixture, expected)
  _, stderr, status = Open3.capture3("bash", guard, fixture)
  abort "Security documentation guard expectation failed: #{stderr}" unless status.success? == expected
end

Dir.mktmpdir("jaiqal-security-docs-") do |temporary|
  copy = lambda do |destination|
    FileUtils.mkdir_p(File.join(destination, "docs"))
    %w[security-audit.md security-operations-runbook.md].each do |name|
      FileUtils.cp(File.join(root, "docs", name), File.join(destination, "docs", name))
    end
  end

  positive = File.join(temporary, "positive")
  copy.call(positive)
  run_guard(guard, positive, true)

  mutations = {
    "missing operator block" => ["## OPS-09 —", "## REMOVED-09 —", "security-operations-runbook.md"],
    "missing hosted control" => ["secret scanning и push protection", "optional scanning", "security-operations-runbook.md"],
    "reopened code backlog" => ["Кодовый backlog: пуст.", "Кодовый backlog: открыт.", "security-audit.md"],
    "mixed partial status" => ["Кодовый backlog: пуст.", "Кодовый backlog: пуст. Частично выполнено.", "security-audit.md"],
  }
  mutations.each do |name, (before, after, filename)|
    fixture = File.join(temporary, name.tr(" ", "-"))
    copy.call(fixture)
    path = File.join(fixture, "docs", filename)
    content = File.read(path)
    abort "Mutation target is absent: #{before}" unless content.include?(before)
    File.write(path, content.sub(before, after))
    run_guard(guard, fixture, false)
  end
end

puts "Security documentation positive/negative self-tests passed"
RUBY
