#!/bin/sh
# Booking and dispatch on a running seeded stack (local profile). A random seeded driver goes online near Indiranagar;
# a random seeded rider books, cancels while searching, and books again; the driver polls for the offer and accepts it;
# the rider then sees their driver and the PIN. Riders and drivers are random because an accepted ride keeps both busy
# until phase 8 can end it, so reruns against the same stack need fresh ones. Exits non-zero on the first unexpected
# answer, or if the phase-7 metrics aren't exported. Used by CI's container job and for local smoke tests.
set -eu
BASE=${BASE:-http://localhost:8080}
MANAGEMENT=${MANAGEMENT:-http://localhost:8081}
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

# Books a fresh quote from Indiranagar to Koramangala in the driver's category; sets RIDE.
book() {
  BODY="{\"pickup\": {\"lat\": 12.97300, \"lon\": 77.64200}, \"dropoff\": {\"lat\": 12.93524, \"lon\": 77.62448}, \"category\": \"$CATEGORY\"}"
  expect "quote" "$(post /v1/quotes "$BODY")" 201
  BODY="{\"quote_id\": \"$(field id)\"}"
  expect "book it" "$(post /v1/rides "$BODY" "$(key)")" 201
  expect_field status SEARCHING
  RIDE=$(field id)
}

sign_in "$DRIVER_PHONE"
DRIVER_TOKEN=$TOKEN
expect "read the driver's profile" "$(get /v1/drivers/me)" 200
VEHICLE=$(field vehicles.0.id)
CATEGORY=$(field vehicles.0.category)
BODY="{\"vehicle_id\": \"$VEHICLE\"}"
expect "go online in a $CATEGORY" "$(post /v1/drivers/me/online "$BODY" "$(key)")" 200
SEQ=$(date +%s)
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
BODY="{\"updates\": [{\"seq\": $SEQ, \"lat\": 12.97194, \"lon\": 77.64115, \"accuracy_m\": 5, \"device_time\": \"$NOW\"}]}"
expect "send a location in Indiranagar" "$(post /v1/drivers/me/location "$BODY")" 200

sign_in "$RIDER_PHONE"
RIDER_TOKEN=$TOKEN
book
expect "cancel while searching" "$(post "/v1/rides/$RIDE/cancel" '{"reason": "smoke test"}' "$(key)")" 200
expect_field status CANCELLED_BY_RIDER
book

TOKEN=$DRIVER_TOKEN
OFFER=''
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  CODE=$(get /v1/drivers/me/offer)
  if [ "$CODE" = 200 ] && [ "$(field ride_id)" = "$RIDE" ]; then
    OFFER=$(field id)
    break
  fi
  sleep 0.5
done
[ -n "$OFFER" ] || { echo "no offer for ride $RIDE within 10 s: $CODE $(cat "$BODY_FILE")" >&2; exit 1; }
echo "poll the offer: 200 after $attempt tries"
expect "accept it" "$(post "/v1/offers/$OFFER/accept" '{}' "$(key)")" 200
expect_field status DRIVER_ASSIGNED
field pin >/dev/null 2>&1 && { echo "the driver's view shows the PIN: $(cat "$BODY_FILE")" >&2; exit 1; }

TOKEN=$RIDER_TOKEN
expect "the rider reads the ride" "$(get "/v1/rides/$RIDE")" 200
expect_field status DRIVER_ASSIGNED
expect_field vehicle.id "$VEHICLE"
PIN=$(field pin)
case $PIN in
  [0-9][0-9][0-9][0-9]) echo "  driver $(field driver.first_name), PIN shown to the rider" ;;
  *) echo "the rider's PIN is $PIN: $(cat "$BODY_FILE")" >&2; exit 1 ;;
esac

curl -sS "$MANAGEMENT/actuator/prometheus" > "$BODY_FILE"
for metric in ride_requests_total dispatch_first_offer_seconds_count ride_assignment_seconds_count \
    'offers_total{outcome="ACCEPTED"}' 'dispatch_search_attempts_total{outcome="OFFERED"}' \
    dispatch_reservation_conflicts_total; do
  grep -qF "$metric" "$BODY_FILE" || { echo "metric $metric isn't exported" >&2; exit 1; }
done
echo "metrics: exported"
