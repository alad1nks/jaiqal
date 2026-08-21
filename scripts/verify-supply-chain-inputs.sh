#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
  echo "Usage: $0 [repository-root]" >&2
  exit 2
fi

repo_root="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
failures=0

ci_step_has_setting() {
  local step_name="$1"
  local expected_setting="$2"
  awk -v step_name="$step_name" -v expected_setting="$expected_setting" '
    $0 == "      - name: " step_name {
      in_step = 1
      saw_step = 1
      next
    }
    in_step && $0 ~ /^      - name:/ {
      in_step = 0
    }
    in_step && index($0, expected_setting) > 0 {
      found_setting = 1
    }
    END {
      exit !(saw_step && found_setting)
    }
  ' "$repo_root/.github/workflows/ci.yml"
}

ci_has_exact_line() {
  local expected="$1"
  grep -Fqx "$expected" "$repo_root/.github/workflows/ci.yml"
}

all_checkout_steps_disable_credentials() {
  awk '
    /^[[:space:]]*uses:[[:space:]]*actions\/checkout@/ {
      if (in_checkout && !disabled) {
        exit 1
      }
      in_checkout = 1
      disabled = 0
      checkout_count++
      next
    }
    in_checkout && /^[[:space:]]*uses:/ {
      if (!disabled) {
        exit 1
      }
      in_checkout = 0
    }
    in_checkout && /^[[:space:]]*persist-credentials:[[:space:]]*false([[:space:]]*#.*)?$/ {
      disabled = 1
    }
    END {
      exit !(checkout_count > 0 && (!in_checkout || disabled))
    }
  ' "$repo_root/.github/workflows/ci.yml"
}

while IFS= read -r reference; do
  if [[ ! "$reference" =~ ^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+@[0-9a-f]{40}$ ]]; then
    echo "Mutable or invalid GitHub Action reference: $reference" >&2
    failures=$((failures + 1))
  fi
done < <(find "$repo_root/.github/workflows" -type f \( -name '*.yml' -o -name '*.yaml' \) -exec sed -nE 's/^[[:space:]]*uses:[[:space:]]*([^[:space:]#]+).*$/\1/p' {} +)

if ! all_checkout_steps_disable_credentials; then
  echo "Every checkout step must disable persisted GitHub credentials" >&2
  failures=$((failures + 1))
fi

if ! ci_has_exact_line '  dependency-review:' ||
  ! ci_has_exact_line "    if: github.event_name == 'pull_request'" ||
  ! ci_step_has_setting 'Review dependency changes' 'uses: actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294' ||
  ! ci_step_has_setting 'Review dependency changes' 'fail-on-severity: moderate'; then
  echo "Pinned fail-closed pull-request dependency review is missing" >&2
  failures=$((failures + 1))
fi

codeowners="$repo_root/.github/CODEOWNERS"
required_codeowner_rules=(
  '/.github/CODEOWNERS @alad1nks'
  '/.github/workflows/ @alad1nks'
  '/server/src/main/kotlin/com/alad1nks/jaiqal/auth/ @alad1nks'
  '/server/src/main/kotlin/com/alad1nks/jaiqal/infrastructure/security/ @alad1nks'
  '/server/src/main/resources/db/migration/ @alad1nks'
  '/deploy/ @alad1nks'
  '/scripts/ @alad1nks'
  '/docs/security*.md @alad1nks'
)
if [[ ! -f "$codeowners" ]]; then
  echo "CODEOWNERS is missing" >&2
  failures=$((failures + 1))
else
  for rule in "${required_codeowner_rules[@]}"; do
    if ! grep -Fqx "$rule" "$codeowners"; then
      echo "Required CODEOWNERS rule is missing: $rule" >&2
      failures=$((failures + 1))
    fi
  done
fi

github_settings_script="$repo_root/scripts/configure-github-security.sh"
required_github_settings=(
  '{"context": "verification"}'
  '{"context": "sast"}'
  '{"context": "supply-chain"}'
  '{"context": "dependency-review"}'
  '"enforce_admins": true'
  '"require_code_owner_reviews": true'
  '"allow_force_pushes": false'
  '"secret_scanning": {"status": "enabled"}'
  '"secret_scanning_push_protection": {"status": "enabled"}'
)
if [[ ! -f "$github_settings_script" ]]; then
  echo "GitHub security settings verifier is missing" >&2
  failures=$((failures + 1))
else
  for setting in "${required_github_settings[@]}"; do
    if ! grep -Fq "$setting" "$github_settings_script"; then
      echo "Required GitHub security setting is missing: $setting" >&2
      failures=$((failures + 1))
    fi
  done
fi

