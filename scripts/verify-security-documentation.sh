#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
  echo "Usage: $0 [repository-root]" >&2
  exit 2
fi

repo_root="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
audit="$repo_root/docs/security-audit.md"
runbook="$repo_root/docs/security-operations-runbook.md"

[[ -f "$audit" ]] || { echo "Security audit is missing" >&2; exit 1; }
[[ -f "$runbook" ]] || { echo "Security operations runbook is missing" >&2; exit 1; }

for number in $(seq -w 1 10); do
  grep -Fq "## OPS-$number —" "$runbook" || {
    echo "Security operations runbook is missing OPS-$number" >&2
    exit 1
  }
done

required_runbook_controls=(
  ':core:api-contract:allTests'
  'verification`, `sast`, `supply-chain`'
  'secret scanning и push protection'
  'TRUSTED_PROXY_CIDRS'
  'FIREBASE_CHECK_REVOKED_TOKENS=true'
  'sslmode=verify-full'
  'staging-security'
  'production-signing'
  'failurePolicy: Fail'
  'collector-export-failures'
  'append-only'
)
for control in "${required_runbook_controls[@]}"; do
  grep -Fq -- "$control" "$runbook" || {
    echo "Security operations runbook is missing control: $control" >&2
    exit 1
  }
done

grep -Fq 'Кодовый backlog: пуст.' "$audit" || {
  echo "Security audit must explicitly close the code backlog" >&2
  exit 1
}
grep -Fq '[`security-operations-runbook.md`](security-operations-runbook.md)' "$audit" || {
  echo "Security audit must link the operator runbook" >&2
  exit 1
}
if grep -Fiq 'частично выполнено' "$audit"; then
  echo "Mixed repository/operator status remains in security-audit.md" >&2
  exit 1
fi

echo "Security audit and operator runbook are cleanly separated"
