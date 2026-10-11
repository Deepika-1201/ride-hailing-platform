#!/usr/bin/env bash
set -euo pipefail

check_samples() {
  local output=$1 interval=$2 previous=0 current sample stamp failed=0
  if [[ -f "$output/sample-interval-seconds.txt" ]]; then
    if ! IFS= read -r interval < "$output/sample-interval-seconds.txt" || [[ ! "$interval" =~ ^[1-9][0-9]*$ ]]; then
      printf '%s\n' 'Invalid recorded sampling interval.' >&2
      return 1
    fi
  fi
  local maximum_gap=$((interval * 2 + 10))
  local samples=()
  shopt -s nullglob
  samples=("$output/metrics/"*.prom)
  if [[ ${#samples[@]} -lt 2 ]]; then
    printf 'Not enough metrics samples in %s\n' "$output" >&2
    return 1
  fi
  for sample in "${samples[@]}"; do
    stamp=${sample##*/}
    stamp=${stamp%.prom}
    if [[ ! "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ || ! -s "$sample" ]]; then
      printf 'Invalid metrics sample: %s\n' "$sample" >&2
      return 1
    fi
    current=$(date -u -j -f '%Y%m%dT%H%M%SZ' "$stamp" '+%s' 2>/dev/null) || \
      current=$(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2}T${stamp:9:2}:${stamp:11:2}:${stamp:13:2}Z" '+%s') || return 1
    if [[ "$previous" -ne 0 && $((current - previous)) -gt "$maximum_gap" ]]; then
      printf 'Metrics gap before %s: %s seconds (maximum %s)\n' \
        "$stamp" "$((current - previous))" "$maximum_gap" >&2
      failed=1
    fi
    previous=$current
  done
  if [[ -s "$output/metrics-failures.txt" ]]; then
    printf '%s\n' 'One or more metrics scrapes failed.' >&2
    failed=1
  fi
  printf 'Checked %s metrics samples.\n' "${#samples[@]}"
  return "$failed"
}

SAMPLE_INTERVAL=${SAMPLE_INTERVAL:-15}
if [[ ! "$SAMPLE_INTERVAL" =~ ^[1-9][0-9]*$ ]]; then
  printf '%s\n' 'SAMPLE_INTERVAL must be a positive number of seconds.' >&2
  exit 2
fi

if [[ ${1:-} == --check-samples ]]; then
  if [[ $# -ne 2 ]]; then
    printf '%s\n' 'Usage: laptop-tier.sh --check-samples output-directory' >&2
    exit 2
  fi
  check_samples "$2" "$SAMPLE_INTERVAL"
  exit $?
fi

if [[ ${1:-} == --help ]]; then
  printf '%s\n' 'Usage: bash scripts/laptop-tier.sh [output-directory]' \
    'Requires the local API, management endpoint, Docker and OSRM already running.' \
    'Runs 2,000 drivers with five minutes of ramp-up and thirty further minutes of demand.' \
    'API_URL, MANAGEMENT_URL, OSRM_URL and WS_URL override local defaults.' \
    'SCENARIO selects a rehearsal scenario; SAMPLE_INTERVAL defaults to 15 seconds.' \
    '--check-samples output-directory validates existing metrics without running load.' \
    'Rechecks use the recorded sampling interval when present.' \
    'macOS runs require AC power and an open lid.' \
    'Output contains private refresh tokens; keep it outside version control.'
  exit 0
fi

ROOT=$(cd "$(dirname "$0")/.." && pwd)
API_URL=${API_URL:-http://localhost:8080}
MANAGEMENT_URL=${MANAGEMENT_URL:-http://localhost:8081}
OSRM_URL=${OSRM_URL:-http://localhost:5000}
WS_URL=${WS_URL:-ws://localhost:8080/ws}
SCENARIO=${SCENARIO:-"$ROOT/simulator/scenarios/laptop-baseline.yaml"}
OUTPUT=${1:-"$ROOT/build/capacity/$(date -u +%Y%m%dT%H%M%SZ)"}
if command -v pmset >/dev/null && ! pmset -g batt | grep -q 'AC Power'; then
  printf '%s\n' 'Connect AC power and keep the lid open before measuring the laptop tier.' >&2
  exit 2
fi
umask 077
mkdir -p "$OUTPUT"
OUTPUT=$(cd "$OUTPUT" && pwd)
if [[ -e "$OUTPUT/environment.txt" || -e "$OUTPUT/report.json" ]]; then
  printf 'Refusing to overwrite an earlier measurement in %s\n' "$OUTPUT" >&2
  exit 2
fi
mkdir -p "$OUTPUT/metrics"
cp "$SCENARIO" "$OUTPUT/scenario.yaml"
printf '%s\n' "$SAMPLE_INTERVAL" > "$OUTPUT/sample-interval-seconds.txt"

curl --fail --silent --show-error --max-time 10 "$API_URL/readyz" > "$OUTPUT/readiness.json"
curl --fail --silent --show-error --max-time 10 \
  "$OSRM_URL/route/v1/driving/77.60502,12.97571;77.62448,12.93524?overview=false" > "$OUTPUT/routing.json"
docker info --format '{{json .}}' > "$OUTPUT/docker-info.json"
{
  date -u +%Y-%m-%dT%H:%M:%SZ
  uname -a
  go version
  git -C "$ROOT" rev-parse HEAD
  if command -v pmset >/dev/null; then pmset -g batt; fi
} > "$OUTPUT/environment.txt"
curl --fail --silent --show-error --max-time 10 \
  "$MANAGEMENT_URL/actuator/prometheus" > "$OUTPUT/metrics-before.prom"

cd "$ROOT/simulator"
go build -o "$OUTPUT/sim" ./cmd/sim
docker stats --format '{{json .}}' > "$OUTPUT/container-stats.jsonl" &
STATS_PID=$!

collect_metrics() {
  while true; do
    sample=$(date -u +%Y%m%dT%H%M%SZ)
    if curl --fail --silent --show-error --max-time 10 "$MANAGEMENT_URL/actuator/prometheus" \
        > "$OUTPUT/metrics/$sample.prom.part"; then
      mv "$OUTPUT/metrics/$sample.prom.part" "$OUTPUT/metrics/$sample.prom"
    else
      rm -f "$OUTPUT/metrics/$sample.prom.part"
      printf '%s\n' "$sample" >> "$OUTPUT/metrics-failures.txt"
    fi
    sleep "$SAMPLE_INTERVAL"
  done
}
collect_metrics &
METRICS_PID=$!
trap 'kill "$STATS_PID" "$METRICS_PID" 2>/dev/null || true' EXIT

SIM=("$OUTPUT/sim")
if command -v caffeinate >/dev/null; then
  SIM=(caffeinate -dims "${SIM[@]}")
fi

set +e
"${SIM[@]}" run -api "$API_URL" -ws "$WS_URL" -osrm "$OSRM_URL" \
  -scenario "$SCENARIO" -tokens "$OUTPUT/tokens.json" \
  -report "$OUTPUT/report.json" 2>&1 | tee "$OUTPUT/run.log"
run_exit=${PIPESTATUS[0]}
set -e

curl --fail --silent --show-error --max-time 10 \
  "$MANAGEMENT_URL/actuator/prometheus" > "$OUTPUT/metrics-after.prom" || run_exit=1
if ! check_samples "$OUTPUT" "$SAMPLE_INTERVAL" && [[ "$run_exit" -eq 0 ]]; then
  run_exit=1
fi
printf '%s\n' "$run_exit" > "$OUTPUT/exit-code.txt"
printf 'Results: %s\n' "$OUTPUT"
exit "$run_exit"