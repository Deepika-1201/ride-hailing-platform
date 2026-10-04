#!/bin/sh
# Payments on a running seeded stack (local profile: the mock provider, its latency and its own webhooks). A random
# seeded rider pays by a mock card that declines and takes a ride with a random seeded driver near Indiranagar. The
# fare is declined and becomes dues, which refuse the next booking; the rider pays them with a card that succeeds.
# Operations find the paid charge and refund part of it, the driver's earnings show the trip, and the payment metrics
# are exported. Riders and drivers are random, so a rerun after a failed run starts with fresh ones. Exits non-zero on
# the first unexpected answer. Used by CI's container job and for local smoke tests.
set -eu
BASE=${BASE:-http://localhost:8080}
MANAGEMENT=${MANAGEMENT:-http://localhost:8081}
DRIVER_PHONE="+91$((7000000011 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 1990))"
RIDER_PHONE="+91$((8000000011 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 490))"
OPS_PHONE="+919000000001"
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

# The status of charge $1 in an operations list held in the body file; empty if it isn't listed.
charge_status() {
  python3 -c 'import json, sys; print(next((c["status"] for c in json.load(open(sys.argv[1]))["items"] if c["id"] == sys.argv[2]), ""))' "$BODY_FILE" "$1"
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

# Quotes Indiranagar to Koramangala in the driver's category; sets QUOTE.
quote() {
  BODY="{\"pickup\": {\"lat\": 12.97300, \"lon\": 77.64200}, \"dropoff\": {\"lat\": 12.93524, \"lon\": 77.62448}, \"category\": \"$CATEGORY\"}"
  expect "quote" "$(post /v1/quotes "$BODY")" 201
  QUOTE=$(field id)
}

# Adds a mock card with the token; sets CARD.
add_card() {
  BODY="{\"type\": \"CARD\", \"provider_token\": \"$1\", \"display\": \"Visa $1\"}"
  expect "add a card ($1)" "$(post /v1/riders/me/payment-methods "$BODY")" 201
  CARD=$(field id)
}

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
add_card tok_decline
expect "make it the default" "$(post "/v1/riders/me/payment-methods/$CARD/default" '{}')" 200
quote
BODY="{\"quote_id\": \"$QUOTE\"}"
expect "book, paying by card" "$(post /v1/rides "$BODY" "$(key)")" 201
RIDE=$(field id)

TOKEN=$DRIVER_TOKEN
wait_for "the driver sees the offer" '[ "$(get /v1/drivers/me/offer)" = 200 ] && [ "$(field ride_id)" = "$RIDE" ]'
expect "accept it" "$(post "/v1/offers/$(field id)/accept" '{}' "$(key)")" 200
TOKEN=$RIDER_TOKEN
expect "the rider reads the PIN" "$(get "/v1/rides/$RIDE")" 200
BODY="{\"pin\": \"$(field pin)\"}"
TOKEN=$DRIVER_TOKEN
expect "arrive" "$(post "/v1/rides/$RIDE/arrive" '{}' "$(key)")" 200
expect "start with the PIN" "$(post "/v1/rides/$RIDE/start" "$BODY" "$(key)")" 200
expect "complete the trip" "$(post "/v1/rides/$RIDE/complete" '{}' "$(key)")" 200
FARE=$(field fare.amount_paise)

TOKEN=$RIDER_TOKEN
wait_for "the declined fare becomes dues" '[ "$(get /v1/riders/me/dues)" = 200 ] && [ "$(field total.amount_paise)" = "$FARE" ]'
expect_field charges.0.failure_code DECLINED
CHARGE=$(field charges.0.id)
quote
BODY="{\"quote_id\": \"$QUOTE\"}"
expect "dues refuse the next booking" "$(post /v1/rides "$BODY" "$(key)")" 409
expect_field code DUES_OUTSTANDING
expect_field dues.amount_paise "$FARE"
add_card tok_ok
BODY="{\"payment_method_id\": \"$CARD\"}"
expect "pay the dues with a card that succeeds" "$(post /v1/riders/me/dues/pay "$BODY" "$(key)")" 202
expect_field charges.0.status PENDING

sign_in "$OPS_PHONE"
wait_for "operations see the charge paid" '[ "$(get "/v1/ops/payments?limit=100")" = 200 ] && [ "$(charge_status "$CHARGE")" = SUCCEEDED ]'
BODY='{"amount_paise": 100, "reason": "smoke test goodwill"}'
expect "refund one rupee" "$(post "/v1/ops/charges/$CHARGE/refunds" "$BODY" "$(key)")" 202
expect_field amount.amount_paise 100

TOKEN=$RIDER_TOKEN
expect "no dues left" "$(get /v1/riders/me/dues)" 200
expect_field total.amount_paise 0
BODY="{\"quote_id\": \"$QUOTE\"}"
expect "book again" "$(post /v1/rides "$BODY" "$(key)")" 201
expect "cancel while searching" "$(post "/v1/rides/$(field id)/cancel" '{}' "$(key)")" 200

TOKEN=$DRIVER_TOKEN
TODAY=$(TZ=Asia/Kolkata date +%Y-%m-%d)
wait_for "the trip is in today's earnings" '[ "$(get "/v1/drivers/me/earnings?from=$TODAY&to=$TODAY")" = 200 ] && [ "$(field totals.rides)" -ge 1 ]'
expect "go offline" "$(post /v1/drivers/me/offline '{}' "$(key)")" 200

curl -sS "$MANAGEMENT/actuator/prometheus" > "$BODY_FILE"
for outcome in SUCCEEDED FAILED UNKNOWN UNRESOLVED; do
  grep -qF "payment_charges_total{outcome=\"$outcome\"}" "$BODY_FILE" \
      || { echo "payment_charges_total{outcome=\"$outcome\"} isn't exported" >&2; exit 1; }
done
echo "metrics: exported"
