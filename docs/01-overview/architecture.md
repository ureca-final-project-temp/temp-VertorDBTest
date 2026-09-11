# Architecture

이 문서는 측정값이 **어느 경계에서 생성되는지**를 설명합니다.

## 계층

```text
BenchmarkController  ─ POST /api/benchmarks/run
        ↓
BenchmarkRunner      ─ 전체 파라미터·반복 실행, 점별 저장
        ↓
VectorStore (port)   ─ DB 중립 계약
        ↓
┌───────┬────────┬──────────┬────────┬────────────┐
pgvector  Qdrant   Weaviate   Milvus   OpenSearch    ← adapter
 (JDBC)   (REST)   (GraphQL)  (REST)     (REST)
```

Ground Truth는 이 경로 밖에서 만듭니다.

```text
VectorDatasetLoader ─> ExactSearchEngine (Java brute-force) ─> Exact Top-K
```

DB가 반환한 결과를 정답으로 쓰지 않습니다. 어떤 DB의 인덱스가 잘못 만들어져도 Recall이 떨어져서 드러납니다.

## 측정 타이머의 경계

```java
VectorSearchRequest request = request(query, scenario, effectiveParameters);  // 타이머 밖
long started = System.nanoTime();
List<VectorSearchResult> approximate = store.search(request);                 // ← 타이머 안
latency.record(System.nanoTime() - started, filtered);
double recall = recallCalculator.recallAtK(...);                              // 타이머 밖
```

타이머 안에 들어가는 것:

- 요청 직렬화 (JSON 배열 또는 GraphQL 문자열, pgvector는 바이너리 JDBC)
- 네트워크 왕복
- DB의 검색 실행
- 응답 역직렬화

타이머 밖에 있는 것: Controller, embedding, Ground Truth 계산, Recall 계산, 자원 샘플링, 파라미터 적용과 추가 진단.

> 직렬화 비용이 DB마다 다르다는 점은 결과 해석에 영향을 줍니다.
> [../03-benchmark-design/limitations.md](../03-benchmark-design/limitations.md)를 봅니다.

## 자원 샘플링

`ResourceCollector`가 별도 데몬 스레드에서 이전 명령 완료 후 500ms 간격으로 `docker stats --no-stream`을 실행합니다.
수집 호출을 검색 요청 타이머에 직접 더하지 않습니다. 다만 같은 호스트의 수집 비용이 성능에 간접 영향을 줄 가능성까지 제거한 실험은 아닙니다.

- 측정 시작 직전 baseline 1회 — Block I/O write의 기준점으로만 사용합니다(유휴 CPU가 평균에 섞이지 않도록).
- 검색 구간 안에서 수집을 시작하고 마친 표본만 집계합니다. 측정 종료 후 유휴 표본을 더하지 않습니다. 검색을 최소 5초 유지하고 실제 시간과 표본 수를 저장합니다.

## Source of Truth

원본 문서와 청크는 PostgreSQL에 보관합니다(`V1__source_of_truth.sql`, Spring Data JDBC).
`BenchmarkSourceOfTruthSynchronizer`가 벡터 JSONL을 200개 원문과 10,000개 청크로 동기화하고,
`benchmark_dataset_state`에 입력 SHA-256과 건수를 기록합니다. 동일 스냅샷이면 재적재하지 않으며,
불일치한 상태에서 `rebuildAndLoad=false`이면 실행을 거부합니다. Flyway는 기존 비어 있지 않은
개발 볼륨도 baseline 0에서 V1·V2를 적용합니다.

전용 Vector DB 프로필에서도 PostgreSQL이 함께 뜨지만 **검색 요청 경로와 자원 측정 대상에서는 제외**합니다.
pgvector 프로필에서만 PostgreSQL이 측정 대상입니다.

## 컴포넌트별 코드 위치

| 개념 | 파일 |
|---|---|
| 전체 파라미터·반복 실행 | `benchmark/BenchmarkRunner.java` |
| Exact Top-K | `benchmark/ExactSearchEngine.java` |
| Recall@K | `benchmark/RecallCalculator.java` |
| 전체 검색 파라미터 그리드 확장 | `benchmark/SearchParameterSweep.java` |
| 지연시간 수집(필터/무필터 분리) | `benchmark/LatencyCollector.java` |
| 자원 샘플링 | `benchmark/ResourceCollector.java` |
| 결과 파일 출력 | `benchmark/ResultWriter.java` |
| 동일 파라미터 반복 집계 | `benchmark/BenchmarkSummary.java` |
| 전체 실측 산포도 | `benchmark/RecallLatencyPlot.java` |
| 저장된 JSON의 산포도 재생성 | `tools/BenchmarkChartGenerator.java` |
| PostgreSQL 원본 스냅샷 동기화 | `infrastructure/rdb/postgres/BenchmarkSourceOfTruthSynchronizer.java` |
| DB 중립 계약 | `port/VectorStore.java`, `port/VectorIndexManager.java` |
| DB별 구현 | `infrastructure/vector/<db>/` |

이번 실행에서 이 경로로 저장한 372개 점과 검증 근거는 [sweep 결과 보고서](../07-results/sweep-results-20260911.md)에 있습니다.

더 자세한 코드 지도는 [../06-implementation/code-architecture.md](../06-implementation/code-architecture.md)에 있습니다.
