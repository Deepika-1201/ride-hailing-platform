#!/bin/sh
# Ratings and notifications on a running seeded stack (local profile). A random seeded rider takes a ride with a random
# seeded driver near Indiranagar. Once the completion reaches the rating module, the rider rates the driver and the
# driver rates the rider; a second rating by the same side is refused, and both profiles show their rating. The
# worker's poller sends the ride's notifications through the logging provider, which the delivery metrics show.
# Exits non-zero on the first unexpected answer. Used by CI's container job and for local smoke tests.
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

# The value of the PUSH deliveries counter with outcome $1; 0 until it is exported.
sent_pushes() {
  curl -sS "$MANAGEMENT/actuator/prometheus" \
    | awk -v outcome="$1" '$1 == "notification_deliveries_total{channel=\"PUSH\",outcome=\"" outcome "\"}" { print int($2); found = 1 } END { if (!found) print 0 }'
}

PUSHES_BEFORE=$(sent_pushes SENT)

sign_in "$DRIVER_PHONE"
DRIVER_TOKEN=$TOKEN
expect "read the driver's profile" "$(get /v1/drivers/me)" 200
VEHICLE=$(field vehicles.0.id)
CATEGORY=$(field vehicles.0.category)
BODY="{\"vehicle_id\": \"$VEHICLE\"}"
expect "go online in a $CATEGORY" "$(post /v1/drivers/me/online "$BODY" "$(key)")" 200
BODY="{\"updates\": [{\"seq\": $(date +%s), \"lat\": 12.97194, \"lon\": 77.64115, \"accuracy_m\": 5, \"device_time\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}"
expect "send a location in Indiranagar" "$(post /v1/drivers/me/location "$BODY")" 200

sign_in "$RIDER_PHONE"
RIDER_TOKEN=$TOKEN
BODY="{\"pickup\": {\"lat\": 12.97300, \"lon\": 77.64200}, \"dropoff\": {\"lat\": 12.93524, \"lon\": 77.62448}, \"category\": \"$CATEGORY\"}"
expect "quote" "$(post /v1/quotes "$BODY")" 201
BODY="{\"quote_id\": \"$(field id)\"}"
expect "book" "$(post /v1/rides "$BODY" "$(key)")" 201
RIDE=$(field id)

TOKEN=$DRIVER_TOKEN
wait_for "the driver sees the offer" '[ "$(get /v1/drivers/me/offer)" = 200 ] && [ "$(field ride_id)" = "$RIDE" ]'
field rider.rating.count > /dev/null || { echo "the offer shows no rider rating: $(cat "$BODY_FILE")" >&2; exit 1; }
expect "accept it" "$(post "/v1/offers/$(field id)/accept" '{}' "$(key)")" 200
TOKEN=$RIDER_TOKEN
expect "the rider reads the PIN" "$(get "/v1/rides/$RIDE")" 200
BODY="{\"pin\": \"$(field pin)\"}"
TOKEN=$DRIVER_TOKEN
expect "arrive" "$(post "/v1/rides/$RIDE/arrive" '{}' "$(key)")" 200
expect "start with the PIN" "$(post "/v1/rides/$RIDE/start" "$BODY" "$(key)")" 200
expect "complete the trip" "$(post "/v1/rides/$RIDE/complete" '{}' "$(key)")" 200
expect "go offline" "$(post /v1/drivers/me/offline '{}' "$(key)")" 200

TOKEN=$RIDER_TOKEN
BODY='{"stars": 5, "comment": "Smooth ride"}'
wait_for "the rider rates the driver once the window opens" '[ "$(post "/v1/rides/$RIDE/rating" "$BODY" "$(key)")" = 201 ]'
expect_field rater_role RIDER
expect_field stars 5
BODY='{"stars": 1}'
expect "a second rating by the rider is refused" "$(post "/v1/rides/$RIDE/rating" "$BODY" "$(key)")" 409
expect_field code ALREADY_RATED
expect "the rider's profile" "$(get /v1/riders/me)" 200
RIDER_RATINGS=$(field rating.count)

TOKEN=$DRIVER_TOKEN
BODY='{"stars": 4}'
expect "the driver rates the rider" "$(post "/v1/rides/$RIDE/rating" "$BODY" "$(key)")" 201
expect_field rater_role DRIVER
expect "the driver's profile shows a rating" "$(get /v1/drivers/me)" 200
[ "$(field rating.count)" -ge 1 ] || { echo "the driver has no rating: $(cat "$BODY_FILE")" >&2; exit 1; }

TOKEN=$RIDER_TOKEN
expect "the rider's profile counts the new rating" "$(get /v1/riders/me)" 200
[ "$(field rating.count)" -eq "$((RIDER_RATINGS + 1))" ] || [ "$RIDER_RATINGS" -eq 100 ] \
    || { echo "the rider's rating didn't count it: $(cat "$BODY_FILE")" >&2; exit 1; }

wait_for "the ride's pushes are sent" '[ "$(sent_pushes SENT)" -ge $((PUSHES_BEFORE + 5)) ]'
curl -sS "$MANAGEMENT/actuator/prometheus" > "$BODY_FILE"
for outcome in SENT FAILED DEAD; do
  grep -qF "notification_deliveries_total{channel=\"PUSH\",outcome=\"$outcome\"}" "$BODY_FILE" \
      || { echo "notification_deliveries_total{outcome=\"$outcome\"} isn't exported" >&2; exit 1; }
done
echo "metrics: exported"
