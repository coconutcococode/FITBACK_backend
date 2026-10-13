"""Shopify Global Catalog(UCP MCP) lookup_catalog stub for local benchmarks.

실제 Shopify를 부하 테스트로 호출하지 않기 위해, 실측한 응답 지연과 응답 크기를 흉내 낸다.

- 지연: BASE_MS + PER_EXTRA_ID_MS * (요청 ID 수 - 1)
  2026-10-13 한국에서 실측한 중앙값: 1개 조회 484ms, 10개 묶음 조회 519ms
- 응답: 상품당 약 4.3KB, variant 1개 (실측 응답과 같은 구조)
- GET /stats 로 호출 수와 요청 ID 수를 확인하고, POST /stats/reset 으로 초기화한다.
"""

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE_MS = float(os.environ.get("STUB_BASE_MS", "480"))
PER_EXTRA_ID_MS = float(os.environ.get("STUB_PER_EXTRA_ID_MS", "4"))
PORT = int(os.environ.get("STUB_PORT", "18080"))
DESCRIPTION = "벤치마크용 상품 설명입니다. " * 200

_lock = threading.Lock()
_stats = {"calls": 0, "requested_ids": 0, "max_ids_per_call": 0}


def product_for(request_id: str) -> dict:
    suffix = request_id.rsplit("/", 1)[-1]
    product_id = f"gid://shopify/p/{suffix}"
    image = {
        "type": "image",
        "url": f"https://cdn.example.com/bench/{suffix}.jpg",
        "alt_text": f"bench product {suffix}",
    }
    return {
        "id": product_id,
        "title": f"Bench Product {suffix}",
        "description": {"plain": DESCRIPTION},
        "media": [image],
        "categories": [{"taxonomy": "merchant", "value": "Apparel > Outerwear"}],
        "variants": [
            {
                "id": request_id,
                "title": f"Bench Product {suffix}",
                "url": f"https://shop.example.com/products/{suffix}",
                "price": {"amount": 89000, "currency": "KRW"},
                "availability": {"available": True},
                "media": [image],
                "seller": {"id": "gid://shopify/Shop/bench", "name": "Bench Shop"},
                "checkout_url": f"https://shop.example.com/cart/{suffix}:1",
                "inputs": [{"id": request_id, "match": "exact"}],
            }
        ],
        "price_range": {
            "min": {"amount": 89000, "currency": "KRW"},
            "max": {"amount": 89000, "currency": "KRW"},
        },
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/stats":
            with _lock:
                self._send(200, dict(_stats))
            return
        self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        if self.path == "/stats/reset":
            with _lock:
                for key in _stats:
                    _stats[key] = 0
            self._send(200, {"reset": True})
            return

        request = json.loads(raw)
        params = request.get("params", {})
        catalog = params.get("arguments", {}).get("catalog", {})
        if params.get("name") != "lookup_catalog":
            self._send(200, {
                "jsonrpc": "2.0",
                "id": request.get("id"),
                "error": {"code": -32601, "message": "only lookup_catalog is stubbed"},
            })
            return

        ids = catalog.get("ids", [])
        with _lock:
            _stats["calls"] += 1
            _stats["requested_ids"] += len(ids)
            _stats["max_ids_per_call"] = max(_stats["max_ids_per_call"], len(ids))

        time.sleep((BASE_MS + PER_EXTRA_ID_MS * max(len(ids) - 1, 0)) / 1000)
        self._send(200, {
            "jsonrpc": "2.0",
            "id": request.get("id"),
            "result": {
                "structuredContent": {"products": [product_for(i) for i in ids]},
                "isError": False,
            },
        })


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    server.daemon_threads = True
    print(f"shopify stub listening on {PORT} (base={BASE_MS}ms, per_extra_id={PER_EXTRA_ID_MS}ms)")
    server.serve_forever()
