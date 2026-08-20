#!/usr/bin/env bash
set -euo pipefail

if (( $# > 0 )); then
  echo "Usage: $0" >&2
  exit 2
fi

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/jaiqal-staging-dast-test.XXXXXX")
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT HUP INT TERM
mkdir -p "$temporary_root/bin"

mock_curl="$temporary_root/bin/curl"
cat >"$mock_curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
method=GET
output=
headers_file=
config=
data_file=
url=
declare -a request_headers=()
while (( $# > 0 )); do
  case "$1" in
    --disable|--silent|--show-error|--tlsv1.2) shift ;;
    --proto|--connect-timeout|--max-time|--write-out) shift 2 ;;
    --request) method="$2"; shift 2 ;;
    --dump-header) headers_file="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --config) config="$2"; shift 2 ;;
    --data-binary) data_file="${2#@}"; shift 2 ;;
    --header) request_headers+=("$2"); shift 2 ;;
    https://*) url="$1"; shift ;;
    *) echo "Unexpected mock curl argument" >&2; exit 90 ;;
  esac
done

path="${url#https://staging.example.test}"
status=200
body='{}'
declare -a response_headers=(
  'HTTP/2 200'
  'Strict-Transport-Security: max-age=31536000; includeSubDomains'
  'X-Deployment-Commit: 0123456789abcdef0123456789abcdef01234567'
)
joined_headers=' '
if (( ${#request_headers[@]} > 0 )); then
  joined_headers=" ${request_headers[*]} "
fi
config_name="${config##*/}"

case "$method $path" in
  'GET /health/live') ;;
  'OPTIONS /api/v1/auth/me')
    if [[ "$joined_headers" == *'Origin: https://app.staging.example.test'* ]]; then
      response_headers+=('Access-Control-Allow-Origin: https://app.staging.example.test')
    else
      status=403
    fi
    ;;
  'GET /api/v1/auth/me')
    if [[ "$config_name" != 'user.curl' ]]; then
      status=401
      body='{"code":"UNAUTHORIZED","requestId":"mock-request"}'
      response_headers+=('Cache-Control: no-store' 'X-Content-Type-Options: nosniff')
    fi
    ;;
  'GET /api/v1/plants/22222222-2222-4222-8222-222222222222')
    if [[ "$config_name" != 'other-user.curl' ]]; then
      status=404
      body='{"code":"NOT_FOUND","requestId":"mock-request"}'
    fi
    ;;
  'POST /api/v1/plants')
    if (( $(wc -c <"$data_file") > 65536 )); then
      status=413
      body='{"code":"PAYLOAD_TOO_LARGE","requestId":"mock-request"}'
    else
      status=400
      body='{"code":"INVALID_JSON","requestId":"mock-request"}'
    fi
    ;;
  'POST /api/device/v1/measurements')
    status=400
    body='{"code":"INVALID_JSON","requestId":"mock-request"}'
    ;;
  'GET /health/ready')
    count=0
    [[ ! -f "$MOCK_CURL_STATE" ]] || count=$(<"$MOCK_CURL_STATE")
    count=$((count + 1))
    printf '%s' "$count" >"$MOCK_CURL_STATE"
    if (( count >= 3 )); then
      status=429
      body='{"code":"RATE_LIMITED","requestId":"mock-request"}'
      response_headers+=('Retry-After: 10')
    else
      body='{"status":"ready"}'
    fi
    ;;
  'GET /api/v1/plants/11111111-1111-4111-8111-111111111111') ;;
  'GET /api/v1/plants/11111111-1111-4111-8111-111111111111/stream')
    response_headers+=('Content-Type: text/event-stream')
    body=': connected'
    ;;
  *) echo "Unexpected mock curl request" >&2; exit 91 ;;
esac

response_headers[0]="HTTP/2 $status"
printf '%s\r\n' "${response_headers[@]}" >"$headers_file"
printf '%s' "$body" >"$output"
printf '%s' "$status"
MOCK
chmod 0700 "$mock_curl"

user_token=mock-user-token-0123456789abcdef
other_token=mock-other-token-0123456789abcdef
device_token=mock-device-token-0123456789abcdef
common_environment=(
  "PATH=$temporary_root/bin:$PATH"
  "MOCK_CURL_STATE=$temporary_root/rate-count"
  'DAST_BASE_URL=https://staging.example.test'
  'DAST_ALLOWED_ORIGIN=https://app.staging.example.test'
  'DAST_EXPECTED_COMMIT=0123456789abcdef0123456789abcdef01234567'
  "DAST_USER_TOKEN=$user_token"
  "DAST_OTHER_USER_TOKEN=$other_token"
  'DAST_USER_PLANT_ID=11111111-1111-4111-8111-111111111111'
  'DAST_OTHER_USER_PLANT_ID=22222222-2222-4222-8222-222222222222'
  "DAST_DEVICE_TOKEN=$device_token"
  'DAST_RATE_LIMIT_ATTEMPTS=5'
  'DAST_MAX_BODY_BYTES=65536'
  'DAST_SSE_MAX_LIFETIME_SECONDS=1'
)

positive_stdout="$temporary_root/positive.stdout"
positive_stderr="$temporary_root/positive.stderr"
env "${common_environment[@]}" bash "$repo_root/scripts/staging-dast.sh" >"$positive_stdout" 2>"$positive_stderr"
grep -Fqx 'Staging DAST security gate passed' "$positive_stdout"

negative_stdout="$temporary_root/negative.stdout"
negative_stderr="$temporary_root/negative.stderr"
if env "${common_environment[@]}" \
  DAST_EXPECTED_COMMIT=ffffffffffffffffffffffffffffffffffffffff \
  MOCK_CURL_STATE="$temporary_root/negative-rate-count" \
  bash "$repo_root/scripts/staging-dast.sh" >"$negative_stdout" 2>"$negative_stderr"; then
  echo "Staging DAST self-test expected commit mismatch to fail" >&2
  exit 1
fi
grep -Fq 'DAST-COMMIT-001 deployed commit mismatch' "$negative_stderr"

for secret in "$user_token" "$other_token" "$device_token"; do
  if grep -Fq "$secret" "$positive_stdout" "$positive_stderr" "$negative_stdout" "$negative_stderr"; then
    echo "Staging DAST self-test observed a credential in output" >&2
    exit 1
  fi
done

echo "Staging DAST positive/negative self-tests passed"