dast_workflow="$repo_root/.github/workflows/staging-dast.yml"
dast_script="$repo_root/scripts/staging-dast.sh"
dast_bootstrap="$repo_root/scripts/run-staging-dast.sh"
dast_self_test="$repo_root/scripts/test-staging-dast.sh"
required_dast_workflow_settings=(
  '  workflow_call:'
  '  workflow_dispatch:'
  '  schedule:'
  '  contents: read'
  '    timeout-minutes: 15'
  '    environment: staging-security'
  '          persist-credentials: false'
  '        run: bash scripts/run-staging-dast.sh'
  '      DAST_EXPECTED_COMMIT: ${{ inputs.expected_commit || vars.STAGING_EXPECTED_COMMIT }}'
)
required_dast_checks=(
  'DAST-TLS-001'
  'DAST-COMMIT-001'
  'DAST-PROXY-001'
  'DAST-CORS-001'
  'DAST-CORS-002'
  'DAST-AUTH-001'
  'DAST-OWNERSHIP-001'
  'DAST-JSON-001'
  'DAST-DEVICE-AUTH-001'
  'DAST-BODY-001'
  'DAST-RATE-001'
  'DAST-SSE-001'
  "--proto '=https'"
  '--disable'
  '(( sse_max_lifetime >= 1 && sse_max_lifetime <= 60 ))'
)
if [[ ! -f "$dast_workflow" || ! -f "$dast_script" || ! -f "$dast_bootstrap" || ! -f "$dast_self_test" ]]; then
  echo "Staging DAST workflow or scripts are missing" >&2
  failures=$((failures + 1))
else
  for setting in "${required_dast_workflow_settings[@]}"; do
    if ! grep -Fq "$setting" "$dast_workflow"; then
      echo "Required staging DAST workflow setting is missing: $setting" >&2
      failures=$((failures + 1))
    fi
  done
  if grep -Fq 'actions/upload-artifact' "$dast_workflow"; then
    echo "Staging DAST must not upload credential-adjacent artifacts" >&2
    failures=$((failures + 1))
  fi
  for check in "${required_dast_checks[@]}"; do
    if ! grep -Fq -- "$check" "$dast_script"; then
      echo "Required staging DAST check is missing: $check" >&2
      failures=$((failures + 1))
    fi
  done
  if ! grep -Fq 'https://securetoken.googleapis.com/v1/token' "$dast_bootstrap" ||
    ! grep -Fq 'unset DAST_FIREBASE_WEB_API_KEY DAST_USER_REFRESH_TOKEN DAST_OTHER_USER_REFRESH_TOKEN' "$dast_bootstrap" ||
    grep -Eq 'set[[:space:]]+-[^[:space:]]*x' "$dast_bootstrap" "$dast_script"; then
    echo "Safe staging DAST credential bootstrap is missing or trace logging is enabled" >&2
    failures=$((failures + 1))
  fi
fi

while IFS= read -r reference; do
  if [[ ! "$reference" =~ ^[^@[:space:]]+@sha256:[0-9a-f]{64}$ ]]; then
    echo "Mutable or invalid Dockerfile base image: $reference" >&2
    failures=$((failures + 1))
  fi
done < <(sed -nE 's/^FROM[[:space:]]+([^[:space:]]+).*$/\1/p' "$repo_root/server/Dockerfile")

while IFS= read -r project_root; do
  if ! grep -Eq "^COPY[[:space:]]+${project_root}[[:space:]]+${project_root}([[:space:]]|$)" "$repo_root/server/Dockerfile"; then
    echo "Docker build stage is missing Gradle project root: $project_root" >&2
    failures=$((failures + 1))
  fi
done < <(sed -nE 's/^[[:space:]]*include\(\":([^:\"]+).*$/\1/p' "$repo_root/settings.gradle.kts" | sort -u)

while IFS= read -r reference; do
  if [[ ! "$reference" =~ ^[^@[:space:]]+@sha256:[0-9a-f]{64}$ ]]; then
    echo "Mutable or invalid Compose image: $reference" >&2
    failures=$((failures + 1))
  fi
done < <(sed -nE 's/^[[:space:]]*image:[[:space:]]*([^[:space:]#]+).*$/\1/p' "$repo_root"/compose*.yaml)

if [[ ! -f "$repo_root/gradle/verification-metadata.xml" ]]; then
  echo "Gradle dependency verification metadata is missing" >&2
  failures=$((failures + 1))
fi

if [[ ! -f "$repo_root/gradle/wrapper/gradle-wrapper.properties" ]] ||
  ! grep -Eq '^distributionSha256Sum=[0-9a-f]{64}$' "$repo_root/gradle/wrapper/gradle-wrapper.properties"; then
  echo "Gradle wrapper SHA-256 pin is missing or invalid" >&2
  failures=$((failures + 1))
fi

if ! ci_step_has_setting 'Scan checkout for committed secrets' 'version: v0.74.0' ||
  ! ci_step_has_setting 'Scan checkout for committed secrets' 'scanners: secret' ||
  ! ci_step_has_setting 'Scan checkout for committed secrets' 'severity: UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL' ||
  ! ci_step_has_setting 'Scan checkout for committed secrets' 'exit-code: "1"'; then
  echo "Fail-closed repository secret scan is missing" >&2
  failures=$((failures + 1))
fi

