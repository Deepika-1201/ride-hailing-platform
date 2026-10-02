#!/usr/bin/env bash
# Runs a design spike and saves its raw output under results/raw. Usage: ./run.sh s1|s2|s3
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p results/raw
export COMPOSE_PROFILES=tools,ws

# CPU time used so far by a service's container, in microseconds (cgroup v2).
cpu_usec() { docker compose exec -T "$1" cat /sys/fs/cgroup/cpu.stat </dev/null | awk '$1 == "usage_usec" {print $2}'; }

# Runs the bench tool and adds the server's CPU use between the MEASURE_START and MEASURE_END markers.
bench() {
  local server=$1 seconds=$2 out=$3
  shift 3
  docker compose run --rm -T bench "$@" 2>&1 </dev/null | while IFS= read -r line; do
    echo "$line"
    case $line in
      MEASURE_START*) start=$(cpu_usec "$server") ;;
      MEASURE_END*) awk -v a="$start" -v b="$(cpu_usec "$server")" -v t="$seconds" \
        'BEGIN { printf "server_cpu_cores=%.2f\n", (b - a) / 1e6 / t }' ;;
    esac
  done | tee "$out"
}

s1() {
  docker compose up -d valkey postgis
  for tier in laptop cloud; do
    if [[ $tier == laptop ]]; then
      seconds=45 args=(-drivers 2000 -query-rate 15 -update-workers 16 -query-workers 8 -warmup 15s -measure 45s)
    else
      seconds=60 args=(-drivers 50000 -query-rate 300 -update-workers 64 -query-workers 16 -warmup 20s -measure 60s)
    fi
    for approach in redis-geo redis-h3 postgis postgis-unlogged; do
      server=valkey
      [[ $approach == postgis* ]] && server=postgis
      echo "=== S-1 $approach $tier"
      bench "$server" "$seconds" "results/raw/s1-$approach-$tier.txt" geo -approach "$approach" "${args[@]}"
    done
  done
}

s2() {
  docker compose up -d postgis
  for run in "cloud-250ms -rate 200 -poll-every 250ms" \
             "designed-250ms -rate 2000 -poll-every 250ms" \
             "designed-100ms -rate 2000 -poll-every 100ms" \
             "designed-outage -rate 2000 -poll-every 250ms -outage 10s"; do
    set -- $run
    name=$1
    shift
    echo "=== S-2 $name"
    bench postgis 60 "results/raw/s2-$name.txt" timers "$@"
  done
}

s3() {
  docker compose stop valkey postgis # frees memory for the JVM and 20,000 connections
  # Default Tomcat runs out of a 1 GB heap before 20,000 connections, so it stops at 10,000.
  for run in "servlet 5000,10000" "servlet-tuned 5000,10000,20000" "reactive 5000,10000,20000"; do
    set -- $run
    variant=$1 steps=$2 svc=ws-$1
    docker compose up -d --build "$svc"
    echo "=== S-3 $variant"
    docker compose run --rm -T bench wsclient -url "ws://$svc:8080/ws" -stats "http://$svc:8080/stats" \
      -steps "$steps" -hold 20s 2>&1 </dev/null | while IFS= read -r line; do
      echo "$line"
      case $line in
        HOLD_START*) start=$(cpu_usec "$svc") ;;
        STEP*) awk -v a="$start" -v b="$(cpu_usec "$svc")" \
          'BEGIN { printf "server_cpu_cores=%.2f\n", (b - a) / 1e6 / 20 }' ;;
      esac
    done | tee "results/raw/s3-$variant.txt"
    docker compose stop "$svc"
  done
}

"$@"
