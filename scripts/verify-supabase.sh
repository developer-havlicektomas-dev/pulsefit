#!/usr/bin/env bash
# Verifies step 0.2 (Supabase projects) against one project.
#
# Usage:
#   SUPABASE_URL=https://<ref>.supabase.co \
#   SUPABASE_ANON_KEY=<publishable or anon key> \
#   TEST_EMAIL=<user created in the dashboard> \
#   scripts/verify-supabase.sh
#
# The test user's password is read from TEST_PASSWORD if set, otherwise
# prompted for without echo. Nothing secret is printed.
set -euo pipefail

: "${SUPABASE_URL:?set SUPABASE_URL}"
: "${SUPABASE_ANON_KEY:?set SUPABASE_ANON_KEY}"
: "${TEST_EMAIL:?set TEST_EMAIL}"
SUPABASE_URL="${SUPABASE_URL%/}"

if [[ -z "${TEST_PASSWORD:-}" ]]; then
  read -r -s -p "Password for $TEST_EMAIL: " TEST_PASSWORD
  echo
fi

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok:   $*"; }

# base64url-decode a JWT segment
b64url() {
  local s="${1//-/+}"; s="${s//_//}"
  case $(( ${#s} % 4 )) in 2) s+="==";; 3) s+="=";; esac
  printf '%s' "$s" | base64 -d 2>/dev/null
}

# 1. JWKS exposes at least one asymmetric key
jwks=$(curl -fsS "$SUPABASE_URL/auth/v1/.well-known/jwks.json") || fail "JWKS request failed"
count=$(jq '.keys | length' <<<"$jwks")
(( count > 0 )) || fail "JWKS has no keys (asymmetric JWT signing keys not enabled?)"
ok "JWKS returns $count key(s): $(jq -r '[.keys[] | "\(.kid) \(.alg // .kty)"] | join(", ")' <<<"$jwks")"

# 2. Password grant for the dashboard-created user
body=$(jq -n --arg e "$TEST_EMAIL" --arg p "$TEST_PASSWORD" '{email: $e, password: $p}')
resp=$(curl -sS -w '\n%{http_code}' -X POST \
  "$SUPABASE_URL/auth/v1/token?grant_type=password" \
  -H "apikey: $SUPABASE_ANON_KEY" -H "Content-Type: application/json" \
  --data-binary @- <<<"$body")
status=${resp##*$'\n'}; resp=${resp%$'\n'*}
[[ "$status" == 200 ]] || fail "token request returned $status: $(jq -r '.msg // .error_description // .message // .' <<<"$resp")"
token=$(jq -r '.access_token' <<<"$resp")
ok "password grant returned an access token"

# 3. Token is signed with one of the JWKS keys and has the claims the API checks
IFS=. read -r h p _ <<<"$token"
header=$(b64url "$h"); claims=$(b64url "$p")
alg=$(jq -r '.alg' <<<"$header"); kid=$(jq -r '.kid // empty' <<<"$header")
[[ "$alg" != HS* ]] || fail "token is signed with $alg (symmetric); switch to asymmetric signing keys"
jq -e --arg k "$kid" '.keys[] | select(.kid == $k)' <<<"$jwks" >/dev/null || fail "token kid '$kid' not in JWKS"
ok "token alg $alg, kid $kid found in JWKS"

iss=$(jq -r '.iss' <<<"$claims"); aud=$(jq -r '.aud' <<<"$claims")
[[ "$iss" == "$SUPABASE_URL/auth/v1" ]] || fail "issuer is '$iss', expected '$SUPABASE_URL/auth/v1'"
[[ "$aud" == authenticated ]] || fail "audience is '$aud', expected 'authenticated'"
ok "iss and aud match backend-spec §4 (sub $(jq -r '.sub' <<<"$claims"))"

echo "PASS: $SUPABASE_URL"
