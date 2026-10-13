#!/usr/bin/env bash
# 사용법: perf/run-app.sh <per-item|batch|batch-cache>
set -euo pipefail
MODE="${1:-batch}"
cd "$(dirname "$0")/.."
set -a
source perf/bench.env
set +a
exec java -jar build/libs/FITBACK-Backend-0.0.1-SNAPSHOT.jar \
  --server.port="${APP_PORT:-18081}" \
  --bench.catalog-mode="${MODE}" \
  --bench.cache-ttl="${CACHE_TTL:-PT30S}" \
  --bench.seed.item-count="${ITEM_COUNT:-10}" \
  --bench.seed.report-count="${REPORT_COUNT:-1}" \
  --management.health.mail.enabled=false \
  --spring.mail.host=127.0.0.1 \
  --spring.mail.port=1 \
  --logging.level.root=WARN \
  --logging.level.com.fitback.backend.bench=INFO
