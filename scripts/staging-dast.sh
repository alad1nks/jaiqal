#!/usr/bin/env bash
set -euo pipefail

if (( $# > 0 )); then
  echo "Usage: $0" >&2
  exit 2
fi

required_variables=(
  DAST_BASE_URL
  DAST_ALLOWED_ORIGIN
  DAST_EXPECTED_COMMIT
  DAST_USER_TOKEN
  DAST_OTHER_USER_TOKEN
  DAST_USER_PLANT_ID
  DAST_OTHER_USER_PLANT_ID
  DAST_DEVICE_TOKEN
)
for name in "${required_variables[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "DAST configuration is incomplete: $name is required" >&2
    exit 2
  fi
done

if [[ ! "$DAST_EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
  echo "DAST_EXPECTED_COMMIT must be a full lowercase Git commit SHA" >&2
  exit 2
fi
for id in "$DAST_USER_PLANT_ID" "$DAST_OTHER_USER_PLANT_ID"; do
  if [[ ! "$id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]; then
    echo "DAST plant IDs must be canonical lowercase UUIDs" >&2
    exit 2
  fi
done
for token in "$DAST_USER_TOKEN" "$DAST_OTHER_USER_TOKEN" "$DAST_DEVICE_TOKEN"; do
  if (( ${#token} < 16 || ${#token} > 8192 )) || [[ ! "$token" =~ ^[A-Za-z0-9._~-]+$ ]]; then
    echo "DAST credential format is invalid" >&2
    exit 2
  fi
done

ruby -ruri -e '
  base = URI(ARGV.fetch(0))
  origin = URI(ARGV.fetch(1))
  [base, origin].each do |uri|
    abort "DAST URLs must be credential-free HTTPS origins" unless
      uri.scheme == "https" && uri.host && !uri.userinfo && !uri.query && !uri.fragment &&
      (uri.path.nil? || uri.path.empty? || uri.path == "/")
  end
' "$DAST_BASE_URL" "$DAST_ALLOWED_ORIGIN"

base_url="${DAST_BASE_URL%/}"
rate_limit_attempts="${DAST_RATE_LIMIT_ATTEMPTS:-80}"
max_body_bytes="${DAST_MAX_BODY_BYTES:-65536}"
sse_max_lifetime="${DAST_SSE_MAX_LIFETIME_SECONDS:-15}"
[[ "$rate_limit_attempts" =~ ^[0-9]+$ ]] && (( rate_limit_attempts >= 2 && rate_limit_attempts <= 200 )) || {
  echo "DAST_RATE_LIMIT_ATTEMPTS must be between 2 and 200" >&2
  exit 2
}
[[ "$max_body_bytes" =~ ^[0-9]+$ ]] && (( max_body_bytes >= 1024 && max_body_bytes <= 1048576 )) || {
  echo "DAST_MAX_BODY_BYTES must be between 1024 and 1048576" >&2
  exit 2
}
[[ "$sse_max_lifetime" =~ ^[0-9]+$ ]] && (( sse_max_lifetime >= 1 && sse_max_lifetime <= 60 )) || {
  echo "DAST_SSE_MAX_LIFETIME_SECONDS must be between 1 and 60" >&2
  exit 2
}

umask 077
temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/jaiqal-staging-dast.XXXXXX")
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT HUP INT TERM

user_config="$temporary_root/user.curl"
other_user_config="$temporary_root/other-user.curl"
device_config="$temporary_root/device.curl"
printf 'header = "Authorization: Bearer %s"\n' "$DAST_USER_TOKEN" >"$user_config"
printf 'header = "Authorization: Bearer %s"\n' "$DAST_OTHER_USER_TOKEN" >"$other_user_config"
printf 'header = "Authorization: Device %s"\n' "$DAST_DEVICE_TOKEN" >"$device_config"
unset DAST_USER_TOKEN DAST_OTHER_USER_TOKEN DAST_DEVICE_TOKEN token

response_headers="$temporary_root/headers"
response_body="$temporary_root/body"
LAST_STATUS=

fail() {
  echo "DAST security gate failed: $1" >&2
  exit 1
}

pass() {
  echo "PASS $1"
}

perform_request() {
  local method="$1"
  local path="$2"
  local config="$3"
  local data_file="$4"
  shift 4
  : >"$response_headers"
  : >"$response_body"
  local arguments=(
    --disable
    --silent --show-error
    --proto '=https'
    --tlsv1.2
    --connect-timeout 10
    --max-time 30
    --request "$method"
    --dump-header "$response_headers"
    --output "$response_body"
    --write-out '%{http_code}'
  )
  [[ "$config" == "-" ]] || arguments+=(--config "$config")
  [[ "$data_file" == "-" ]] || arguments+=(--data-binary "@$data_file")
  if ! LAST_STATUS=$(curl "${arguments[@]}" "$@" "$base_url$path"); then
    fail "request transport failed"
  fi
  [[ "$LAST_STATUS" =~ ^[0-9]{3}$ ]] || fail "invalid HTTP status"
  (( 10#$LAST_STATUS < 500 )) || fail "server returned $LAST_STATUS"
}

header_value() {
  local expected_name="$1"
  awk -v expected_name="$expected_name" '
    BEGIN { IGNORECASE = 1 }
    {
      line = $0
      sub(/\r$/, "", line)
      separator = index(line, ":")
      if (separator > 0 && tolower(substr(line, 1, separator - 1)) == tolower(expected_name)) {
        value = substr(line, separator + 1)
        sub(/^[[:space:]]+/, "", value)
      }
    }
    END { print value }
  ' "$response_headers"
}

assert_status() {
  local expected="$1"
  local check="$2"
  [[ "$LAST_STATUS" == "$expected" ]] || fail "$check expected HTTP $expected, got $LAST_STATUS"
}

assert_json_code() {
  local expected="$1"
  local check="$2"
  ruby -rjson -e '
    body = JSON.parse(File.read(ARGV.fetch(0)))
    abort unless body["code"] == ARGV.fetch(1) && body["requestId"].is_a?(String) && !body["requestId"].empty?
  ' "$response_body" "$expected" 2>/dev/null || fail "$check returned an unexpected ApiErrorResponse"
}

perform_request GET /health/live - -
assert_status 200 DAST-TLS-001
[[ "$(header_value Strict-Transport-Security)" == *"max-age="* ]] || fail "DAST-TLS-001 HSTS is missing"
[[ "$(header_value X-Deployment-Commit)" == "$DAST_EXPECTED_COMMIT" ]] || fail "DAST-COMMIT-001 deployed commit mismatch"
pass DAST-TLS-001/DAST-COMMIT-001

perform_request GET /health/live - - \
  --header 'X-Forwarded-Proto: http' \
  --header 'X-Forwarded-For: 127.0.0.1'
assert_status 200 DAST-PROXY-001
[[ "$(header_value X-Deployment-Commit)" == "$DAST_EXPECTED_COMMIT" ]] || fail "DAST-PROXY-001 bypassed the expected deployment"
pass DAST-PROXY-001

perform_request OPTIONS /api/v1/auth/me - - \
  --header "Origin: $DAST_ALLOWED_ORIGIN" \
  --header 'Access-Control-Request-Method: GET' \
  --header 'Access-Control-Request-Headers: Authorization'
assert_status 200 DAST-CORS-001
[[ "$(header_value Access-Control-Allow-Origin)" == "$DAST_ALLOWED_ORIGIN" ]] || fail "DAST-CORS-001 allowed origin mismatch"
perform_request OPTIONS /api/v1/auth/me - - \
  --header 'Origin: https://dast-denied.invalid' \
  --header 'Access-Control-Request-Method: GET' \
  --header 'Access-Control-Request-Headers: Authorization'
assert_status 403 DAST-CORS-002
[[ -z "$(header_value Access-Control-Allow-Origin)" ]] || fail "DAST-CORS-002 reflected a denied origin"
pass DAST-CORS-001/DAST-CORS-002

perform_request GET /api/v1/auth/me - - --header 'Authorization: Bearer definitely-invalid-dast-token'
assert_status 401 DAST-AUTH-001
assert_json_code UNAUTHORIZED DAST-AUTH-001
[[ "$(header_value Cache-Control)" == "no-store" ]] || fail "DAST-AUTH-001 sensitive response is cacheable"
[[ "$(header_value X-Content-Type-Options)" == "nosniff" ]] || fail "DAST-AUTH-001 nosniff header is missing"
pass DAST-AUTH-001

perform_request GET /api/v1/auth/me "$user_config" -
assert_status 200 DAST-AUTH-002
perform_request GET "/api/v1/plants/$DAST_OTHER_USER_PLANT_ID" "$other_user_config" -
assert_status 200 DAST-OWNERSHIP-FIXTURE-001
perform_request GET "/api/v1/plants/$DAST_OTHER_USER_PLANT_ID" "$user_config" -
assert_status 404 DAST-OWNERSHIP-001
assert_json_code NOT_FOUND DAST-OWNERSHIP-001
pass DAST-AUTH-002/DAST-OWNERSHIP-001

malformed_body="$temporary_root/malformed.json"
printf '{' >"$malformed_body"
perform_request POST /api/v1/plants "$user_config" "$malformed_body" --header 'Content-Type: application/json'
assert_status 400 DAST-JSON-001
assert_json_code INVALID_JSON DAST-JSON-001
perform_request POST /api/device/v1/measurements "$device_config" "$malformed_body" --header 'Content-Type: application/json'
assert_status 400 DAST-DEVICE-AUTH-001
assert_json_code INVALID_JSON DAST-DEVICE-AUTH-001
pass DAST-JSON-001/DAST-DEVICE-AUTH-001

oversized_body="$temporary_root/oversized.json"
ruby -e 'File.write(ARGV.fetch(0), %({"padding":") + "x" * Integer(ARGV.fetch(1)) + %("}))' \
  "$oversized_body" "$((max_body_bytes + 1024))"
perform_request POST /api/v1/plants "$user_config" "$oversized_body" --header 'Content-Type: application/json'
assert_status 413 DAST-BODY-001
assert_json_code PAYLOAD_TOO_LARGE DAST-BODY-001
pass DAST-BODY-001

rate_limited=false
for ((attempt = 1; attempt <= rate_limit_attempts; attempt++)); do
  perform_request GET /health/ready - - --header "X-Forwarded-For: 198.51.100.$attempt"
  if [[ "$LAST_STATUS" == "429" ]]; then
    rate_limited=true
    assert_json_code RATE_LIMITED DAST-RATE-001
    [[ "$(header_value Retry-After)" =~ ^[1-9][0-9]*$ ]] || fail "DAST-RATE-001 Retry-After is missing"
    break
  fi
  [[ "$LAST_STATUS" == "200" || "$LAST_STATUS" == "503" ]] || fail "DAST-RATE-001 unexpected readiness status $LAST_STATUS"
done
[[ "$rate_limited" == true ]] || fail "DAST-RATE-001 did not observe a bounded rate limit"
pass DAST-RATE-001

perform_request GET "/api/v1/plants/$DAST_USER_PLANT_ID" "$user_config" -
assert_status 200 DAST-SSE-FIXTURE-001
sse_headers="$temporary_root/sse-headers"
sse_body="$temporary_root/sse-body"
sse_started=$(date +%s)
if ! sse_status=$(curl \
  --disable \
  --silent --show-error \
  --proto '=https' \
  --tlsv1.2 \
  --connect-timeout 10 \
  --max-time "$((sse_max_lifetime + 10))" \
  --config "$user_config" \
  --header 'Accept: text/event-stream' \
  --dump-header "$sse_headers" \
  --output "$sse_body" \
  --write-out '%{http_code}' \
  "$base_url/api/v1/plants/$DAST_USER_PLANT_ID/stream"); then
  fail "DAST-SSE-001 stream exceeded its server-side lifetime"
fi
sse_elapsed=$(( $(date +%s) - sse_started ))
sse_min_lifetime=$((sse_max_lifetime > 5 ? sse_max_lifetime - 5 : 0))
[[ "$sse_status" == "200" ]] || fail "DAST-SSE-001 expected HTTP 200, got $sse_status"
grep -Eqi '^Content-Type:[[:space:]]*text/event-stream' "$sse_headers" || fail "DAST-SSE-001 content type mismatch"
(( sse_elapsed >= sse_min_lifetime )) || fail "DAST-SSE-001 stream closed unexpectedly early"
(( sse_elapsed <= sse_max_lifetime + 5 )) || fail "DAST-SSE-001 stream lifetime was not bounded"
pass DAST-SSE-001

echo "Staging DAST security gate passed"