if ! ci_step_has_setting 'Scan repository security configuration' 'version: v0.74.0' ||
  ! ci_step_has_setting 'Scan repository security configuration' 'scanners: misconfig' ||
  ! ci_step_has_setting 'Scan repository security configuration' 'severity: CRITICAL,HIGH' ||
  ! ci_step_has_setting 'Scan repository security configuration' 'exit-code: "1"'; then
  echo "Fail-closed repository misconfiguration scan is missing" >&2
  failures=$((failures + 1))
fi

if ! ci_step_has_setting 'Initialize CodeQL for Java and Kotlin' 'uses: github/codeql-action/init@e4fba868fa4b1b91e1fdab776edc8cfbe6e9fb81' ||
  ! ci_step_has_setting 'Initialize CodeQL for Java and Kotlin' 'languages: java-kotlin' ||
  ! ci_step_has_setting 'Initialize CodeQL for Java and Kotlin' 'build-mode: manual' ||
  ! ci_step_has_setting 'Initialize CodeQL for Java and Kotlin' 'queries: security-extended' ||
  ! ci_step_has_setting 'Build server classes for CodeQL' 'run: ./gradlew --no-daemon --no-build-cache --rerun-tasks :server:clean :server:classes' ||
  ! ci_step_has_setting 'Analyze Java and Kotlin' 'uses: github/codeql-action/analyze@e4fba868fa4b1b91e1fdab776edc8cfbe6e9fb81'; then
  echo "Pinned fail-closed Java/Kotlin CodeQL analysis is missing" >&2
  failures=$((failures + 1))
fi

if ! ci_step_has_setting 'Verify production runtime policy' 'run: bash scripts/verify-runtime-policy.sh'; then
  echo "Fail-closed production runtime policy verification is missing" >&2
  failures=$((failures + 1))
fi

if [[ ! -f "$repo_root/deploy/observability/security-observability-policy.yaml" ]] ||
  [[ ! -f "$repo_root/scripts/verify-security-observability-policy.sh" ]] ||
  [[ ! -f "$repo_root/scripts/test-security-observability-policy.sh" ]] ||
  ! ci_step_has_setting 'Verify security observability policy' 'run: bash scripts/verify-security-observability-policy.sh'; then
  echo "Fail-closed security observability policy verification is missing" >&2
  failures=$((failures + 1))
fi

if [[ ! -f "$repo_root/docs/security-audit.md" ]] ||
  [[ ! -f "$repo_root/docs/security-operations-runbook.md" ]] ||
  [[ ! -f "$repo_root/scripts/verify-security-documentation.sh" ]] ||
  [[ ! -f "$repo_root/scripts/test-security-documentation.sh" ]] ||
  ! ci_step_has_setting 'Verify security audit documentation' 'run: bash scripts/verify-security-documentation.sh'; then
  echo "Security audit/operator separation guard is missing" >&2
  failures=$((failures + 1))
fi

if [[ ! -f "$repo_root/.github/workflows/publish-production-image.yml" ]] ||
  [[ ! -f "$repo_root/scripts/verify-image-release-policy.sh" ]] ||
  ! ci_step_has_setting 'Verify production image release policy' 'run: bash scripts/verify-image-release-policy.sh'; then
  echo "Fail-closed production image release policy verification is missing" >&2
  failures=$((failures + 1))
fi

if ! ci_step_has_setting 'Verify Firebase Storage runtime graph is absent' 'run: ./gradlew --no-daemon :server:verifyFirebaseStorageRuntimeGraph'; then
  echo "Firebase Storage runtime regression guard is missing" >&2
  failures=$((failures + 1))
fi

if [[ ! -f "$repo_root/scripts/trivy-medium-baseline.txt" ]] ||
  [[ ! -f "$repo_root/scripts/verify-medium-vulnerability-baseline.sh" ]] ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'version: v0.74.0' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'scan-type: rootfs' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'scan-ref: server/build/install/server' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'scanners: vuln' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'severity: MEDIUM' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'ignore-unfixed: "false"' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'format: json' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'output: trivy-medium.json' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'exit-code: "0"' ||
  ! ci_step_has_setting 'Capture Medium server runtime findings' 'timeout: 10m0s' ||
  ! ci_step_has_setting 'Reject new Medium server runtime findings' 'run: bash scripts/verify-medium-vulnerability-baseline.sh trivy-medium.json'; then
  echo "Reviewed Medium runtime vulnerability regression gate is missing" >&2
  failures=$((failures + 1))
fi

if [[ ! -f "$repo_root/scripts/test-security-guards.sh" ]] ||
  ! ci_step_has_setting 'Run security guard self-tests' 'run: bash scripts/test-security-guards.sh' ||
  ! ci_has_exact_line '  security-guard-self-tests:' ||
  [[ $(grep -Fc '    needs: security-guard-self-tests' "$repo_root/.github/workflows/ci.yml") -ne 4 ]]; then
  echo "Security guard self-tests must gate every consuming CI job" >&2
  failures=$((failures + 1))
fi

if (( failures > 0 )); then
  exit 1
fi

echo "Supply-chain inputs are immutably pinned"
