#!/usr/bin/env bash
# Steps the request rate up for the baseline and the limited endpoint and prints a table.
#   RATES="1000 2000 4000" DURATION=30s ./loadtest/run-benchmark.sh
#   K6_NATIVE=1 TARGETS=limited ./loadtest/run-benchmark.sh      # k6 on the host instead of in Docker
#   (Linux hosts only: on Colima, the host->VM port forwarder becomes the bottleneck)
set -euo pipefail
cd "$(dirname "$0")/.."
RATES=${RATES:-"1000 2000 4000 6000 8000"}
TARGETS=${TARGETS:-"baseline limited"}
# K6_NATIVE=1 runs k6 installed on the host (brew install k6) instead of in a container
if [[ "${K6_NATIVE:-0}" == "1" ]]; then
  set -a; . ./.env; set +a
fi
DURATION=${DURATION:-30s}
mkdir -p loadtest/results
rm -f loadtest/results/*.json

for target in $TARGETS; do
  for rate in $RATES; do
    echo ">> $target @ ${rate} req/s for $DURATION"
    if [[ "${K6_NATIVE:-0}" == "1" ]]; then
      # k6 on the host, outside the Docker VM, so it doesn't compete with the stack for VM CPUs
      k6 run -q -e TARGET="$target" -e RATE="$rate" -e DURATION="$DURATION" \
        -e BASE_URL=http://localhost:8080 -e ADMIN_TOKEN="$ADMIN_TOKEN" -e RESULTS_DIR=loadtest/results \
        loadtest/k6/throughput.js || true
    else
      docker compose --profile loadtest run --rm -e TARGET="$target" -e RATE="$rate" -e DURATION="$DURATION" \
        k6 run -q -o experimental-prometheus-rw /scripts/throughput.js || true
    fi
  done
done

echo
printf '%-9s %8s %10s %8s %8s %8s %8s %8s\n' target rate achieved p50ms p95ms p99ms errors dropped
for f in $(ls loadtest/results/*.json | sort -t- -k2 -n); do
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print('%-9s %8d %10d %8.2f %8.2f %8.2f %7.2f%% %8d' % (d['target'], d['targetRate'], d['achievedRps'], d['p50ms'], d['p95ms'], d['p99ms'], d['errorRate']*100, d['dropped']))" "$f"
done
