#!/bin/sh
# Operations on a running seeded stack (local profile), as the seeded operations user. A random seeded driver near
# Indiranagar is suspended, which takes them offline and refuses their going online, then reinstated. A random seeded
# rider books; once the driver accepts, the ride is in the active rides and its timeline shows the search and the
# offer, and operations cancel it. The review queue and the gauges are read last.
# Exits non-zero on the first unexpected answer. Used by CI's container job and for local smoke tests.
set -eu
BASE=${BASE:-http://localhost:8080}
MANAGEMENT=${MANAGEMENT:-http://localhost:8081}
OPS_PHONE="+919000000001"
DRIVER_PHONE="+91$((7000000011 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 1990))"
RIDER_PHONE="+91$((8000000011 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 490))"
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

expect_field() {
  actual=$(field "$1" 2>/dev/null || echo '(missing)')
  [ "$actual" = "$2" ] || { echo "$1: expected $2, got $actual: $(cat "$BODY_FILE")" >&2; exit 1; }
}

# How many of the response's items have field $1 equal to $2.
items_with() {
  python3 -c 'import json, sys; print(sum(1 for item in json.load(open(sys.argv[1]))[sys.argv[2]] if str(item.get(sys.argv[3])) == sys.argv[4]))' "$BODY_FILE" "$1" "$2" "$3"
}

# wait_for DESCRIPTION CONDITION: evaluates the condition every half second for up to 40 s.
wait_for() {
  for attempt in $(seq 1 80); do
    if eval "$2"; then
      echo "$1: after $attempt tries"
      return 0
    fi
    sleep 0.5
  done
  echo "$1: not within 40 s: $(cat "$BODY_FILE")" >&2
  exit 1
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

go_online() {
  BODY="{\"vehicle_id\": \"$VEHICLE\"}"
  expect "$1" "$(post /v1/drivers/me/online "$BODY" "$(key)")" "$2"
}

sign_in "$DRIVER_PHONE"
DRIVER_TOKEN=$TOKEN
expect "read the driver's profile" "$(get /v1/drivers/me)" 200
DRIVER=$(field id)
VEHICLE=$(field vehicles.0.id)
CATEGORY=$(field vehicles.0.category)
go_online "go online in a $CATEGORY" 200
BODY="{\"updates\": [{\"seq\": $(date +%s), \"lat\": 12.97194, \"lon\": 77.64115, \"accuracy_m\": 5, \"device_time\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}"
expect "send a location in Indiranagar" "$(post /v1/drivers/me/location "$BODY")" 200

sign_in "$OPS_PHONE"
OPS_TOKEN=$TOKEN
expect "list the available drivers" "$(get "/v1/ops/drivers?city_id=blr&status=AVAILABLE&limit=100")" 200
BODY='{"reason": "Smoke test"}'
expect "suspend the driver" "$(post "/v1/ops/drivers/$DRIVER/suspend" "$BODY" "$(key)")" 200
expect_field suspended True
expect_field status.status OFFLINE

TOKEN=$DRIVER_TOKEN
go_online "going online is refused while suspended" 409
expect_field code DRIVER_NOT_ELIGIBLE

TOKEN=$OPS_TOKEN
BODY='{"reason": "Smoke test over"}'
expect "reinstate the driver" "$(post "/v1/ops/drivers/$DRIVER/reinstate" "$BODY" "$(key)")" 200
expect_field suspended False

TOKEN=$DRIVER_TOKEN
go_online "go online again" 200
BODY="{\"updates\": [{\"seq\": $(($(date +%s) + 1)), \"lat\": 12.97194, \"lon\": 77.64115, \"accuracy_m\": 5, \"device_time\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}"
wait_for "send a location again, a second after the first (the rate limit)" '[ "$(post /v1/drivers/me/location "$BODY")" = 200 ]'

sign_in "$RIDER_PHONE"
BODY="{\"pickup\": {\"lat\": 12.97300, \"lon\": 77.64200}, \"dropoff\": {\"lat\": 12.93524, \"lon\": 77.62448}, \"category\": \"$CATEGORY\"}"
expect "quote" "$(post /v1/quotes "$BODY")" 201
BODY="{\"quote_id\": \"$(field id)\"}"
expect "book" "$(post /v1/rides "$BODY" "$(key)")" 201
RIDE=$(field id)

TOKEN=$DRIVER_TOKEN
wait_for "the driver sees the offer" '[ "$(get /v1/drivers/me/offer)" = 200 ] && [ "$(field ride_id)" = "$RIDE" ]'
expect "accept it" "$(post "/v1/offers/$(field id)/accept" '{}' "$(key)")" 200

TOKEN=$OPS_TOKEN
expect "list the assigned rides" "$(get "/v1/ops/rides?city_id=blr&status=DRIVER_ASSIGNED&limit=100")" 200
[ "$(items_with items id "$RIDE")" = 1 ] || { echo "the ride isn't listed: $(cat "$BODY_FILE")" >&2; exit 1; }
expect "read the ride's timeline" "$(get "/v1/ops/rides/$RIDE/timeline")" 200
for kind in TRANSITION DISPATCH_DECISION OFFER EVENT; do
  [ "$(items_with entries kind "$kind")" -ge 1 ] || { echo "the timeline has no $kind: $(cat "$BODY_FILE")" >&2; exit 1; }
done
echo "timeline: transitions, decisions, offers and events"
BODY='{"reason": "Smoke test"}'
expect "cancel the ride as operations" "$(post "/v1/ops/rides/$RIDE/cancel" "$BODY" "$(key)")" 200
expect_field status CANCELLED_BY_SYSTEM
expect "read the review queue" "$(get /v1/ops/flags)" 200
wait_for "the gauges are exported" 'curl -sS "$MANAGEMENT/actuator/prometheus" > "$BODY_FILE" && grep -q "^live_drivers{" "$BODY_FILE" && grep -q "^outbox_oldest_unpublished_seconds " "$BODY_FILE"'

TOKEN=$DRIVER_TOKEN
expect "go offline" "$(post /v1/drivers/me/offline '{}' "$(key)")" 200
