# 결과 형식

## 디렉터리

```text
<result-directory>/
├─ raw/benchmark-*.json
├─ raw/ground-truth-top10.jsonl
├─ csv/vector-db-result.csv
├─ charts/recall-latency-latest.svg
├─ logs/
├─ api-response-run-*.json
├─ execution-order.json
└─ summary/vector-db-summary.csv
```

CSV schema가 기존 파일과 다르면 이어 쓰지 않고 중단합니다. 하네스 변경 뒤에는 새 result directory를 사용합니다.

## 원시 행 핵심 필드

| 필드 | 의미 |
|---|---|
| `testId`, `runNumber` | T01~T28와 전체 재구축 회차 |
| `database`, `engine`, `indexType` | 실제 어댑터 식별자 |
| `targetRecall` | 0.90 또는 0.95 |
| `actualRecall` | evaluation 전체(필터 포함) Recall |
| `comparisonRecall` | evaluation 무필터 Recall. ANN 주 비교값 |
| `recallTolerance` | 기본 0.01 |
| `targetMet` | evaluation comparisonRecall이 목표 ±허용범위인지 |
| `calibrationSelection` | `WITHIN_TOLERANCE`, `CLOSEST_AVAILABLE`, `EXPLICIT_PARAMETERS` |
| `calibrationRecall` | 선택 당시 calibration 무필터 Recall |
| `searchParameters` | 실제 ef/probes/nprobe/searchProbe/nprobes/candidate_k |
| `filtered`, `unfiltered` | 모집단별 query count, Recall, 평균, p50/p95/p99 |
| `qps` | evaluation search 완료 시간만 사용한 처리량 |
| `averageCpuPercent`, `peakCpuPercent` | 대상 컨테이너 합산 CPU 평균/최대 |
| `averageMemoryBytes`, `peakMemoryBytes` | 대상 컨테이너 합산 RAM 평균/최대 |
| `indexSizeBytes` | 제품에서 분리 측정 가능한 인덱스 크기. 미지원 -1 |
| `indexBuildTimeMs` | drop/create부터 적재·flush/refresh·ready까지 |
| `upsertTimeMs` | 적재 API 구간 |
| `stabilityDiagnostics` | Milvus serial/concurrent 표본과 전후 index/load/segment 상태 |
| `environment` | 입력 SHA-256, query split SHA-256, Docker 제한, 실행 환경 |

QPS 타이머 종료 후 Recall을 계산하므로 QPS와 latency는 같은 순수 검색 workload를 설명합니다.

## CSV 컬럼

```text
test_id,run_number,database,engine,index,target_recall,actual_recall,comparison_recall,
recall_tolerance,target_met,calibration_selection,calibration_recall,
average_ms,p50_ms,p95_ms,p99_ms,qps,
filtered_queries,filtered_recall,filtered_average_ms,filtered_p50_ms,filtered_p95_ms,filtered_p99_ms,
unfiltered_queries,unfiltered_recall,unfiltered_average_ms,unfiltered_p50_ms,unfiltered_p95_ms,unfiltered_p99_ms,
cpu_average_percent,cpu_max_percent,ram_average_bytes,ram_max_bytes,disk_write_bytes,
index_size_bytes,time_to_index_ready_ms,upsert_ms,vector_count,query_executions,
concurrency,top_k,warmup_iterations,measurement_iterations,
stability_verified,stability_diagnostics,index_parameters,search_parameters,environment,measured_at
```

JSON 객체는 CSV에서 인용된 JSON 문자열입니다.

## 집계 행

`summary/vector-db-summary.csv`는 test ID별로 다음을 계산합니다.

- completed runs
- comparison Recall average/min/max
- median unfiltered p95와 QPS
- median CPU/RAM/index size/index build time
- `within_target_tolerance`: Recall 평균이 해당 목표 ±0.01인지
- `passes_recall_floor`: Recall 평균이 의사결정 하한(기본 0.95) 이상인지
- `rebuild_recall_range`: 재구축 회차 간 comparison Recall max-min
- `stability_verified`: 모든 회차의 같은-index drift 진단과, 필수 대상의 rebuild Recall range ≤0.05를 모두 통과했는지
- `eligible`: 반복 완료와 Recall/p95/RAM/stability 규칙을 모두 통과했는지

단일 실행 행의 `targetMet`과 반복 집계의 `within_target_tolerance`을 혼동하지 않습니다.
