#!/bin/sh
# Quotes on a running seeded stack (local profile), as seeded rider 2: the OpenAPI example trip in Bengaluru, whose
# fare must add up, and a pickup outside every service area. Exits non-zero on the first unexpected answer. Used by
# CI's container job and for local smoke tests.
set -eu
BASE=${BASE:-http://localhost:8080}
PHONE='+918000000002'
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

post() {
  if [ -n "$TOKEN" ]; then
    curl -sS -o "$BODY_FILE" -w '%{http_code}' -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $TOKEN" -d "$2" "$BASE$1"
  else
    curl -sS -o "$BODY_FILE" -w '%{http_code}' -H 'Content-Type: application/json' -d "$2" "$BASE$1"
  fi
}

expect() {
  if [ "$2" != "$3" ]; then
    echo "$1: expected $3, got $2: $(cat "$BODY_FILE")" >&2
    exit 1
  fi
  echo "$1: $2"
}

# Bodies go through variables: bash 3.2 (macOS /bin/sh) brace-expands "{…,…}" nested inside "$(…)".
TOKEN=''
BODY="{\"phone\": \"$PHONE\"}"
expect "request a code" "$(post /v1/auth/otp "$BODY")" 202
BODY="{\"phone\": \"$PHONE\", \"code\": \"123456\"}"
expect "exchange it" "$(post /v1/auth/token "$BODY")" 200
TOKEN=$(sed -E 's/.*"access_token":"([^"]+)".*/\1/' "$BODY_FILE")

BODY='{"pickup": {"lat": 12.97194, "lon": 77.64115}, "dropoff": {"lat": 12.93524, "lon": 77.62448}, "category": "MINI"}'
expect "quote Indiranagar to Koramangala" "$(post /v1/quotes "$BODY")" 201
python3 - "$BODY_FILE" <<'PYTHON'
import json, sys
quote = json.load(open(sys.argv[1]))
fare = quote["fare"]
parts = sum(fare[part]["amount_paise"] for part in
            ("base", "distance", "time", "surge", "minimum_topup", "booking_fee", "tax", "rounding"))
total = fare["total"]["amount_paise"]
if parts != total or total % 100 != 0 or quote["city_id"] != "blr":
    sys.exit(f"the quote doesn't add up: {quote}")
print(f"  {quote['distance_m']} m, {quote['duration_s']} s, surge {quote['surge_multiplier']}: Rs {total // 100}")
PYTHON

BODY='{"pickup": {"lat": 1.0, "lon": 1.0}, "dropoff": {"lat": 12.93524, "lon": 77.62448}, "category": "MINI"}'
expect "quote from outside every service area" "$(post /v1/quotes "$BODY")" 422
grep -q '"code":"OUTSIDE_SERVICE_AREA"' "$BODY_FILE" || { echo "unexpected problem: $(cat "$BODY_FILE")" >&2; exit 1; }
