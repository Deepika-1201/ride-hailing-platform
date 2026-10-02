#!/bin/sh
# Signs in as the seeded admin against a running stack (local profile): code, tokens, refresh, logout. Exits non-zero
# on the first unexpected answer. Used by CI's container job and for local smoke tests.
set -eu
BASE=${BASE:-http://localhost:8080}
PHONE='+919000000002'

post() {
  curl -sS -o /tmp/sign-in-body -w '%{http_code}' -H 'Content-Type: application/json' -d "$2" "$BASE$1"
}

expect() {
  if [ "$2" != "$3" ]; then
    echo "$1: expected $3, got $2: $(cat /tmp/sign-in-body)" >&2
    exit 1
  fi
  echo "$1: $2"
}

# Bodies go through variables: bash 3.2 (macOS /bin/sh) brace-expands "{…,…}" nested inside "$(…)".
expect "request a code" "$(post /v1/auth/otp "{\"phone\": \"$PHONE\"}")" 202
BODY="{\"phone\": \"$PHONE\", \"code\": \"123456\"}"
expect "exchange it" "$(post /v1/auth/token "$BODY")" 200
grep -q '"roles":\["ADMIN"\]' /tmp/sign-in-body || { echo "not signed in as ADMIN" >&2; exit 1; }
REFRESH=$(sed -E 's/.*"refresh_token":"([^"]+)".*/\1/' /tmp/sign-in-body)
BODY="{\"refresh_token\": \"$REFRESH\"}"
expect "refresh" "$(post /v1/auth/refresh "$BODY")" 200
REFRESH=$(sed -E 's/.*"refresh_token":"([^"]+)".*/\1/' /tmp/sign-in-body)
BODY="{\"refresh_token\": \"$REFRESH\"}"
expect "log out" "$(post /v1/auth/logout "$BODY")" 204
expect "refresh after logout" "$(post /v1/auth/refresh "$BODY")" 401
