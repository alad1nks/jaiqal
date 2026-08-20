#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [--apply] [owner/repository] [branch]" >&2
}

mode=check
if [[ "${1:-}" == "--apply" ]]; then
  mode=apply
  shift
fi
if (( $# > 2 )); then
  usage
  exit 2
fi

repository="${1:-alad1nks/jaiqal}"
branch="${2:-main}"
if [[ ! "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
  [[ ! "$branch" =~ ^[A-Za-z0-9._/-]+$ ]]; then
  usage
  exit 2
fi

command -v gh >/dev/null || {
  echo "GitHub CLI is required" >&2
  exit 1
}
gh auth status >/dev/null

api_version=2026-03-10
api() {
  gh api -H "X-GitHub-Api-Version: $api_version" "$@"
}

if [[ "$mode" == "apply" ]]; then
  remote_ci=$(api -H 'Accept: application/vnd.github.raw+json' "repos/$repository/contents/.github/workflows/ci.yml?ref=$branch")
  for job in security-guard-self-tests verification sast supply-chain dependency-review; do
    grep -Fq "  $job:" <<<"$remote_ci" || {
      echo "Refusing to protect $branch before CI job '$job' exists on that branch" >&2
      exit 1
    }
  done
  api "repos/$repository/contents/.github/CODEOWNERS?ref=$branch" >/dev/null || {
    echo "Refusing to require code-owner review before CODEOWNERS exists on $branch" >&2
    exit 1
  }

  api --method PUT "repos/$repository/branches/$branch/protection" --input - >/dev/null <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "checks": [
      {"context": "verification"},
      {"context": "sast"},
      {"context": "supply-chain"},
      {"context": "dependency-review"}
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismissal_restrictions": {},
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 1,
    "require_last_push_approval": true,
    "bypass_pull_request_allowances": {}
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": true,
  "lock_branch": false,
  "allow_fork_syncing": false
}
JSON

  api --method PATCH "repos/$repository" --input - >/dev/null <<'JSON'
{
  "security_and_analysis": {
    "secret_scanning": {"status": "enabled"},
    "secret_scanning_push_protection": {"status": "enabled"}
  }
}
JSON
fi

protection=$(api "repos/$repository/branches/$branch/protection")
repository_settings=$(api "repos/$repository")

ruby -rjson -e '
  protection = JSON.parse(ARGV.fetch(0))
  expected_checks = %w[dependency-review sast supply-chain verification]
  actual_checks = protection.dig("required_status_checks", "contexts")&.sort
  failures = []
  failures << "required checks" unless actual_checks == expected_checks
  failures << "strict checks" unless protection.dig("required_status_checks", "strict") == true
  failures << "administrator enforcement" unless protection.dig("enforce_admins", "enabled") == true
  reviews = protection["required_pull_request_reviews"] || {}
  failures << "code-owner review" unless reviews["require_code_owner_reviews"] == true
  failures << "stale review dismissal" unless reviews["dismiss_stale_reviews"] == true
  failures << "last-push approval" unless reviews["require_last_push_approval"] == true
  failures << "approving review count" unless reviews["required_approving_review_count"] == 1
  failures << "force-push prohibition" unless protection.dig("allow_force_pushes", "enabled") == false
  failures << "branch deletion prohibition" unless protection.dig("allow_deletions", "enabled") == false
  abort "GitHub branch protection differs: #{failures.join(", ")}" unless failures.empty?
' "$protection"

ruby -rjson -e '
  repository = JSON.parse(ARGV.fetch(0))
  security = repository["security_and_analysis"] || {}
  failures = []
  failures << "secret scanning" unless security.dig("secret_scanning", "status") == "enabled"
  failures << "push protection" unless security.dig("secret_scanning_push_protection", "status") == "enabled"
  abort "GitHub repository security differs: #{failures.join(", ")}" unless failures.empty?
' "$repository_settings"

echo "GitHub branch and repository security settings match P3.14"
