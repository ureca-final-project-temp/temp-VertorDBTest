# 실험 설계

현재 기준은 [current-protocol.md](current-protocol.md)입니다.

## 1. 실행 단위

`data/benchmark-matrix.json`의 같은 DB/engine/index 두 행(Recall 0.90, 0.95)을 한 번의 인덱스 재구축에서 측정합니다. 실행기는 전체 조합을 3~5회 반복합니다.

```text
repeat N
  DB 순서 교차
    index 조합별
      DB 시작 및 4 vCPU/8GiB 제한 검증
      drop → create
      vector load
      flush/refresh/async index ready 대기
      calibration으로 search parameter 선택
      evaluation warm-up
      evaluation measurement
      결과 저장
      DB 중지
```

각 repeat에서 다시 구축하므로 build 편차가 결과에 포함됩니다. `time_to_index_ready_ms`와 `upsert_ms`는 같은 재구축을 공유하는 두 목표 행 모두에 기록됩니다.

## 2. Query 분리

기본 300개를 query type별로 층화한 뒤 query ID의 SHA-256 순서로 고정 분할합니다.

| 용도 | 건수 | 사용 위치 |
|---|---:|---|
| calibration | 100 | ef/probes/nprobe/searchProbe/candidate_k 선택 |
| evaluation | 200 | warm-up, 최종 Recall, latency, QPS |

두 집합의 ID SHA-256은 환경 정보에 저장됩니다. evaluation 결과로 파라미터를 다시 선택하면 데이터 누수이므로 금지합니다.

## 3. 측정 부하

| 항목 | 기본값 |
|---|---:|
| topK | 10 |
| concurrency | 10 |
| warm-up | evaluation 전체 1회 |
| measurement | evaluation 전체 5회 |
| 최종 검색 요청 | 200 × 5 = 1,000 |

QPS 시간 구간은 search future 제출부터 모든 search 결과 수집까지입니다. Recall 계산과 결과 직렬화는 분모에서 제외합니다.

## 4. Recall 목표 선택

각 인덱스가 실제로 지원하는 한 개의 검색 폭 파라미터만 후보 그리드로 바꿉니다. calibration Recall이 목표 ±0.01에 들어오는 후보 중 비용이 작은 값을 선택하고, 없으면 가장 가까운 실제 Recall 후보를 선택합니다.

`calibration_selection`은 calibration의 선택 결과이고 `target_met`은 독립 evaluation Recall 판정입니다. 두 컬럼은 같은 의미가 아닙니다.

## 5. 반복 및 순서

기본 3회 순서는 다음과 같습니다.

1. pgvector → Qdrant → Weaviate → Milvus → OpenSearch
2. Weaviate → OpenSearch → pgvector → Qdrant → Milvus
3. Milvus → Qdrant → OpenSearch → Weaviate → pgvector

4·5회용 순서도 스크립트에 고정돼 있습니다. 최종 집계는 Recall 평균/min/max, median p95/QPS/자원/구축 시간을 사용합니다.

## 6. Milvus drift 감사

Milvus는 매 결과 행마다 선택된 동일 파라미터와 calibration 무필터 query를 사용해 serial 3회, 본 concurrency 3회를 추가 실행합니다. 타이머 밖에서 수행하며 성능 지표에는 섞지 않습니다. 진단 전후 index/load/query-segment 상태를 저장합니다.

calibration Recall 대비 concurrent 최대 편차 또는 serial/concurrent 중앙값 차이가 기본 0.05를 넘거나 상태 수집이 실패하면 `stability_verified=false`입니다.

## 7. 확장 단계

- `run-filter-selectivity-benchmarks.ps1`: 1%/10%/50% 필터 workload
- `run-shortlist-scale-validation.ps1`: 실제 100k 또는 1M 입력, 최소 건수 강제
- `run-real-workload-validation.ps1`: 문서/query에 synthetic=true가 있으면 거부
