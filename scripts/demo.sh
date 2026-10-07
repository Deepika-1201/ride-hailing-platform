#!/bin/sh
# The V1 demo (FR-S1, LLD §17.1): one ride end to end on the local stack (docker compose up), told step by step.
# A random seeded rider adds a mock card and a random seeded driver goes online near Indiranagar. The rider books a
# trip to Koramangala; the driver accepts, arrives, starts with the rider's PIN and completes it at the quoted fare.
# The worker charges the card, both sides rate each other, and the rider's receipt and history, the driver's trips and
# earnings, and the ride's timeline as operations see it all show the ride. Exits non-zero on the first unexpected
# answer. Used by CI's container job.
set -eu
BASE=${BASE:-http://localhost:8080}
MANAGEMENT=${MANAGEMENT:-http://localhost:8081}
DRIVER_PHONE="+91$((7000000011 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 1990))"
RIDER_PHONE="+91$((8000000011 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 490))"
OPS_PHONE="+919000000001"
TODAY=$(TZ=Asia/Kolkata date +%Y-%m-%d)
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

# expect WHAT STATUS EXPECTED: silent when they match.
expect() {
  [ "$2" = "$3" ] || { echo "$1: expected $3, got $2: $(cat "$BODY_FILE")" >&2; exit 1; }
}

field() {
  python3 -c 'import json, sys; value = json.load(open(sys.argv[1])); [value := value[int(k) if k.isdigit() else k] for k in sys.argv[2].split(".")]; print(value)' "$BODY_FILE" "$1"
}

expect_field() {
  actual=$(field "$1" 2>/dev/null || echo '(missing)')
  [ "$actual" = "$2" ] || { echo "$1: expected $2, got $actual: $(cat "$BODY_FILE")" >&2; exit 1; }
}

# The money at the path of the last body, such as "INR 259.00".
money() {
  python3 -c 'import json, sys; value = json.load(open(sys.argv[1])); [value := value[int(k) if k.isdigit() else k] for k in sys.argv[2].split(".")]; print("%s %.2f" % (value["currency"], value["amount_paise"] / 100))' "$BODY_FILE" "$1"
}

# wait_for WHAT CONDITION: evaluates the condition every half second for up to 40 s.
wait_for() {
  for attempt in $(seq 1 80); do
    eval "$2" && return 0
    sleep 0.5
  done
  echo "$1: not within 40 s: $(cat "$BODY_FILE")" >&2
  exit 1
}

say() {
  echo "==> $*"
}

detail() {
  echo "    $*"
}

# Sets TOKEN for the phone. Bodies go through variables: bash 3.2 (macOS /bin/sh) brace-expands "{…,…}" in "$(…)".
sign_in() {
  TOKEN=''
  BODY="{\"phone\": \"$1\"}"
  expect "request a code for $1" "$(post /v1/auth/otp "$BODY")" 202
  BODY="{\"phone\": \"$1\", \"code\": \"123456\"}"
  expect "sign in as $1" "$(post /v1/auth/token "$BODY")" 200
  TOKEN=$(field access_token)
}

key() {
  python3 -c 'import uuid; print(uuid.uuid4())'
}

curl -fsS "$MANAGEMENT/actuator/health/readiness" > /dev/null 2>&1 \
    || { echo "The stack isn't ready at $MANAGEMENT; start it with: docker compose up" >&2; exit 1; }

sign_in "$RIDER_PHONE"
RIDER_TOKEN=$TOKEN
expect "read the rider's profile" "$(get /v1/riders/me)" 200
RIDER_NAME=$(field first_name)
BODY='{"type": "CARD", "provider_token": "tok_ok", "display": "Visa 4242"}'
expect "add a card" "$(post /v1/riders/me/payment-methods "$BODY")" 201
CARD=$(field id)
expect "make it the default" "$(post "/v1/riders/me/payment-methods/$CARD/default" '{}')" 200
say "Rider $RIDER_NAME signs in with a one-time code and adds a card as their default way to pay"

sign_in "$DRIVER_PHONE"
DRIVER_TOKEN=$TOKEN
expect "read the driver's profile" "$(get /v1/drivers/me)" 200
DRIVER_NAME=$(field first_name)
VEHICLE=$(field vehicles.0.id)
CATEGORY=$(field vehicles.0.category)
PLATE=$(field vehicles.0.plate)
BODY="{\"vehicle_id\": \"$VEHICLE\"}"
expect "go online" "$(post /v1/drivers/me/online "$BODY" "$(key)")" 200
expect_field status AVAILABLE
BODY="{\"updates\": [{\"seq\": $(date +%s), \"lat\": 12.97194, \"lon\": 77.64115, \"accuracy_m\": 5, \"device_time\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}"
expect "send a location" "$(post /v1/drivers/me/location "$BODY")" 200
say "Driver $DRIVER_NAME goes online in their $CATEGORY ($PLATE) and reports a position in Indiranagar"

TOKEN=$RIDER_TOKEN
BODY="{\"pickup\": {\"lat\": 12.97300, \"lon\": 77.64200}, \"dropoff\": {\"lat\": 12.93524, \"lon\": 77.62448}, \"category\": \"$CATEGORY\"}"
expect "quote" "$(post /v1/quotes "$BODY")" 201
QUOTE=$(field id)
say "$RIDER_NAME asks for a quote from Indiranagar to Koramangala"
detail "$(field distance_m) m, about $(($(field duration_s) / 60)) min: $(money fare.total), a driver $(field pickup_eta_s 2>/dev/null || echo '?') s away"
BODY="{\"quote_id\": \"$QUOTE\"}"
expect "book" "$(post /v1/rides "$BODY" "$(key)")" 201
expect_field status SEARCHING
RIDE=$(field id)
say "$RIDER_NAME books it: ride $RIDE is SEARCHING"

TOKEN=$DRIVER_TOKEN
wait_for "the driver's offer" '[ "$(get /v1/drivers/me/offer)" = 200 ] && [ "$(field ride_id)" = "$RIDE" ]'
OFFER=$(field id)
say "$DRIVER_NAME is offered the ride, $(field pickup_distance_m) m from the pickup, for $(money fare)"
expect "accept" "$(post "/v1/offers/$OFFER/accept" '{}' "$(key)")" 200
expect_field status DRIVER_ASSIGNED

TOKEN=$RIDER_TOKEN
expect "the rider's view" "$(get /v1/riders/me/active-ride)" 200
expect_field id "$RIDE"
PIN=$(field pin)
say "$DRIVER_NAME accepts; $RIDER_NAME's app shows $(field driver.first_name) in the $(field vehicle.colour) $(field vehicle.make) $(field vehicle.model) ($PLATE), and the PIN $PIN"

TOKEN=$DRIVER_TOKEN
expect "arrive" "$(post "/v1/rides/$RIDE/arrive" '{}' "$(key)")" 200
expect_field status DRIVER_ARRIVED
BODY="{\"pin\": \"$PIN\"}"
expect "start" "$(post "/v1/rides/$RIDE/start" "$BODY" "$(key)")" 200
expect_field status IN_TRIP
say "$DRIVER_NAME arrives and starts the trip with the rider's PIN"
expect "complete" "$(post "/v1/rides/$RIDE/complete" '{}' "$(key)")" 200
expect_field status COMPLETED
say "$DRIVER_NAME completes the trip at the quoted fare, $(money fare)"
expect "the driver's active ride" "$(get /v1/drivers/me/active-ride)" 204

TOKEN=$RIDER_TOKEN
wait_for "the card is charged" '[ "$(get "/v1/rides/$RIDE/receipt")" = 200 ] && [ "$(field payment.charges.0.status 2>/dev/null)" = SUCCEEDED ]'
say "The worker charges the card; $RIDER_NAME's receipt:"
python3 -c '
import json, sys
receipt = json.load(open(sys.argv[1]))
for part in ("base", "distance", "time", "surge", "minimum_topup", "booking_fee", "tax", "rounding", "total"):
    money = receipt["fare"][part]
    if money["amount_paise"] or part == "total":
        print("    %-14s %s %8.2f" % (part.replace("_", " "), money["currency"], money["amount_paise"] / 100))
charge = receipt["payment"]["charges"][0]
print("    paid by %s: %s %s" % (receipt["payment"]["method_type"], charge["purpose"], charge["status"]))
' "$BODY_FILE"
expect "the rider's history" "$(get "/v1/riders/me/rides?limit=1")" 200
expect_field items.0.id "$RIDE"
expect_field items.0.status COMPLETED
expect "the rider's active ride" "$(get /v1/riders/me/active-ride)" 204
say "The ride heads $RIDER_NAME's history, and they have no active ride"

BODY='{"stars": 5, "comment": "Smooth ride"}'
wait_for "the rating window" '[ "$(post "/v1/rides/$RIDE/rating" "$BODY" "$(key)")" = 201 ]'
TOKEN=$DRIVER_TOKEN
BODY='{"stars": 5}'
expect "the driver rates the rider" "$(post "/v1/rides/$RIDE/rating" "$BODY" "$(key)")" 201
say "$RIDER_NAME and $DRIVER_NAME rate each other 5 stars"

expect "the driver's trips" "$(get "/v1/drivers/me/rides?limit=1")" 200
expect_field items.0.id "$RIDE"
say "The trip heads $DRIVER_NAME's history with its earnings: $(money items.0.earnings.gross) less $(money items.0.earnings.commission) commission, $(money items.0.earnings.net) net"
expect "the driver's earnings" "$(get "/v1/drivers/me/earnings?from=$TODAY&to=$TODAY")" 200
detail "completed trips on $TODAY: $(field totals.rides), earning $(money totals.net) net"
expect "go offline" "$(post /v1/drivers/me/offline '{}' "$(key)")" 200
say "$DRIVER_NAME goes offline"

sign_in "$OPS_PHONE"
expect "the ride's timeline" "$(get "/v1/ops/rides/$RIDE/timeline")" 200
say "Operations read the ride's timeline:"
python3 -c '
import json, sys
for entry in json.load(open(sys.argv[1]))["entries"]:
    print("    %s  %-17s %s" % (entry["at"][11:19], entry["kind"], entry["summary"]))
' "$BODY_FILE"
say "Done: ride $RIDE ran end to end"
