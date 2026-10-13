// 분석 결과 상세 조회(GET /api/v1/analyses/{reportId}) 부하 테스트.
// 사용법: k6 run -e VUS=10 -e DURATION=60s -e REPORT_IDS=1,2,3 perf/k6/analysis-detail.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18081';
const REPORT_IDS = (__ENV.REPORT_IDS || '').split(',').filter((id) => id.length > 0);

export const options = {
  scenarios: {
    detail: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 1),
      duration: __ENV.DURATION || '60s',
      gracefulStop: '60s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // setup 단계의 로그인 요청을 빼고 상세 조회만 집계하기 위한 sub-metric
  thresholds: {
    'http_req_duration{scenario:detail}': ['max>=0'],
    'http_reqs{scenario:detail}': ['count>=0'],
    'http_req_failed{scenario:detail}': ['rate>=0'],
  },
};

export function setup() {
  if (REPORT_IDS.length === 0) {
    throw new Error('REPORT_IDS is required');
  }
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({
      email: __ENV.EMAIL || 'bench@fitback.local',
      password: __ENV.PASSWORD || 'BenchPass123!',
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  return { token: response.json('data.accessToken') };
}

export default function (data) {
  const reportId = REPORT_IDS[Math.floor(Math.random() * REPORT_IDS.length)];
  const response = http.get(`${BASE_URL}/api/v1/analyses/${reportId}`, {
    headers: { Authorization: `Bearer ${data.token}` },
    timeout: '60s',
    tags: { name: 'GET /api/v1/analyses/{reportId}' },
  });
  check(response, {
    'status is 200': (r) => r.status === 200,
    'all items resolved': (r) => r.status === 200 && r.json('data.recommendationGroups')
      .every((group) => group.items.every((item) => item.name !== null)),
  });
}
