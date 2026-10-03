#!/bin/sh
# A driver's shift on a running seeded stack (local profile), as seeded driver 3: go online with their vehicle, send a
# location near Indiranagar, appear as the pickup ETA in seeded rider 3's quote from nearby, and go offline. Exits
# non-zero on the first unexpected answer, or if the phase-6 metrics aren't exported. Used by CI's container job and
# for local smoke tests.
set -eu
BASE=${BASE:-http://localhost:8080}
MANAGEMENT=${MANAGEMENT:-http://localhost:8081}
DRIVER_PHONE='+917000000003'
RIDER_PHONE='+918000000003'
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

# post PATH BODY [IDEMPOTENCY-KEY], as $TOKEN when set.
post() {
  set -- "$1" "$2" "${3:-}" -sS -o "$BODY_FILE" -w '%{http_code}' -H 'Content-Type: application/json'
  path=$1 body=$2 idempotency_key=$3
  shift 3
  [ -n "$TOKEN" ] && set -- "$@" -H "Authorization: Bearer $TOKEN"
  [ -n "$idempotency_key" ] && set -- "$@" -H "Idempotency-Key: $idempotency_key"
  curl "$@" -d "$body" "$BASE$path"
}

get() {
  curl -sS -o "$BODY_FILE" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE$1"
}

expect() {
  if [ "$2" != "$3" ]; then
    echo "$1: expected $3, got $2: $(cat "$BODY_FILE")" >&2
    exit 1
  fi
  echo "$1: $2"
}

field() {
  python3 -c 'import json, sys; value = json.load(open(sys.argv[1])); [value := value[int(k) if k.isdigit() else k] for k in sys.argv[2].split(".")]; print(value)' "$BODY_FILE" "$1"
}

# Sets TOKEN for the phone. Bodies go through variables: bash 3.2 (macOS /bin/sh) brace-expands "{…,…}" in "$(…)".
sign_in() {
  TOKEN=''
  BODY="{\"phone\": \"$1\"}"
  expect "request a code for $1" "$(post /v1/auth/otp "$BODY")" 202
  BODY="{\"phone\": \"$1\", \"code\": \"123456\"}"
  expect "exchange it" "$(post /v1/auth/token "$BODY")" 200
  TOKEN=$(field access_token)
}

key() {
  python3 -c 'import uuid; print(uuid.uuid4())'
}

sign_in "$DRIVER_PHONE"
DRIVER_TOKEN=$TOKEN
expect "read the driver's profile" "$(get /v1/drivers/me)" 200
VEHICLE=$(field vehicles.0.id)
CATEGORY=$(field vehicles.0.category)

BODY="{\"vehicle_id\": \"$VEHICLE\"}"
expect "go online in a $CATEGORY" "$(post /v1/drivers/me/online "$BODY" "$(key)")" 200
[ "$(field status)" = AVAILABLE ] || { echo "not available: $(cat "$BODY_FILE")" >&2; exit 1; }

# The app's sequence numbers only grow, so a rerun against the same stack still counts as new.
SEQ=$(date +%s)
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY="{\"updates\": [{\"seq\": $SEQ, \"lat\": 12.97194, \"lon\": 77.64115, \"accuracy_m\": 5, \"device_time\": \"$NOW\"}]}"
expect "send a location in Indiranagar" "$(post /v1/drivers/me/location "$BODY")" 200
[ "$(field applied)" = 1 ] || { echo "not applied: $(cat "$BODY_FILE")" >&2; exit 1; }

sign_in "$RIDER_PHONE"
BODY="{\"pickup\": {\"lat\": 12.97300, \"lon\": 77.64200}, \"dropoff\": {\"lat\": 12.93524, \"lon\": 77.62448}, \"category\": \"$CATEGORY\"}"
expect "quote from nearby" "$(post /v1/quotes "$BODY")" 201
ETA=$(field pickup_eta_s 2>/dev/null) || { echo "the quote has no pickup ETA: $(cat "$BODY_FILE")" >&2; exit 1; }
echo "  pickup ETA $ETA s"

TOKEN=$DRIVER_TOKEN
expect "go offline" "$(post /v1/drivers/me/offline '{}' "$(key)")" 200
[ "$(field status)" = OFFLINE ] || { echo "still online: $(cat "$BODY_FILE")" >&2; exit 1; }

curl -sS "$MANAGEMENT/actuator/prometheus" > "$BODY_FILE"
for metric in 'location_updates_total{result="applied"}' live_index_mirror_failures_total \
    'sweeper_safety_valve_total{rule="idle"}' location_sweeper_removed_total; do
  grep -qF "$metric" "$BODY_FILE" || { echo "metric $metric isn't exported" >&2; exit 1; }
done
echo "metrics: exported"
