#!/usr/bin/env bash
# Steps the request rate up for the baseline and the limited endpoint and prints a table.
#   RATES="1000 2000 4000" DURATION=30s ./loadtest/run-benchmark.sh
set -euo pipefail
cd "$(dirname "$0")/.."
RATES=${RATES:-"1000 2000 4000 6000 8000"}
DURATION=${DURATION:-30s}
mkdir -p loadtest/results
rm -f loadtest/results/*.json

for target in baseline limited; do
  for rate in $RATES; do
    echo ">> $target @ ${rate} req/s for $DURATION"
    docker compose --profile loadtest run --rm -e TARGET="$target" -e RATE="$rate" -e DURATION="$DURATION" \
      k6 run -q -o experimental-prometheus-rw /scripts/throughput.js || true
  done
done

echo
printf '%-9s %8s %10s %8s %8s %8s %8s %8s\n' target rate achieved p50ms p95ms p99ms errors dropped
for f in $(ls loadtest/results/*.json | sort -t- -k2 -n); do
  python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print('%-9s %8d %10d %8.2f %8.2f %8.2f %7.2f%% %8d' % (d['target'], d['targetRate'], d['achievedRps'], d['p50ms'], d['p95ms'], d['p99ms'], d['errorRate']*100, d['dropped']))" "$f"
done
