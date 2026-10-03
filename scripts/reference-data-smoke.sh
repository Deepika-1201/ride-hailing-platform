#!/bin/sh
# Calls every admin and profile endpoint of phase 4 on a running seeded stack (local profile), as the seeded admin,
# rider 1 and driver 1. Writes use fresh names and codes, so it can run again. Exits non-zero on the first
# unexpected answer. Used by CI's container job and for local smoke tests.
set -eu
BASE=${BASE:-http://localhost:8080}
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

# call METHOD PATH TOKEN [JSON]: prints the status; the body goes to $BODY_FILE.
call() {
  if [ $# -ge 4 ]; then
    curl -sS -o "$BODY_FILE" -w '%{http_code}' -X "$1" -H "Authorization: Bearer $3" \
      -H 'Content-Type: application/json' -d "$4" "$BASE$2"
  else
    curl -sS -o "$BODY_FILE" -w '%{http_code}' -X "$1" -H "Authorization: Bearer $3" "$BASE$2"
  fi
}

expect() {
  if [ "$2" != "$3" ]; then
    echo "$1: expected $3, got $2: $(cat "$BODY_FILE")" >&2
    exit 1
  fi
  echo "$1: $2" >&2
}

# field PATH: a value from the last body, such as id or items.0.id.
field() {
  python3 -c 'import json, sys
value = json.load(open(sys.argv[1]))
for key in sys.argv[2].split("."):
    value = value[int(key)] if isinstance(value, list) else value[key]
print(value)' "$BODY_FILE" "$1"
}

random() {
  LC_ALL=C tr -dc "$1" </dev/urandom | head -c "$2"
}

sign_in() {
  BODY="{\"phone\": \"$1\"}"
  STATUS=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' -H 'Content-Type: application/json' -d "$BODY" "$BASE/v1/auth/otp")
  expect "code for $1" "$STATUS" 202
  BODY="{\"phone\": \"$1\", \"code\": \"123456\"}"
  STATUS=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' -H 'Content-Type: application/json' -d "$BODY" "$BASE/v1/auth/token")
  expect "token for $1" "$STATUS" 200
  field access_token
}

ADMIN=$(sign_in '+919000000002')
RIDER=$(sign_in '+918000000001')
DRIVER=$(sign_in '+917000000001')

# Seeded reference data.
expect "cities" "$(call GET /v1/admin/cities "$ADMIN")" 200
expect "Bengaluru" "$(call GET /v1/admin/cities/blr "$ADMIN")" 200
expect "Bengaluru's special areas" "$(call GET /v1/admin/cities/blr/special-areas "$ADMIN")" 200
expect "categories" "$(call GET /v1/admin/categories "$ADMIN")" 200
expect "MINI in Bengaluru" "$(call GET /v1/admin/cities/blr/categories/MINI "$ADMIN")" 200
expect "fare rules" "$(call GET '/v1/admin/fare-rules?city_id=blr' "$ADMIN")" 200
expect "fee rules" "$(call GET '/v1/admin/fee-rules?city_id=blr&category=MINI' "$ADMIN")" 200
expect "surge rules" "$(call GET '/v1/admin/surge-rules?city_id=blr' "$ADMIN")" 200
expect "verified drivers" "$(call GET '/v1/admin/drivers?city_id=blr&verification=VERIFIED&limit=5' "$ADMIN")" 200
CURSOR=$(field next_cursor)
expect "the next page" "$(call GET "/v1/admin/drivers?city_id=blr&verification=VERIFIED&limit=5&cursor=$CURSOR" "$ADMIN")" 200
expect "driver 1" "$(call GET /v1/admin/drivers/0199a3f0-0001-7000-8000-000000000001 "$ADMIN")" 200

# A new city in the Indian Ocean, with everything an admin sets up.
CITY="s$(random a-z 7)"
CODE="SMOKE-$(random A-Z 6)"
SQUARE='[[[60.0, -10.0], [60.4, -10.0], [60.4, -9.6], [60.0, -9.6], [60.0, -10.0]]]'
BODY="{\"id\": \"$CITY\", \"name\": \"Smoke $CITY\", \"time_zone\": \"Asia/Kolkata\", \"currency\": \"INR\", \"bounds\": {\"type\": \"Polygon\", \"coordinates\": $SQUARE}}"
expect "create a city" "$(call POST /v1/admin/cities "$ADMIN" "$BODY")" 201
expect "read it" "$(call GET "/v1/admin/cities/$CITY" "$ADMIN")" 200
BODY="{\"area\": {\"type\": \"MultiPolygon\", \"coordinates\": [$SQUARE]}}"
expect "set its service area" "$(call PUT "/v1/admin/cities/$CITY/service-area" "$ADMIN" "$BODY")" 204
BODY="{\"code\": \"$CODE\", \"name\": \"Smoke area\", \"kind\": \"OTHER\", \"area\": {\"type\": \"MultiPolygon\", \"coordinates\": [[[[60.1, -9.9], [60.2, -9.9], [60.2, -9.8], [60.1, -9.8], [60.1, -9.9]]]]}}"
expect "add a special area" "$(call POST "/v1/admin/cities/$CITY/special-areas" "$ADMIN" "$BODY")" 201
AREA=$(field id)
expect "list its special areas" "$(call GET "/v1/admin/cities/$CITY/special-areas" "$ADMIN")" 200
BODY='{"active": true, "offer_ttl_s": 15, "search_timeout_s": 180, "radius_start_m": 2000, "radius_step_m": 1000, "radius_max_m": 6000, "ranker": "nearest"}'
expect "offer MINI" "$(call PUT "/v1/admin/cities/$CITY/categories/MINI" "$ADMIN" "$BODY")" 200
expect "read MINI" "$(call GET "/v1/admin/cities/$CITY/categories/MINI" "$ADMIN")" 200
BODY="{\"city_id\": \"$CITY\", \"category\": \"MINI\", \"base_paise\": 4000, \"per_km_paise\": 1400, \"per_min_paise\": 150, \"minimum_paise\": 8000, \"booking_fee_paise\": 1000, \"tax_bp\": 500, \"commission_bp\": 2000, \"currency\": \"INR\"}"
expect "publish a fare rule" "$(call POST /v1/admin/fare-rules "$ADMIN" "$BODY")" 201
BODY="{\"city_id\": \"$CITY\", \"category\": \"MINI\", \"cancellation_fee_paise\": 5000, \"no_show_fee_paise\": 7500, \"commission_bp\": 2000, \"currency\": \"INR\"}"
expect "publish a fee rule" "$(call POST /v1/admin/fee-rules "$ADMIN" "$BODY")" 201
BODY="{\"city_id\": \"$CITY\", \"zone_id\": \"area:$CODE\", \"days_of_week\": [5, 6], \"start_local\": \"22:00\", \"end_local\": \"02:00\", \"multiplier\": 1.5}"
expect "add a surge rule" "$(call POST /v1/admin/surge-rules "$ADMIN" "$BODY")" 201
RULE=$(field id)
expect "change it" "$(call PATCH "/v1/admin/surge-rules/$RULE" "$ADMIN" '{"multiplier": 1.25, "version": 0}')" 200

# Onboard a driver there.
BODY="{\"phone\": \"+9196$(random 0-9 8)\", \"first_name\": \"Smoke\", \"city_id\": \"$CITY\"}"
expect "onboard a driver" "$(call POST /v1/admin/drivers "$ADMIN" "$BODY")" 201
NEW_DRIVER=$(field id)
expect "read the driver" "$(call GET "/v1/admin/drivers/$NEW_DRIVER" "$ADMIN")" 200
BODY='{"status": "VERIFIED", "reason": "Documents checked"}'
expect "verify the driver" "$(call POST "/v1/admin/drivers/$NEW_DRIVER/verification" "$ADMIN" "$BODY")" 200
BODY="{\"driver_id\": \"$NEW_DRIVER\", \"category\": \"MINI\", \"plate\": \"SM $(random 0-9 8)\", \"make\": \"Maruti Suzuki\", \"model\": \"Swift\", \"colour\": \"Red\"}"
expect "add a vehicle" "$(call POST /v1/admin/vehicles "$ADMIN" "$BODY")" 201
VEHICLE=$(field id)
expect "deactivate it" "$(call PATCH "/v1/admin/vehicles/$VEHICLE" "$ADMIN" '{"active": false, "version": 0}')" 200

# Tidy up: the special area and the city stop counting.
expect "deactivate the special area" "$(call DELETE "/v1/admin/cities/$CITY/special-areas/$AREA" "$ADMIN")" 204
expect "deactivate the city" "$(call PATCH "/v1/admin/cities/$CITY" "$ADMIN" '{"active": false, "version": 0}')" 200

# The seeded rider's profile, places and payment methods.
expect "rider profile" "$(call GET /v1/riders/me "$RIDER")" 200
expect "update it" "$(call PATCH /v1/riders/me "$RIDER" '{"first_name": "Aditi"}')" 200
BODY="{\"label\": \"smoke-$(random a-z 6)\", \"name\": \"12th Main, Indiranagar\", \"location\": {\"lat\": 12.97194, \"lon\": 77.64115}}"
expect "save a place" "$(call POST /v1/riders/me/places "$RIDER" "$BODY")" 201
PLACE=$(field id)
expect "list places" "$(call GET /v1/riders/me/places "$RIDER")" 200
expect "delete the place" "$(call DELETE "/v1/riders/me/places/$PLACE" "$RIDER")" 204
expect "payment methods" "$(call GET /v1/riders/me/payment-methods "$RIDER")" 200
BODY='{"type": "CARD", "provider_token": "tok_ok", "display": "Visa 4242"}'
expect "add a card" "$(call POST /v1/riders/me/payment-methods "$RIDER" "$BODY")" 201
CARD=$(field id)
expect "make it the default" "$(call POST "/v1/riders/me/payment-methods/$CARD/default" "$RIDER")" 200
expect "remove it" "$(call DELETE "/v1/riders/me/payment-methods/$CARD" "$RIDER")" 204

# The seeded driver's own view.
expect "driver profile" "$(call GET /v1/drivers/me "$DRIVER")" 200
[ "$(field verification)" = VERIFIED ] || { echo "driver 1 is not verified" >&2; exit 1; }
