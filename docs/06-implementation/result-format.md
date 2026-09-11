# 결과 형식

현재 결과의 기준은 [2026-09-11 T01~T28 전체 실행 보고서](../07-results/matrix-results-20260911.md)입니다. 42회 인덱스 재구축과 84회 케이스 실행을 완료했습니다. 이 문서는 저장 형식을 설명하며, 기존 `eligible` 필드를 이번 보고서의 제품 선정 조건으로 사용하지 않습니다.

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
| `stabilityDiagnostics` | Milvus serial/concurrent 표본과 전후 index/load/segment 상태. 다른 어댑터의 required=false는 진단 미실시 |
| `environment` | 입력 SHA-256, query split SHA-256, Docker 제한, 실행 환경 |

QPS 타이머 종료 후 Recall을 계산합니다. QPS는 필터·무필터가 섞인 전체 평가 검색의 처리량이고, 주 비교 p95는 무필터 구간입니다. Store 호출 시간에는 클라이언트 변환·통신·응답 처리도 들어가므로 DB 엔진 내부 시간만을 뜻하지 않습니다.

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
- median unfiltered p95와 혼합 QPS
- median average CPU, 전체 회차 peak CPU 최댓값, median RAM/index size/index build time
- `within_target_tolerance`: Recall 평균이 해당 목표 ±0.01인지
- `rebuild_recall_range`: 재구축 회차 간 comparison Recall max-min

단일 실행 행의 `targetMet`과 반복 집계의 `within_target_tolerance`를 혼동하지 않습니다. 후자는 평균만 검사하며 개별 실행의 목표 충족 횟수는 원시 행의 `targetMet`에서 별도로 계산합니다. 중앙값 p95/RAM도 모든 회차의 최댓값을 제한하는 판정이 아닙니다.

## 호환·기록용 legacy 판정 필드

아래 필드는 현재 실행 스크립트가 기존 계산식을 유지해 출력하는 값입니다. **이번 84회 보고서의 제품 선정·탈락에는 사용하지 않습니다.** 기본 임계값은 이 비교 프로토콜의 품질·성능 판정 조건과 구분합니다.

| 필드 | 현재 코드의 계산 |
|---|---|
| `passes_recall_floor` | comparison Recall 평균 ≥ `DecisionRecallMinimum` (기본 0.95) |
| `passes_p95` | unfiltered p95 중앙값 ≤ `DecisionP95LimitMs` (기본 30ms) |
| `passes_ram` | peak RAM 중앙값 ≤ `DecisionRamLimitBytes` (기본 2GiB) |
| `per_run_stability_verified` | 모든 행의 `stability_verified`가 true |
| `rebuild_stability_verified` | 필수 진단 대상이면 Recall range ≤0.05, 비대상이면 true |
| `stability_verified` | 위 두 안정성 집계값의 AND |
| `eligible` | 반복 완료 AND Recall 하한 AND p95 AND RAM AND 기존 stability 조건 |

`targetMet`과 `within_target_tolerance`는 `eligible` 계산에 직접 들어가지 않습니다. 따라서 목표 0.90을 충족해도 Recall 하한에는 미달할 수 있고, 반대로 목표를 크게 초과한 행도 하한은 통과할 수 있습니다.

필수 안정성 진단은 현재 Milvus에만 적용됩니다. 다른 DB의 true는 동일한 진단 통과를 의미하지 않으므로 모든 DB의 회차별 변동을 원시 값으로 확인해야 합니다. OpenSearch는 기본 4GiB heap으로 측정했으므로 2GiB 메모리 필터 결과를 제품의 최소 운영 메모리 검증으로 읽지 않습니다. [비교 결과 해석 기준](../07-results/decision.md)에 구체적인 구분을 정리했습니다.
