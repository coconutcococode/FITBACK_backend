# 추천 결과 조회 성능 측정

분석 결과 상세 조회(`GET /api/v1/analyses/{reportId}`)에서 추천 상품 정보를 Shopify에서 다시 불러오는 비용을 측정한다.
Shopify 상품은 정책상 가격과 이미지를 저장하지 않고(`IDENTITY_ONLY`) 조회할 때마다 실시간으로 불러온다.

## 비교 대상

| 방식 | 설명 |
|---|---|
| `per-item` | batch lookup 도입(a9e775e) 이전 동작. 상품마다 DB 조회 1번과 Shopify `lookup_catalog` 1번 |
| `batch` | 현재 코드. 최대 50개를 묶어 `lookup_catalog` 1번 |
| `batch-cache` | 실험. 묶음 조회 결과를 30초 동안 메모리(Caffeine)에 보관 |

`per-item`은 옛 커밋을 다시 빌드하지 않고 재현했다. `ProductDetailService`는 catalog port가 `BatchProductCatalogPort`일 때만 묶음 조회를 쓰기 때문에,
bench 프로필에서 이 인터페이스를 숨긴 래퍼(`PerItemProductCatalogPort`)를 쓰면 이전과 같은 상품별 조회 경로를 탄다. 운영 설정에는 영향이 없다.

## 환경

- MacBook(Apple Silicon), Java 21, MySQL 9.6(로컬), k6
- Shopify는 부하 테스트로 호출하지 않고 가짜 서버(`shopify-stub/stub_server.py`)로 대체했다.
  - 지연 시간은 2026-10-13 한국에서 실제 `lookup_catalog`를 5번씩 호출해 잰 중앙값을 사용했다: 1개 조회 484ms, 10개 묶음 조회 519ms
  - 가짜 서버 설정: 480ms + 추가 ID당 4ms, 상품당 응답 약 4.2KB(실측 4.3KB)
- 데이터: 분석 결과 20개, 결과마다 서로 다른 추천 상품 10개(실제 화면의 카테고리별 대안 수와 비슷한 규모)
- k6 `constant-vus`로 VU 1, 10, 50을 각 60초씩 측정, 측정 전 15초 워밍업
- HikariCP 기본 커넥션 풀(10개)과 `open-in-view: false`. 운영(prod) 설정과 같다.

## 실행

```bash
# MySQL을 127.0.0.1:13306에 띄우고 fitback DB를 만든다(접속 정보: perf/bench.env).
python3 perf/shopify-stub/stub_server.py &   # 가짜 Shopify
./gradlew bootJar
perf/run-benchmark.sh                          # 결과: perf/results/<날짜>/summary.md
```

## 결과

전체 표는 `results/*/summary.md`에 있다. 각 조건은 1회씩 측정했다.

### 1. batch lookup 효과 (동시 사용자 1명)

| 방식 | p50 | p95 | 요청당 Shopify 호출 |
|---|---:|---:|---:|
| per-item | 4,972ms | 5,016ms | 10 |
| batch | 546ms | 553ms | 1 |

상품 10개짜리 추천 결과 기준으로 응답 시간이 약 9배 줄었다. 묶음 조회는 ID가 늘어도 지연이 거의 늘지 않아 효과가 그대로 나타난다.

### 2. 부하 테스트에서 발견한 커넥션 풀 교착

수정 전 코드는 동시 요청이 10개를 넘자 방식과 관계없이 멈췄다(`results/2026-10-13-before-fix`).

| 방식 | VU 10 실패율 | VU 50 실패율 |
|---|---:|---:|
| per-item | 100% | 100% |
| batch | 100% | 86% |
| batch-cache | 100% | 100% |

캐시로 외부 호출이 거의 없는 경우에도 멈췄기 때문에 원인은 외부 API 지연이 아니라 트랜잭션 구조였다.

- `AnalysisService.getReport`가 `@Transactional(readOnly = true)`로 커넥션을 잡은 상태에서 `RecommendationQueryService`(`NOT_SUPPORTED`)를 호출한다.
- `NOT_SUPPORTED`는 바깥 트랜잭션을 잠시 멈출 뿐 커넥션은 반납하지 않는다. 안쪽 조회가 커넥션을 하나 더 요청하므로 요청 하나가 커넥션 2개를 필요로 한다.
- 동시 요청 수가 풀 크기(10)에 닿으면 모든 요청이 커넥션 1개를 쥔 채 두 번째 커넥션을 기다리고, 30초 후 `HikariPool - Connection is not available`로 실패한다.

