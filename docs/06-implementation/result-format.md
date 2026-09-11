# Result Format

## 디렉터리

```text
benchmark-result/<dir>/
├─ raw/
│  ├─ ground-truth-top10.jsonl        정답지
│  └─ benchmark-<run-id>.json         실행별 전체 결과
├─ csv/
│  └─ vector-db-result.csv            누적 비교표 (append)
├─ charts/
│  ├─ recall-latency-<run-id>.svg
│  └─ recall-latency-latest.svg       해당 디렉터리의 모든 raw를 합쳐 재생성
└─ logs/                              run-all-benchmarks.ps1 실행 시
```

`run-id`는 첫 결과의 `measuredAt`을 UTC `yyyyMMdd-HHmmss-SSS`로 포맷한 값입니다.

## ground-truth-top10.jsonl

```json
{"queryId":"q-001","topK":["chunk-000-00","chunk-000-04", ...]}
```

다른 도구에서 Recall을 독립 재검산할 때 씁니다.

## benchmark-&lt;run-id&gt;.json

`BenchmarkResult` 배열입니다. 시나리오 하나가 원소 하나입니다.

```json
{
  "database": "qdrant",
  "indexType": "hnsw",
  "targetRecall": 0.95,
  "actualRecall": 0.9417,
  "recallTolerance": 0.01,
  "targetMet": true,
  "recallSelection": "WITHIN_TOLERANCE",
  "tuningRecall": 0.9417,
  "averageLatencyMs": 23.55,
  "p50LatencyMs": 5.04,
  "p95LatencyMs": 126.64,
  "p99LatencyMs": 284.93,
  "qps": 420.01,
  "filtered":   {"queryExecutions": 150,  "recall": 0.88, "averageMs": ..., "p50Ms": ..., "p95Ms": ..., "p99Ms": ...},
  "unfiltered": {"queryExecutions": 1350, "recall": 0.95, "averageMs": ..., "p50Ms": ..., "p95Ms": ..., "p99Ms": ...},
  "averageCpuPercent": 213.31,
  "peakMemoryBytes": 101817958,
  "diskWriteBytes": 4096,
  "indexSizeBytes": -1,
  "indexBuildTimeMs": 5143,
  "upsertTimeMs": 3891,
  "vectorCount": 10000,
  "queryExecutions": 1500,
  "concurrency": 10,
  "topK": 10,
  "warmupIterations": 1,
  "measurementIterations": 5,
  "indexParameters": {...},
  "searchParameters": {"hnsw_ef": 1000},
  "environment": {...},
  "measuredAt": "2026-09-11T02:51:17.281Z"
}
```

## 필드 정의

### Recall

| 필드 | 의미 |
|---|---|
| `targetRecall` | 목표 |
| `actualRecall` | 본 측정 5회 평균 (전체 질의) |
| `recallTolerance` | 허용오차 (기본 0.01) |
| `targetMet` | 본 측정이 허용 범위를 충족했는지 |
| `recallSelection` | `WITHIN_TOLERANCE` / `CLOSEST_AVAILABLE` / `EXPLICIT_PARAMETERS` |
| `tuningRecall` | 튜닝 단계에서 그 후보가 기록한 Recall. 명시 파라미터면 null |

### 지연시간

| 필드 | 의미 |
|---|---|
| `averageLatencyMs`, `p50` / `p95` / `p99` | **전체 질의 합산.** 필터 질의가 소수면 p95·p99는 필터 비용을 반영 |
| `filtered.*` | 필터가 있는 질의만 |
| `unfiltered.*` | 필터가 없는 질의만 |

percentile은 nearest-rank 방식입니다.

```java
int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
```

**ANN 성능 비교에는 `unfiltered.*`를 씁니다.**
이유는 [../03-benchmark-design/metrics.md](../03-benchmark-design/metrics.md)에 있습니다.

### 자원

| 필드 | 의미 |
|---|---|
| `qps` | 측정 요청 수 ÷ 측정 구간 소요 시간 |
| `averageCpuPercent` | 대상 컨테이너 CPU 평균 합계. Milvus는 3개 합산 |
| `peakMemoryBytes` | 같은 대상 메모리 합계의 최대값 |
| `diskWriteBytes` | 검색 측정 중 Block I/O write 증가량. 볼륨 전체 크기와 다름 |
| `indexSizeBytes` | DB가 제공하는 범위에서만. 미지원 `-1` |
| `indexBuildTimeMs` | drop/create + 적재 + 비동기 인덱싱 완료까지 (첫 시나리오만) |
| `upsertTimeMs` | 위 시간에 포함되는 적재 구간 (첫 시나리오만) |

### environment

```json
{
  "os": "...", "cpu": "...", "availableProcessors": 16, "physicalMemoryBytes": ...,
  "javaVersion": "21...", "springBootVersion": "4.1.1", "dockerServerVersion": "29.7.2",
  "declaredVectorDbBudget": {"cpuCores": 4.0, "memoryBytes": 8589934592},
  "containerLimits": {"vector-qdrant": "cpuNano=4000000000,memoryBytes=8589934592,memorySwapBytes=8589934592"},
  "metric": "COSINE",
  "documentVectorsSha256": "cc23f0...",
  "queryDefinitionsSha256": "...",
  "queryVectorsSha256": "d06cc8..."
}
```

이 값이 다르면 서로 다른 실험입니다. 한 표에 섞지 않습니다.

## vector-db-result.csv

41개 컬럼입니다.

```text
database,index,target_recall,actual_recall,recall_tolerance,target_met,recall_selection,tuning_recall,
average_ms,p50_ms,p95_ms,p99_ms,qps,
filtered_queries,filtered_recall,filtered_average_ms,filtered_p50_ms,filtered_p95_ms,filtered_p99_ms,
unfiltered_queries,unfiltered_recall,unfiltered_average_ms,unfiltered_p50_ms,unfiltered_p95_ms,unfiltered_p99_ms,
cpu_percent,peak_memory_bytes,disk_write_bytes,index_size_bytes,time_to_index_ready_ms,upsert_ms,
vector_count,query_executions,concurrency,top_k,warmup_iterations,measurement_iterations,
index_parameters,search_parameters,environment,measured_at
```

- 같은 디렉터리에 실행할 때마다 **append**됩니다.
- 구간에 질의가 없으면 `*_queries`는 0, `*_recall`은 빈 값입니다.
- `index_parameters` / `search_parameters` / `environment`는 JSON 문자열을 CSV 인용한 값입니다.

### 스키마 호환성

`ResultWriter`가 기존 CSV의 헤더를 검사합니다.

```java
throw new IllegalStateException("Existing CSV schema is incompatible; use a new benchmark result directory: " + csv);
```

**컬럼이 바뀌면 기존 디렉터리에 이어 쓸 수 없습니다.** 새 디렉터리를 지정합니다.

## charts/recall-latency-*.svg

x축은 **unfiltered p95**, y축은 Recall입니다.
합산 p95는 필터 비용을 반영하므로 차트 축으로 쓰지 않습니다.
구간 데이터가 없는 예전 결과는 합산 p95로 대체됩니다.

같은 디렉터리의 모든 `raw/benchmark-*.json`을 다시 읽어 재생성하므로
다섯 DB를 순서대로 실행하면 마지막 차트에 전부 들어갑니다.

## 관련 문서

- [../03-benchmark-design/metrics.md](../03-benchmark-design/metrics.md)
- [code-architecture.md](code-architecture.md)
