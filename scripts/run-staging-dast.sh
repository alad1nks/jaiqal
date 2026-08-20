#!/usr/bin/env bash
set -euo pipefail

if (( $# > 0 )); then
  echo "Usage: $0" >&2
  exit 2
fi

for name in DAST_FIREBASE_WEB_API_KEY DAST_USER_REFRESH_TOKEN DAST_OTHER_USER_REFRESH_TOKEN; do
  if [[ -z "${!name:-}" ]]; then
    echo "DAST token bootstrap configuration is incomplete: $name is required" >&2
    exit 2
  fi
done
if [[ ! "$DAST_FIREBASE_WEB_API_KEY" =~ ^[A-Za-z0-9_-]{16,256}$ ]]; then
  echo "DAST_FIREBASE_WEB_API_KEY format is invalid" >&2
  exit 2
fi

umask 077
temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/jaiqal-dast-token-bootstrap.XXXXXX")
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT HUP INT TERM

mint_id_token() {
  local refresh_token="$1"
  local label="$2"
  local refresh_file="$temporary_root/$label.refresh"
  local response_file="$temporary_root/$label.response"
  local curl_config="$temporary_root/$label.curl"
  printf '%s' "$refresh_token" >"$refresh_file"
  printf 'url = "https://securetoken.googleapis.com/v1/token?key=%s"\n' "$DAST_FIREBASE_WEB_API_KEY" >"$curl_config"
  local status
  if ! status=$(curl \
    --disable \
    --silent --show-error \
    --proto '=https' \
    --tlsv1.2 \
    --connect-timeout 10 \
    --max-time 30 \
    --config "$curl_config" \
    --request POST \
    --header 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=refresh_token' \
    --data-urlencode "refresh_token@$refresh_file" \
    --output "$response_file" \
    --write-out '%{http_code}'); then
    echo "DAST Firebase token bootstrap transport failed for $label" >&2
    return 1
  fi
  [[ "$status" == "200" ]] || {
    echo "DAST Firebase token bootstrap returned HTTP $status for $label" >&2
    return 1
  }
  ruby -rjson -e '
    token = JSON.parse(File.read(ARGV.fetch(0)))["id_token"]
    abort unless token.is_a?(String) && token.match?(/\A[A-Za-z0-9._~-]{16,8192}\z/)
    print token
  ' "$response_file" 2>/dev/null || {
    echo "DAST Firebase token bootstrap returned an invalid response for $label" >&2
    return 1
  }
}

DAST_USER_TOKEN=$(mint_id_token "$DAST_USER_REFRESH_TOKEN" user)
DAST_OTHER_USER_TOKEN=$(mint_id_token "$DAST_OTHER_USER_REFRESH_TOKEN" other-user)
export DAST_USER_TOKEN DAST_OTHER_USER_TOKEN
unset DAST_FIREBASE_WEB_API_KEY DAST_USER_REFRESH_TOKEN DAST_OTHER_USER_REFRESH_TOKEN

bash "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/staging-dast.sh"