두 단계로 고쳤다.

1. `AnalysisService.getReport`: 리포트와 태그, 저장 상태는 짧은 읽기 전용 트랜잭션에서 먼저 읽고, 추천 결과 조회는 트랜잭션이 끝난 뒤 호출한다.
   → 교착이 사라졌지만(VU 50 실패율 0%) 처리량이 18.6 RPS에서 멈췄다(`results/2026-10-13-fix-step1`).
   스레드 덤프에서 Shopify 응답을 기다리는 스레드 10개가 커넥션 10개를 모두 쥐고, 나머지 40개는 커넥션을 기다리고 있었다.
   `NOT_SUPPORTED` 구간 안에서 연 영속성 컨텍스트가 외부 호출이 끝날 때까지 커넥션을 붙잡고 있었기 때문이다.
2. `RecommendationQueryService`: 추천 항목과 저장 여부 조회를 짧은 읽기 전용 트랜잭션으로 묶어 먼저 끝내고, Shopify 조회는 그 밖에서 실행한다.
   → 같은 부하에서 50개 스레드가 모두 Shopify 응답만 기다리고, 커넥션 대기는 0개가 됐다.

수정 후 결과(`results/2026-10-13-after-fix`):

| 방식 | VU | RPS | p50 | p95 | 실패율 |
|---|---:|---:|---:|---:|---:|
| batch | 10 | 18.3 | 544ms | 557ms | 0% |
| batch | 50 | 91.8 | 541ms | 564ms | 0% |
| per-item | 50 | 10.1 | 4,936ms | 4,992ms | 0% |

VU 50 기준 처리량은 수정 1단계 18.6 RPS에서 91.8 RPS로 늘었고, p50은 2,666ms에서 541ms로 줄었다.
회귀 테스트로 `AnalysisServiceTest`에 "트랜잭션 커밋 후 추천 결과 조회" 순서 검증을 추가했고, 전체 테스트 1,016개가 통과했다.

### 3. 캐시 실험 (적용 보류)

| VU | RPS | p50 | p95 | 캐시 적중률 |
|---:|---:|---:|---:|---:|
| 10 | 1,493.7 | 4ms | 7ms | 100% |
| 50 | 2,031.9 | 12ms | 54ms | 98% |

리포트 20개를 반복 조회하는 조건이라 적중률이 매우 높게 나온 최선의 경우다. 다음 이유로 운영 적용은 보류했다.

- Shopify 공급자는 가격과 이미지를 저장하지 않고 실시간 조회하도록 설정되어 있다(`requiresLiveLookup=true`, `canPersistPrice=false`). 30초라도 가격과 재고가 바뀐 것을 놓칠 수 있다.
- TTL이 끝나는 순간 같은 상품을 여러 요청이 동시에 다시 조회하는 현상(cache stampede)이 보였다. VU 50에서 60초 동안 Shopify 호출이 1,873번으로, 리포트 수(20개) 기준 예상치보다 훨씬 많았다.
- 실제로 같은 분석 결과를 짧은 시간에 반복 조회하는 비율을 아직 모른다.

적용한다면 키별 동시 로딩을 하나로 합치는 방식(예: Caffeine `AsyncLoadingCache`)과, 공급자 정책을 확인한 짧은 TTL이 필요하다.

## 한계

- 로컬 단일 머신(MySQL 9.6)에서 측정했다. 운영 RDS(MySQL 8, db.t4g.micro)와 EC2(t3.micro)에서는 절대 수치가 다를 수 있다.
- Shopify 지연은 실측 중앙값으로 고정했다. 실제 응답 시간의 편차(431~775ms)는 반영하지 않았다.
- 조건마다 1회씩 측정했다.
- local 프로필의 SQL 로그 출력이 켜진 상태로 측정했다. 모든 방식이 같은 조건이지만, 요청이 매우 빠른 캐시 방식의 절대 처리량은 로그 출력 비용의 영향을 받았을 수 있다.
