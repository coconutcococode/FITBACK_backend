"""run-benchmark.sh 결과 폴더를 읽어 모드별 비교 표(Markdown)를 출력한다."""

import json
import re
import sys
from pathlib import Path

ITEMS_PER_REPORT = 10


def load(path: Path) -> dict:
    return json.loads(path.read_text()) if path.exists() else {}


def main(out_dir: str) -> None:
    out = Path(out_dir)
    rows = []
    for summary in sorted(out.glob("k6-*-vus*.json")):
        match = re.match(r"k6-(.+)-vus(\d+)\.json", summary.name)
        mode, vus = match.group(1), int(match.group(2))
        metrics = load(summary).get("metrics", {})
        duration = metrics.get("http_req_duration{scenario:detail}", {})
        requests = metrics.get("http_reqs{scenario:detail}", {})
        failed = metrics.get("http_req_failed{scenario:detail}", {})
        checks = metrics.get("checks", {})
        stub = load(out / f"stub-{mode}-vus{vus}.json")
        detail_requests = int(requests.get("count", 0))
        calls_per_request = stub.get("calls", 0) / detail_requests if detail_requests else 0
        cache_hit = 1 - stub.get("requested_ids", 0) / (detail_requests * ITEMS_PER_REPORT) \
            if detail_requests else 0
        rows.append((
            mode, vus, detail_requests, requests.get("rate", 0),
            duration.get("med", 0), duration.get("p(95)", 0), duration.get("max", 0),
            failed.get("value", 0), checks.get("value", 0),
            stub.get("calls", 0), calls_per_request, cache_hit,
        ))

    order = {"per-item": 0, "batch": 1, "batch-cache": 2}
    rows.sort(key=lambda row: (row[1], order.get(row[0], 9)))
    print("| 방식 | VU | 요청 수 | RPS | p50 (ms) | p95 (ms) | 최대 (ms) | 실패율 | 검증 통과율 "
          "| Shopify 호출 | 요청당 호출 | 캐시 적중률 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for row in rows:
        (mode, vus, count, rps, p50, p95, peak, fail, check, calls, per_req, hit) = row
        hit_text = f"{max(hit, 0):.0%}" if mode.endswith("cache") and fail < 1 else "-"
        print(f"| {mode} | {vus} | {count} | {rps:.2f} | {p50:.0f} | {p95:.0f} | {peak:.0f} "
              f"| {fail:.1%} | {check:.1%} | {calls} | {per_req:.2f} | {hit_text} |")


if __name__ == "__main__":
    main(sys.argv[1])
