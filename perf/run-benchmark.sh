#!/usr/bin/env bash
# 세 가지 조회 방식(per-item, batch, batch-cache)을 같은 조건에서 차례로 측정한다.
# 사전 조건: perf/bench.env의 MySQL 실행, perf/shopify-stub/stub_server.py 실행, bootJar 빌드
set -euo pipefail
cd "$(dirname "$0")/.."

MODES="${MODES:-per-item batch batch-cache}"
VUS_LIST="${VUS_LIST:-1 10 50}"
DURATION="${DURATION:-60s}"
APP_PORT="${APP_PORT:-18081}"
STUB_URL="${STUB_URL:-http://127.0.0.1:18080}"
OUT="${OUT:-perf/results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"

wait_for_app() {
  for _ in $(seq 1 90); do
    if curl -sf "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "app did not start" >&2
  return 1
}

for mode in $MODES; do
  APP_PORT="$APP_PORT" REPORT_COUNT="${REPORT_COUNT:-20}" perf/run-app.sh "$mode" \
    >"$OUT/app-${mode}.log" 2>&1 &
  app_pid=$!
  wait_for_app
  report_ids="$(cat perf/.bench-report-id)"

  # JIT 워밍업: 결과에 포함하지 않는다.
  k6 run --quiet -e VUS=5 -e DURATION=15s -e REPORT_IDS="$report_ids" \
    -e BASE_URL="http://127.0.0.1:${APP_PORT}" perf/k6/analysis-detail.js >/dev/null

  for vus in $VUS_LIST; do
    curl -s -X POST "${STUB_URL}/stats/reset" >/dev/null
    k6 run --quiet -e VUS="$vus" -e DURATION="$DURATION" -e REPORT_IDS="$report_ids" \
      -e BASE_URL="http://127.0.0.1:${APP_PORT}" \
      --summary-export "$OUT/k6-${mode}-vus${vus}.json" \
      perf/k6/analysis-detail.js >"$OUT/k6-${mode}-vus${vus}.txt" 2>&1 || true
    curl -s "${STUB_URL}/stats" >"$OUT/stub-${mode}-vus${vus}.json"
    echo "done mode=${mode} vus=${vus}"
  done

  kill "$app_pid"
  wait "$app_pid" 2>/dev/null || true
done

python3 -I perf/summarize.py "$OUT" | tee "$OUT/summary.md"
